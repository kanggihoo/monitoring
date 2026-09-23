from __future__ import annotations

import socket
from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import create_app

TEST_INTERNAL_KEY = "test-internal-key"

#: 테스트가 나갈 수 있는 유일한 주소. TestClient 는 앱을 그 자리에서 부르므로 소켓을 쓰지 않고,
#: `MockTransport` 도 마찬가지다 — 정상 경로는 이 목록조차 필요 없다.
LOCAL_HOSTS = frozenset({"127.0.0.1", "::1", "localhost", "0.0.0.0"})  # noqa: S104


class BlockedNetwork(OSError):
    """바깥으로 나가려는 시도를 막았다.

    **`OSError` 를 상속하는 것이 중요하다.** 예전에는 `RuntimeError` 를 던졌는데, 그러면
    네트워크 오류를 자기 예외로 옮기는 계층(`anyio` → `httpcore` → `httpx`)이 이 예외를
    "네트워크 오류" 로 알아보지 못한다. `anyio` 의 태스크 그룹이 `ExceptionGroup` 으로 감싸
    던지고, 그것이 `httpx` 의 예외 변환을 그대로 통과해 호출부까지 올라갔다 —
    CI 에서 그 모양으로 터졌다 (2026-09-18, `test_llm_client_cannot_reach_the_real_gateway`).

    `OSError` 면 `httpcore` 가 `ConnectError` 로 옮기고 우리 클라이언트가 `LlmError` 로 받는다.
    즉 **실제 통신 실패와 같은 경로**를 타므로, 호출부의 실패 처리를 그대로 검증할 수 있다.
    """


def _is_outside(host: object) -> bool:
    return isinstance(host, str) and host not in LOCAL_HOSTS


@pytest.fixture(autouse=True, scope="session")
def _block_outbound_network() -> Iterator[None]:
    """테스트에서 바깥으로 나가는 연결을 막는다.

    `AI_LLM_API_KEY` 가 채워진 채로 앱을 띄우는 테스트가 있다 (프롬프트가 깨진 상태를
    재현하는 자리). 지금은 프롬프트를 못 읽어 호출 **전에** 멈추지만, 그 전제가 바뀌면
    실제 게이트웨이로 요청이 나간다. 키가 진짜면 크레딧까지 나간다.

    **이름 조회와 연결 둘 다 막는다.** 연결만 막으면 결과가 실행 환경에 좌우된다 — 이름
    조회가 안 되는 곳(개발 노트북)에서는 조회 단계에서 실패하고, 되는 곳(CI)에서는 연결
    단계까지 가서 다른 예외가 올라온다. 둘을 같은 예외로 막아 어디서 돌려도 같게 만든다.
    """
    real_connect = socket.socket.connect
    real_getaddrinfo = socket.getaddrinfo

    def guarded_connect(self: socket.socket, address: Any, *args: Any, **kwargs: Any) -> Any:
        # AF_UNIX 는 주소가 파일 경로다. 바깥으로 나가는 연결이 아니라 그대로 보낸다.
        if self.family != getattr(socket, "AF_UNIX", object()):
            host = address[0] if isinstance(address, tuple) else address
            if _is_outside(host):
                raise BlockedNetwork(
                    f"테스트가 바깥으로 연결하려 했다: {host}. "
                    "게이트웨이 호출은 httpx2.MockTransport 로 흉내 낸다 (tests/test_llm.py)."
                )
        return real_connect(self, address, *args, **kwargs)

    def guarded_getaddrinfo(host: Any, *args: Any, **kwargs: Any) -> Any:
        if _is_outside(host):
            raise BlockedNetwork(
                f"테스트가 바깥 주소를 조회하려 했다: {host}. "
                "게이트웨이 호출은 httpx2.MockTransport 로 흉내 낸다 (tests/test_llm.py)."
            )
        return real_getaddrinfo(host, *args, **kwargs)

    socket.socket.connect = guarded_connect  # type: ignore[method-assign]
    socket.getaddrinfo = guarded_getaddrinfo  # type: ignore[assignment]
    try:
        yield
    finally:
        socket.socket.connect = real_connect  # type: ignore[method-assign]
        socket.getaddrinfo = real_getaddrinfo  # type: ignore[assignment]


@pytest.fixture
def settings() -> Settings:
    # `_env_file=None` — 개발자 로컬 `.env` 에 테스트가 좌우되지 않게 끊는다.
    return Settings(_env_file=None, env="local", internal_api_key=TEST_INTERNAL_KEY)


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    app = create_app(settings)
    app.dependency_overrides[get_settings] = lambda: settings
    # raise_server_exceptions=False — 전역 예외 핸들러의 응답을 그대로 검증하려면 필요하다.
    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client
