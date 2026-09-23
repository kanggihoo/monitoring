"""일일 헤드라인 LLM 생성.

블록 선택(`report_blocks`)이 만드는 헤드라인 위에 얹는 층이다. **대체가 아니라 덧칠이다** —
생성이 실패하거나 검사에 걸리면 조용히 블록 문장으로 되돌아간다. 폐장~발표 예산이 20분이라
그 자리에서 다시 걸어볼 사람이 없고, 헤드라인 하나 때문에 리포트 전체가 비면 안 된다.

모델에 넘기는 것은 **판정 결과뿐**이다. 종목명·계정·체결 원본을 보내지 않는다. 게임 종목이
실제와 다른 임의의 이름이라, 이름을 넘기지 않으면 익명화가 프롬프트 지침이 아니라 구조로
지켜진다.

프롬프트 문안은 `prompts/daily-headline.md` 가 갖는다. 여기서는 값 채우기와 검사만 한다.
"""

from __future__ import annotations

import asyncio
import re
from functools import lru_cache
from pathlib import Path

from app.core.logging import get_logger
from app.exceptions import AiServiceError
from app.schemas.report import DailyFeatures
from app.services.llm import LlmClient
from app.services.report_blocks import render
from app.services.text_checks import Verdict
from app.services.text_checks import check as shared_check

logger = get_logger(__name__)

#: 프롬프트가 사는 곳. 슬롯이 늘면 파일을 늘린다.
PROMPT_PATH = Path(__file__).resolve().parents[2] / "prompts" / "daily-headline.md"

#: 생성 문장의 길이 상한. 지침은 30자 안팎을 요구하고, 여기서는 조금 넘겨도 통과시킨다.
#: 헤드라인 블록 37개가 16~27자라, 45자를 넘으면 화면에서 한 줄을 벗어난다.
MAX_LENGTH = 45

#: ```text 펜스만 본다. 문서에 설명용 예시 블록이 섞여도 흔들리지 않는다.
_FENCE = re.compile(r"^```text\n(.*?)^```", re.MULTILINE | re.DOTALL)


VS_INDEX_LABEL = {
    "ABOVE": "시장보다 앞섰다",
    "SIMILAR": "시장과 비슷했다",
    "BELOW": "시장에 뒤처졌다",
}
#: `mainMistake` 는 이름 그대로 **아쉬웠던 쪽**이다. 라벨에 그 방향을 실어야 한다 —
#: "가장 두드러졌다" 로만 적었더니 모델이 잘한 일로 읽고 "성과를 이끌었습니다" 를 냈다 (실측).
MAIN_MISTAKE_LABEL = {
    "PEAK_BUY": "고점에서 사들인 자리",
    "BOTTOM_SELL": "저점에서 팔아버린 자리",
    "OVERTRADE": "지나치게 잦았던 매매",
    # 아쉬운 자리가 없었다는 뜻이다. "없었음" 을 명시하지 않으면 모델이 빈 라벨로 읽고
    # 다른 슬롯의 값을 끌어다 쓴다 — 위 세 줄과 같은 이유로 방향을 문장에 실어 둔다.
    "NORMAL": "눈에 띄게 아쉬운 자리는 없었음",
    "QUIET": "거의 없었던 거래",
}

_WIN_TAGS = frozenset({"BOTTOM_BUY", "PEAK_SELL"})
_LOSS_TAGS = frozenset({"PEAK_BUY", "BOTTOM_SELL"})


class PromptError(AiServiceError):
    """프롬프트 파일이 없거나 모양이 어긋난 경우. 설정 실수라 기동 중에 드러나야 한다."""

    message = "프롬프트를 읽지 못했습니다."


