"""GMS 게이트웨이 클라이언트. 네트워크 없이 `MockTransport` 로 응답을 흉내 낸다."""

from __future__ import annotations

import httpx2
import pytest

from app.core.config import Settings
from app.services import llm as llm_module
from app.services.llm import LlmClient, LlmError


def make_settings(**overrides: object) -> Settings:
    base: dict[str, object] = {
        "_env_file": None,
        "env": "local",
        "llm_api_key": "test-gms-key",
        # 재시도 대기 때문에 테스트가 느려지지 않게 한도를 짧게 잡는다.
        "llm_timeout_seconds": 1.0,
    }
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


def completion(text: str) -> dict[str, object]:
    return {"choices": [{"message": {"role": "assistant", "content": text}}]}


def client_with(handler) -> LlmClient:
    """`start()` 가 만드는 것과 같은 모양의 클라이언트에 가짜 전송을 끼운다."""
    settings = make_settings()
    llm = LlmClient(settings)
    llm._client = httpx2.AsyncClient(
        base_url=settings.llm_base_url,
        transport=httpx2.MockTransport(handler),
    )
    return llm


async def test_complete_returns_message_content() -> None:
    """모델호출_정상응답_본문문자열을반환한다"""
    llm = client_with(lambda request: httpx2.Response(200, json=completion(" 좋은 하루였습니다. ")))

    assert await llm.complete("지침", "판정") == "좋은 하루였습니다."


async def test_complete_sends_gpt5_parameters() -> None:
    """모델호출_요청본문_gpt5용파라미터를싣는다"""
    seen: dict[str, object] = {}

    def handler(request: httpx2.Request) -> httpx2.Response:
        import json

        seen.update(json.loads(request.content))
        return httpx2.Response(200, json=completion("한 문장입니다."))

    await client_with(handler).complete("지침", "판정")

    # gpt-5 계열은 max_tokens 를 거절하고, temperature 도 1 이외 값을 거절한다.
    assert seen["max_completion_tokens"] == 1024
    assert "max_tokens" not in seen
    assert "temperature" not in seen
    assert seen["reasoning_effort"] == "minimal"
    assert [m["role"] for m in seen["messages"]] == ["developer", "user"]  # type: ignore[index,union-attr]


async def test_complete_sends_bearer_authorization() -> None:
    """모델호출_요청헤더_Bearer키를싣는다"""
    seen: dict[str, str] = {}

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen["auth"] = request.headers["Authorization"]
        return httpx2.Response(200, json=completion("한 문장입니다."))

    settings = make_settings()
    llm = LlmClient(settings)
    await llm.start()
    llm._client = httpx2.AsyncClient(
        base_url=settings.llm_base_url,
        headers=dict(llm._client.headers),
        transport=httpx2.MockTransport(handler),
    )
    await llm.complete("지침", "판정")

    assert seen["auth"] == "Bearer test-gms-key"


async def test_complete_with_empty_content_raises() -> None:
    """모델호출_본문이빈응답_실패로분류한다"""
    # 추론 토큰이 max_completion_tokens 를 다 쓰면 200 인데 본문만 비어서 온다.
    llm = client_with(lambda request: httpx2.Response(200, json=completion("")))

    with pytest.raises(LlmError):
        await llm.complete("지침", "판정")


async def test_complete_retries_once_on_rate_limit(monkeypatch) -> None:
    """모델호출_429응답_한번더시도한다"""
    # 재시도 대기를 없앤다. 기다리는 시간이 아니라 한 번 더 거는지가 검증 대상이다.
    monkeypatch.setattr(llm_module, "RETRY_BACKOFF_SECONDS", 0)
    calls: list[int] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        calls.append(1)
        if len(calls) == 1:
            return httpx2.Response(429)
        return httpx2.Response(200, json=completion("두 번째에 성공했습니다."))

    assert await client_with(handler).complete("지침", "판정") == "두 번째에 성공했습니다."
    assert len(calls) == 2


async def test_complete_does_not_retry_on_unauthorized() -> None:
    """모델호출_401응답_재시도없이실패한다"""
    calls: list[int] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        calls.append(1)
        return httpx2.Response(401)

    with pytest.raises(LlmError):
        await client_with(handler).complete("지침", "판정")
    # 키가 틀린 것은 다시 걸어도 같다. 폐장 시각에 헛되이 기다리지 않는다.
    assert len(calls) == 1


