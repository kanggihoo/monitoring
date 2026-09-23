"""SSAFY GMS 게이트웨이 호출.

게이트웨이는 OpenAI 의 `/chat/completions` 를 그대로 앞에 세운 프록시라, 요청·응답 모양이
OpenAI 공식 문서와 같다. 그래서 `openai` SDK 를 의존성에 넣지 않고 `httpx2` 로 직접 부른다 —
주소를 바꿔 끼우기도 쉽고, 넣지 않아도 되는 의존성이 하나 줄어든다.

**여기서는 문장을 판단하지 않는다.** 무엇을 물어보고 무엇을 받아들일지는 `headline.py` 가
정하고, 이 모듈은 호출·재시도·실패 분류만 맡는다.

프롬프트와 응답 원문은 로그에 남기지 않는다 (`docs/conventions/code.md` §6).
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
from typing import Any

import httpx2
from fastapi import Request
from opentelemetry import trace

from app.core.config import Settings
from app.core.logging import get_logger
from app.exceptions import UpstreamError

logger = get_logger(__name__)

#: 한 번 더 걸어볼 만한 상태 코드. 429 는 동시 호출 상한을, 5xx 는 게이트웨이 쪽 일시 장애를 뜻한다.
RETRIABLE_STATUS = frozenset({408, 429, 500, 502, 503, 504})

#: 재시도 전 대기(초). 폐장~발표 예산이 20분이라 길게 물러설 수 없다.
RETRY_BACKOFF_SECONDS = 1.0


@dataclass(frozen=True)
class LlmUsage:
    """이 클라이언트가 지금까지 태운 양. **크레딧을 사후에 셀 수 있게 하는 유일한 자리다.**

    게이트웨이가 200 과 함께 돌려주는 `usage` 를 그대로 더한다. 호출부는 배치 앞뒤로
    `client.usage` 를 떠서 빼면 그 배치가 쓴 양이 된다 (`-` 연산자).

    `calls` 는 **200 으로 돌아온 호출**이다. 전송 실패·4xx 는 토큰이 나가지 않으므로 세지
    않고 `failures` 로만 남긴다. 빈 본문(200)은 추론 토큰을 이미 쓴 뒤라 양쪽에 다 잡힌다.
    """

    calls: int = 0
    failures: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    reasoning_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens

    def __sub__(self, other: LlmUsage) -> LlmUsage:
        return LlmUsage(
            calls=self.calls - other.calls,
            failures=self.failures - other.failures,
            prompt_tokens=self.prompt_tokens - other.prompt_tokens,
            completion_tokens=self.completion_tokens - other.completion_tokens,
            reasoning_tokens=self.reasoning_tokens - other.reasoning_tokens,
        )

    def line(self) -> str:
        """로그 한 줄. 토큰은 자릿수가 커서 천 단위로 끊는다."""
        return (
            f"호출 {self.calls}건(실패 {self.failures}) · "
            f"토큰 {self.total_tokens:,} (입력 {self.prompt_tokens:,}"
            f" · 출력 {self.completion_tokens:,}"
            f", 그중 추론 {self.reasoning_tokens:,})"
        )


#: httpx2 는 OTel 자동 계측 대상이 아니라 게이트웨이 호출이 트레이스에서 비어 보인다.
#: 여기만 손으로 잰다. SDK 없이(uv run) 돌면 no-op 이다.
_tracer = trace.get_tracer(__name__)


def _usage_of(data: dict[str, Any]) -> tuple[int, int, int]:
    """응답에서 토큰 수를 꺼낸다. **없으면 0 이다 — 여기서 실패하지 않는다.**

    계측이 문장 생성을 막으면 안 된다. 게이트웨이가 `usage` 를 빼고 주는 날이 와도
    리포트는 그대로 나가야 하고, 그때는 토큰 줄이 0 으로 찍혀 오설정이 눈에 띈다.
    """
    usage = data.get("usage")
    if not isinstance(usage, dict):
        return 0, 0, 0
    details = usage.get("completion_tokens_details")
    reasoning = details.get("reasoning_tokens", 0) if isinstance(details, dict) else 0
    return (
        int(usage.get("prompt_tokens") or 0),
        int(usage.get("completion_tokens") or 0),
        int(reasoning or 0),
    )


class LlmError(UpstreamError):
    """모델 호출 실패. 호출부는 이걸 잡아서 블록 문장으로 되돌아간다."""

    message = "문장 생성에 실패했습니다."


class LlmClient:
    """게이트웨이 호출 하나를 감싼다. 앱 상태(`app.state.llm`)에 하나만 둔다.

    `AsyncClient` 를 요청마다 만들지 않는다 — 커넥션 풀이 매번 버려진다
    (`docs/conventions/code.md` §7).
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client: httpx2.AsyncClient | None = None
        # 계정 수만큼 호출이 몰리므로 동시 호출을 여기서 막는다.
        self._gate = asyncio.Semaphore(settings.llm_max_concurrency)
        self._usage = LlmUsage()

    @property
    def configured(self) -> bool:
        return self._settings.llm_active and bool(self._settings.llm_api_key)

    @property
    def usage(self) -> LlmUsage:
        """기동 뒤 누적. 얼어 있는 사본이라 들고 있어도 뒤에서 변하지 않는다."""
        return self._usage

    async def start(self) -> None:
        """기동 시 클라이언트를 연다. 꺼져 있으면 아무것도 만들지 않는다."""
        settings = self._settings
        if not self.configured:
            logger.info("헤드라인 LLM 비활성 — 블록 문장만 쓴다 (AI_LLM_API_KEY 를 채우면 켜진다)")
            return
        self._client = httpx2.AsyncClient(
            base_url=settings.llm_base_url,
            timeout=httpx2.Timeout(settings.llm_timeout_seconds),
            headers={
                "Authorization": f"Bearer {settings.llm_api_key}",
                "Content-Type": "application/json",
            },
        )
        # 키 값은 남기지 않는다. 어느 주소의 어떤 모델인지만 남겨도 오설정은 눈에 띈다.
        logger.info(
            "헤드라인 LLM 준비 (%s, model=%s, 동시 %d)",
            settings.llm_base_url,
            settings.llm_model,
            settings.llm_max_concurrency,
        )

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def complete(
        self,
        instruction: str,
        message: str,
        *,
        request_timeout: float | None = None,
        retry: bool = True,
        wait: float | None = None,
        max_completion_tokens: int | None = None,
    ) -> str:
        """한 번 물어보고 본문 문자열을 돌려준다.

        `developer` 역할은 GMS 예제와 OpenAI 현행 표기를 따른 것이다 (예전 `system` 자리).
        `max_tokens` 가 아니라 `max_completion_tokens` 를 쓴다 — gpt-5 계열은 옛 이름을 거절한다.
        `temperature` 는 보내지 않는다. 같은 이유로 1 이외의 값이 거절된다.

        **부르는 자리마다 기다릴 수 있는 시간이 다르다.** 리포트는 폐장 뒤 20분 예산의 배치라
        느긋하게 기다려도 되지만, 온보딩 한 줄 해석은 사용자가 화면에서 기다린다 — 거기서
        15초를 끄는 것은 이탈이다. 그래서 타임아웃·재시도·대기 상한을 인자로 연다.

        `wait` 는 **동시 실행 자리를 기다리는 상한**이다. 시즌 시작에 온보딩이 몰리면 세마포어
        앞에 줄이 서는데, 거기서 오래 기다리느니 버튼 경로로 돌려보내는 편이 낫다.
        """
        client = self._client
        if client is None:
            raise LlmError("LLM 클라이언트가 준비되지 않았습니다.")

        settings = self._settings
        payload: dict[str, Any] = {
            "model": settings.llm_model,
            "messages": [
                {"role": "developer", "content": instruction},
                {"role": "user", "content": message},
            ],
            "max_completion_tokens": settings.llm_max_completion_tokens,
            "reasoning_effort": settings.llm_reasoning_effort,
        }

        if max_completion_tokens is not None:
            payload["max_completion_tokens"] = max_completion_tokens

        try:
            await asyncio.wait_for(self._gate.acquire(), timeout=wait)
        except TimeoutError as exc:
            raise LlmError(f"동시 호출 자리를 {wait}초 안에 받지 못했습니다.") from exc
        try:
            # 재시도까지 한 span 이다. 예외는 span 에 기록되고 상태가 ERROR 가 된다.
            with _tracer.start_as_current_span("llm.call", kind=trace.SpanKind.CLIENT) as span:
                span.set_attribute("gen_ai.request.model", settings.llm_model)
                data = await self._post_with_retry(
                    client, payload, request_timeout=request_timeout, retry=retry
                )
                prompt, completion, _ = _usage_of(data)
                span.set_attribute("gen_ai.usage.input_tokens", prompt)
                span.set_attribute("gen_ai.usage.output_tokens", completion)
        except LlmError:
            self._count(failed=True)
            raise
        finally:
            self._gate.release()

        # 200 이 왔으면 토큰은 이미 나갔다. 본문 검사보다 **앞에서** 센다 — 빈 본문도
        # 추론 토큰을 쓴 뒤라, 여기서 세지 않으면 가장 비싼 실패가 장부에서 빠진다.
        self._count(data=data)
        try:
            return _extract_content(data)
        except LlmError:
            # 200 인데 본문이 비었다. 호출은 이미 위에서 셌으니 실패만 더한다.
            self._count(failed=True)
            raise

    def _count(self, *, data: dict[str, Any] | None = None, failed: bool = False) -> None:
        """장부를 더한다. `data` 가 있으면 200 한 건, `failed` 면 실패 한 건이다."""
        prompt, completion, reasoning = _usage_of(data) if data is not None else (0, 0, 0)
        self._usage = replace(
            self._usage,
            calls=self._usage.calls + (1 if data is not None else 0),
            failures=self._usage.failures + (1 if failed else 0),
            prompt_tokens=self._usage.prompt_tokens + prompt,
            completion_tokens=self._usage.completion_tokens + completion,
            reasoning_tokens=self._usage.reasoning_tokens + reasoning,
        )

    async def _post_with_retry(
        self,
        client: httpx2.AsyncClient,
        payload: dict[str, Any],
        *,
        request_timeout: float | None = None,
        retry: bool = True,
    ) -> dict[str, Any]:
        """한 번만 더 시도한다.

        폐장 직후 한 번에 몰아 부르는 구조라 429 가 나기 쉽다. 다만 무한히 물러서면 발표
        시각을 넘기므로, 한 번 쉬고 한 번 더 걸어본 뒤에는 실패로 넘긴다 — 호출부에 블록
        문장이라는 대안이 있다.
        """
        last: Exception | None = None
        attempts = (1, 2) if retry else (1,)
        limit = (
            httpx2.Timeout(request_timeout)
            if request_timeout is not None
            else httpx2.USE_CLIENT_DEFAULT
        )
        for attempt in attempts:
            try:
                response = await client.post("/chat/completions", json=payload, timeout=limit)
            except httpx2.HTTPError as exc:
                last = LlmError(f"게이트웨이에 닿지 못했습니다: {type(exc).__name__}")
            else:
                if response.status_code == 200:
                    return response.json()
                # 본문에 키가 실려 올 이유는 없지만, 게이트웨이 오류 본문을 그대로 로그에
                # 흘리지 않는다. 상태 코드만으로 원인 분류는 충분하다.
                last = LlmError(f"게이트웨이가 {response.status_code} 를 돌려줬습니다.")
                if response.status_code not in RETRIABLE_STATUS:
                    break

            if attempt == 1 and retry:
                logger.warning("LLM 호출 실패, 한 번 더 시도한다: %s", last)
                await asyncio.sleep(RETRY_BACKOFF_SECONDS)

        raise last if last is not None else LlmError()


def _extract_content(data: dict[str, Any]) -> str:
    """응답에서 본문만 꺼낸다.

    빈 본문을 실패로 본다. gpt-5 계열은 추론 토큰이 `max_completion_tokens` 를 같이 쓰기
    때문에, 한도가 모자라면 **200 응답에 본문만 비어서** 돌아온다. 그대로 통과시키면
    헤드라인 자리가 빈 채로 화면까지 간다.
    """
    try:
        content = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise LlmError("응답 모양이 예상과 다릅니다.") from exc

    text = (content or "").strip()
    if not text:
        raise LlmError("본문이 빈 응답입니다 (max_completion_tokens 부족일 수 있습니다).")
    return text


def get_llm_client(request: Request) -> LlmClient:
    """라우터용 의존성. 앱 상태에 붙은 인스턴스를 그대로 넘긴다."""
    client: LlmClient | None = getattr(request.app.state, "llm", None)
    if client is None:
        # lifespan 이 돌지 않은 경우다 (TestClient 를 컨텍스트 매니저 없이 쓰면 생긴다).
        raise LlmError("LLM 계층이 초기화되지 않았습니다.")
    return client