@lru_cache
def load_prompt() -> tuple[str, str]:
    """`prompts/daily-headline.md` 의 코드 블록 두 개를 (지침, 사용자 틀) 로 읽는다."""
    if not PROMPT_PATH.exists():
        raise PromptError(f"프롬프트 파일이 없습니다: {PROMPT_PATH.name}")
    blocks = [block.strip() for block in _FENCE.findall(PROMPT_PATH.read_text(encoding="utf-8"))]
    if len(blocks) != 2:
        raise PromptError(f"코드 블록이 2개여야 합니다 (현재 {len(blocks)}개): {PROMPT_PATH.name}")
    return blocks[0], blocks[1]


def build_message(features: DailyFeatures) -> str:
    """사용자 메시지를 만든다. 판정 결과만 담고 종목명·계정은 넣지 않는다."""
    _, template = load_prompt()
    win = sum(1 for scene in features.scenes if scene.tag in _WIN_TAGS)
    loss = sum(1 for scene in features.scenes if scene.tag in _LOSS_TAGS)
    return render(
        template,
        {
            "vsIndex": VS_INDEX_LABEL[features.vs_index],
            "mainMistake": MAIN_MISTAKE_LABEL[features.main_mistake],
            "executionCount": f"{features.execution_count}건",
            "winCount": f"{win}건",
            "lossCount": f"{loss}건",
        },
    )


def check(text: str) -> Verdict:
    """헤드라인 한 줄을 검사한다. 기준은 `services/text_checks.py` 가 갖는다."""
    return shared_check(text, max_length=MAX_LENGTH)


def validate(text: str) -> str | None:
    """사유가 필요 없을 때 쓰는 얇은 겉면."""
    return check(text).text


async def generate(client: LlmClient, features: DailyFeatures) -> str | None:
    """헤드라인 한 줄을 만든다. 실패하면 `None` — 호출부가 블록 문장을 쓴다.

    예외를 밖으로 내보내지 않는다. 이 자리의 실패는 리포트를 못 만들 이유가 아니다.

    **프롬프트 준비도 `try` 안에 있다.** 예전에는 `load_prompt()` 가 밖에 있어서 `PromptError`
    가 그대로 올라갔고, 라우터가 `generate_many` 를 감싸지 않으므로 그 일차 **전원**의 문구가
    빈 채로 리포트가 열렸다 — 컨테이너에 `prompts/` 가 없던 때가 그랬다. 한 명의 실패로 100
    명을 비우지 않는다는 규칙이 이 경로에도 적용돼야 한다.

    잡는 범위가 `AiServiceError` 인 이유는 여기서 날 수 있는 실패가 셋이기 때문이다 —
    모델 호출(`LlmError`), 프롬프트 파일(`PromptError`), 틀 치환(`BlockError`). 셋 다
    "문구 층이 어긋났다" 이고 답은 같다: 블록 문장으로 간다.
    """
    if not client.configured:
        return None

    try:
        instruction, _ = load_prompt()
        message = build_message(features)
        raw = await client.complete(instruction, message)
    except AiServiceError as exc:
        logger.warning(
            "헤드라인 생성 실패, 블록 문장을 쓴다 (계정=%s): %s", features.account_id, exc
        )
        return None

    headline, reason = check(raw)
    if headline is None:
        # 사유와 길이만 남긴다. 생성 문장 원문은 남기지 않는다.
        logger.warning(
            "헤드라인이 검사를 통과하지 못해 블록 문장을 쓴다 (계정=%s, 사유=%s, 길이=%d)",
            features.account_id,
            reason,
            len(raw),
        )
    return headline


async def generate_many(client: LlmClient, batch: list[DailyFeatures]) -> list[str | None]:
    """여러 계정을 한 번에 만든다. 순서는 입력과 같고, 실패한 자리는 `None` 이다.

    동시 호출 상한은 `LlmClient` 안의 세마포어가 쥔다. 여기서 또 나누면 상한이 두 겹이 된다.
    """
    if not client.configured or not batch:
        return [None] * len(batch)
    return list(await asyncio.gather(*(generate(client, features) for features in batch)))