async def test_complete_on_transport_error_raises(monkeypatch) -> None:
    """모델호출_게이트웨이에닿지못함_실패로분류한다"""
    monkeypatch.setattr(llm_module, "RETRY_BACKOFF_SECONDS", 0)

    def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ConnectError("no route")

    with pytest.raises(LlmError):
        await client_with(handler).complete("지침", "판정")


async def test_complete_without_start_raises() -> None:
    """모델호출_클라이언트미준비_실패로분류한다"""
    with pytest.raises(LlmError):
        await LlmClient(make_settings()).complete("지침", "판정")


async def test_start_without_key_leaves_client_unconfigured() -> None:
    """기동_키미설정_비활성상태로뜬다"""
    llm = LlmClient(make_settings(llm_api_key=""))
    await llm.start()

    assert llm.configured is False
    await llm.aclose()


async def test_start_with_explicit_disable_leaves_client_unconfigured() -> None:
    """기동_키는있고스위치가false_비활성상태로뜬다"""
    llm = LlmClient(make_settings(llm_enabled=False))
    await llm.start()

    assert llm.configured is False
    await llm.aclose()


def usage_body(text: str, *, prompt: int, completion_tokens: int, reasoning: int) -> dict:
    body = completion(text)
    body["usage"] = {
        "prompt_tokens": prompt,
        "completion_tokens": completion_tokens,
        "completion_tokens_details": {"reasoning_tokens": reasoning},
    }
    return body


async def test_usage_accumulates_tokens() -> None:
    """사용량_정상응답_호출수와토큰을누적한다"""
    body = usage_body("한 문장입니다.", prompt=120, completion_tokens=40, reasoning=16)
    llm = client_with(lambda request: httpx2.Response(200, json=body))

    await llm.complete("지침", "판정")
    await llm.complete("지침", "판정")

    usage = llm.usage
    assert (usage.calls, usage.failures) == (2, 0)
    assert (usage.prompt_tokens, usage.completion_tokens) == (240, 80)
    assert usage.reasoning_tokens == 32
    assert usage.total_tokens == 320


async def test_usage_counts_empty_body_as_call_and_failure() -> None:
    """사용량_빈본문_호출과실패에모두잡힌다

    200 이면 추론 토큰은 이미 나갔다. 여기서 호출을 안 세면 **가장 비싼 실패**가 장부에서
    빠진다 — 본문이 비는 원인이 대개 `max_completion_tokens` 를 추론이 다 쓴 경우다.
    """
    body = usage_body("", prompt=100, completion_tokens=1024, reasoning=1024)
    llm = client_with(lambda request: httpx2.Response(200, json=body))

    with pytest.raises(LlmError):
        await llm.complete("지침", "판정")

    assert (llm.usage.calls, llm.usage.failures) == (1, 1)
    assert llm.usage.completion_tokens == 1024


async def test_usage_does_not_count_tokens_for_rejected_call() -> None:
    """사용량_4xx응답_토큰은세지않고실패만더한다"""
    llm = client_with(lambda request: httpx2.Response(401))

    with pytest.raises(LlmError):
        await llm.complete("지침", "판정")

    assert (llm.usage.calls, llm.usage.failures) == (0, 1)
    assert llm.usage.total_tokens == 0


async def test_usage_survives_a_response_without_usage_field() -> None:
    """사용량_usage없는응답_0으로세고문장은그대로낸다"""
    llm = client_with(lambda request: httpx2.Response(200, json=completion("한 문장입니다.")))

    assert await llm.complete("지침", "판정") == "한 문장입니다."
    assert (llm.usage.calls, llm.usage.total_tokens) == (1, 0)


async def test_usage_difference_gives_one_batch() -> None:
    """사용량_배치앞뒤차이_그배치가쓴양만남는다"""
    body = usage_body("한 문장입니다.", prompt=10, completion_tokens=5, reasoning=2)
    llm = client_with(lambda request: httpx2.Response(200, json=body))

    await llm.complete("지침", "판정")
    before = llm.usage
    await llm.complete("지침", "판정")

    batch = llm.usage - before
    assert (batch.calls, batch.prompt_tokens, batch.completion_tokens) == (1, 10, 5)
    # 앞서 뜬 사본은 뒤의 호출에 흔들리지 않는다.
    assert before.calls == 1
