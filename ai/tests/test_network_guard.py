"""테스트가 바깥으로 나가지 못하는지 본다 (conftest 의 `_block_outbound_network`).

그물 자체를 시험하는 자리다. 이 파일이 깨지면 나머지 테스트가 게이트웨이로 실제 요청을
보낼 수 있는 상태라는 뜻이다 — 키가 진짜면 크레딧이 나간다.
"""

from __future__ import annotations

import socket

import httpx2
import pytest

from app.core.config import Settings
from app.services.llm import LlmClient, LlmError
from tests.conftest import BlockedNetwork


def test_outbound_connection_is_blocked() -> None:
    """네트워크차단_외부주소_이름조회에서막는다

    **이름 조회부터 막는다.** 연결만 막으면 이름 조회가 되는 환경(CI)과 안 되는 환경
    (개발 노트북)에서 서로 다른 예외가 올라와, 같은 코드가 한쪽에서만 통과한다.
    """
    with pytest.raises(BlockedNetwork, match="바깥 주소를 조회"):
        socket.create_connection(("gms.ssafy.io", 443), timeout=1)


def test_outbound_connection_to_a_literal_ip_is_blocked() -> None:
    """네트워크차단_아이피직접_연결에서막는다

    이름 조회를 건너뛰고 주소를 바로 찍는 경로도 있다 (`create_connection` 은 IP 를 줘도
    조회를 거치므로, 조회를 건너뛰는 `socket.connect` 로 그 자리를 직접 본다).
    """
    with socket.socket() as sock:
        sock.settimeout(1)
        with pytest.raises(BlockedNetwork, match="바깥으로 연결"):
            sock.connect(("52.78.138.88", 443))


def test_loopback_is_still_allowed() -> None:
    """네트워크차단_루프백_그대로둔다

    TestClient 는 앱을 그 자리에서 부르지만, 다른 도구가 로컬 포트를 쓸 여지를 남긴다.
    막힌 것이 '바깥' 인지 '전부' 인지 여기서 갈린다.
    """
    with socket.socket() as sock:
        sock.settimeout(1)
        # 아무도 듣고 있지 않은 포트라 거절당하는 것이 정상이다. 그물에 걸린 것이면 안 된다.
        with pytest.raises(OSError) as caught:
            sock.connect(("127.0.0.1", 9))
    assert not isinstance(caught.value, BlockedNetwork)


async def test_llm_client_cannot_reach_the_real_gateway() -> None:
    """네트워크차단_키가채워진클라이언트_게이트웨이에닿지못한다

    프롬프트가 깨진 상태를 재현하는 테스트가 키를 채운 채 앱을 띄운다. 그 전제가 바뀌어도
    실제 호출로 이어지지 않는다는 것을 여기서 못 박는다.
    """
    settings = Settings(
        _env_file=None,
        env="local",
        llm_api_key="dummy-key",
        llm_timeout_seconds=1.0,
    )
    llm = LlmClient(settings)
    await llm.start()
    try:
        with pytest.raises(LlmError):
            await llm.complete("지침", "판정", retry=False)
    finally:
        await llm.aclose()
    # 전송 실패라 토큰은 나가지 않았다.
    assert (llm.usage.calls, llm.usage.failures) == (0, 1)


def test_mock_transport_is_not_blocked() -> None:
    """네트워크차단_MockTransport_그대로통과한다"""
    client = httpx2.Client(
        base_url="https://gms.ssafy.io",
        transport=httpx2.MockTransport(lambda request: httpx2.Response(200, json={"ok": True})),
    )
    assert client.get("/v1/models").status_code == 200
