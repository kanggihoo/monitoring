"""GMS 게이트웨이 연결을 눈으로 확인한다. 서버도 DB 도 필요 없다.

    uv run python scripts/check_llm.py

무엇이 막혔는지 단계별로 알려준다 — 설정 · 프롬프트 · 호출 · 검사. 실패하면 어디를 봐야
하는지 함께 출력한다. 실행 절차는 docs/usage.md 참조.

**실제로 호출하므로 크레딧이 차감된다** (한 번에 한 건, 극소량).
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import Settings, get_settings
from app.core.logging import configure_logging
from app.exceptions import AiServiceError
from app.schemas.report import DailyFeatures, Scene
from app.services.headline import build_message, check, load_prompt
from app.services.llm import LlmClient, LlmError

#: 확인용 조건 벡터. 실제 목업을 쓰지 않는 이유는 이 스크립트가 보려는 것이 문장 품질이
#: 아니라 **연결**이기 때문이다. 값이 고정이어야 실패 원인이 입력 탓인지 아닌지 가려진다.
SAMPLE = DailyFeatures(
    accountId=4001,
    seasonDay=1,
    vsIndex="ABOVE",
    mainMistake="PEAK_BUY",
    executionCount=12,
    scenes=[
        Scene(gameMonth="2021-03", instrumentName="바나나", side="BUY", tag="PEAK_BUY"),
        Scene(gameMonth="2021-04", instrumentName="바나나", side="SELL", tag="PEAK_SELL"),
    ],
)


def fail(message: str, hint: str) -> int:
    print(f"\n실패: {message}")
    print(f"확인할 것: {hint}")
    return 1


async def run(settings: Settings) -> int:
    print("1. 설정")
    if not settings.llm_api_key:
        return fail(
            "AI_LLM_API_KEY 가 비어 있다.",
            "ai/.env 에 한 줄 채운다 — AI_LLM_API_KEY=<gms.ssafy.io 에서 받은 키>\n"
            "  .env 가 없으면 먼저 만든다: cp .env.example .env",
        )
    if not settings.llm_active:
        return fail(
            "키는 있는데 AI_LLM_ENABLED 가 false 다.",
            "ai/.env 의 AI_LLM_ENABLED 를 비우거나 true 로 둔다.",
        )
    # 키 값은 찍지 않는다. 길이만으로도 붙여넣기 사고는 대개 드러난다.
    print(f"   주소   {settings.llm_base_url}")
    print(f"   모델   {settings.llm_model}")
    print(f"   키     {len(settings.llm_api_key)}자 설정됨")
    print(f"   한도   {settings.llm_timeout_seconds}초 · 동시 {settings.llm_max_concurrency}")

    print("2. 프롬프트")
    try:
        instruction, _ = load_prompt()
        message = build_message(SAMPLE)
    except AiServiceError as exc:
        return fail(exc.message, "prompts/daily-headline.md 의 코드 블록이 2개인지 본다.")
    print(f"   지침   {len(instruction)}자")
    print("   보낼 판정")
    for line in message.splitlines():
        if line.strip():
            print(f"     {line}")

    print("3. 호출")
    client = LlmClient(settings)
    await client.start()
    try:
        raw = await client.complete(instruction, message)
    except LlmError as exc:
        return fail(
            exc.message,
            "키가 유효한지, 크레딧이 남아 있는지 본다 (https://gms.ssafy.io).\n"
            "  사내망·프록시 환경이면 게이트웨이 주소에 닿는지도 확인한다.\n"
            "  본문이 비어서 실패하면 AI_LLM_MAX_COMPLETION_TOKENS 를 올린다.",
        )
    finally:
        await client.aclose()
    print(f"   응답   {raw!r}")

    print("4. 검사")
    headline, reason = check(raw)
    if headline is None:
        return fail(
            f"생성된 문장이 검사를 통과하지 못했다 ({reason}).",
            "연결 자체는 정상이다. 문안을 손본다 — prompts/daily-headline.md.\n"
            "  검사 기준은 app/services/headline.py 의 check 에 있다.",
        )
    print(f"   헤드라인  {headline}")

    print("\n연결이 정상이다. scripts/preview_report.py --llm 로 리포트에 얹어 볼 수 있다.")
    return 0


def main() -> int:
    settings = get_settings()
    # 한글 출력을 위해 stdout 을 UTF-8 로 고정한다 (Windows 콘솔 기본 코드페이지 대응).
    configure_logging(settings.log_level)
    print(f"env={settings.env}")
    return asyncio.run(run(settings))


if __name__ == "__main__":
    sys.exit(main())
