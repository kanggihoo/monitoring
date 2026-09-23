"""결과지 ④⑦ 생성 — LLM 이 쓰는 두 자리. 명세 9절.

**계좌당 호출은 한 번이다.** 출력은 JSON 이고 `actual` 하나를 쓴다 — ⑦ 은 블록이 만든다.

처음에는 ⑦ 도 여기서 받았는데 실호출에서 모델이 세 번 다 틀렸다: 축을 섞고(`ENTRY` 선언에
`TIME` 문구), `MATCHED` 틀에 `OPPOSITE` 내용을 넣고, 선언 쪽을 통째로 빠뜨렸다. ⑦ 은 조합이
열일곱 가지뿐이라(축 넷, 방향 둘, 판정 둘, 전부 일치 하나) **블록이 정확히 덮는다.**
④ 는 두드러진 축 둘을 자연스럽게 잇는 일이라 조합이 폭발하고, 실호출에서 깨끗하게 나왔다.

생성이 실패하거나 검사에 걸리면 **조용히 블록 문장으로 되돌아간다.** 이 자리의 실패는
리포트를 못 만들 이유가 아니다.
"""

from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from app.core.logging import get_logger
from app.schemas.report import SeasonFeatures
from app.services.headline import PromptError
from app.services.llm import LlmClient, LlmError
from app.services.narrative import (
    ITEM_DIRECTION_WORDS,
    ITEM_HINTS,
    ITEM_MIDPOINT,
    ITEMS,
    NEGATION_WINDOW,
    NEGATIONS,
    item_direction,
)
from app.services.report_blocks import render
from app.services.text_checks import (
    ALLOWED_NUMERALS,
    CONTRAST_WORDS,
    COUNTING_WORDS,
    FORBIDDEN,
)
from app.services.text_checks import check as shared_check

logger = get_logger(__name__)

PROMPT_PATH = Path(__file__).resolve().parents[2] / "prompts" / "season-narrative.md"

#: ④ 는 두 문장, ⑦ 은 한 문장이다. 지침은 더 짧게 요구하고 여기서는 조금 넘겨도 통과시킨다.
MAX_ACTUAL = 130
MAX_GAP = 70

#: ```text 펜스만 본다. 문서에 설명용 예시 블록이 섞여도 흔들리지 않는다.
_FENCE = re.compile(r"^```text\n(.*?)^```", re.MULTILINE | re.DOTALL)
_JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)

#: 수치 표현까지 막는다. **한글 수사는 아라비아 숫자 검사를 그대로 통과한다** —
#: 게임 1거래일이 36초라 "나흘" 은 실투자 감각의 틀린 값이다.
SEASON_FORBIDDEN: tuple[str, ...] = FORBIDDEN + COUNTING_WORDS

#: 선언이 없는 계좌에만 더 막는 것. 없는 선언을 지어내는 문장을 만든다.
UNDECLARED_FORBIDDEN: tuple[str, ...] = SEASON_FORBIDDEN + CONTRAST_WORDS


@dataclass(frozen=True)
class Narration:
    """생성 결과. 실패한 자리는 `None` 이고 조립기가 폴백 블록을 쓴다."""

    actual: str | None = None
    gap: str | None = None


@lru_cache
def load_prompt() -> tuple[str, str]:
    """`prompts/season-narrative.md` 의 코드 블록 두 개를 (지침, 사용자 틀) 로 읽는다."""
    if not PROMPT_PATH.exists():
        raise PromptError(f"프롬프트 파일이 없습니다: {PROMPT_PATH.name}")
    blocks = [block.strip() for block in _FENCE.findall(PROMPT_PATH.read_text(encoding="utf-8"))]
    if len(blocks) != 2:
        raise PromptError(f"코드 블록이 2개여야 합니다 (현재 {len(blocks)}개): {PROMPT_PATH.name}")
    return blocks[0], blocks[1]


def build_message(features: SeasonFeatures, type_name: str) -> str:
    """사용자 메시지. **점수가 아니라 방향 문장을 넘긴다.**

    `TIME = 82` 를 주면 모델이 82를 "길다"로 읽을 여지가 있다. 헤드라인에서 한 번 겪은
    함정이라(`services/headline.py` 의 `mainMistake`) 방향을 문장으로 확정해 넘긴다.

    계좌 번호·종목명·체결 원본·수익률·순위는 넘기지 않는다 — 익명화가 지침이 아니라
    구조로 지켜진다.
    """
    _, template = load_prompt()
    points = {item.item: item.point for item in features.profile_items}
    highlights = [
        ITEM_HINTS[item][item_direction(points[item])] for item in _prominent_items(features)
    ]
    return render(
        template,
        {
            "typeName": type_name,
            "axisLabel": features.profile_name or "아직 판정되지 않음",
            "highlight1": highlights[0] if highlights else "두드러진 자리가 없었습니다",
            "highlight2": highlights[1] if len(highlights) > 1 else "그 밖에는 조용했습니다",
            "declared": "있음" if features.self_level is not None else "없음",
        },
    )


def _prominent_items(features: SeasonFeatures) -> list[str]:
    """중앙에서 먼 항목 둘. ⑥ 특징을 고르는 규칙과 같은 순서다 — **달라지면 ④ 와 ⑥ 이
    다른 자리를 짚어 한 화면에서 두 가지 이야기를 하게 된다.**

    흐린 항목은 뺀다. 근거가 얇은 자리를 모델에게 "두드러졌다" 고 넘기면 안 된다.
    """
    order = {item: index for index, item in enumerate(ITEMS)}
    usable = [
        item for item in features.profile_items if item.point is not None and not item.provisional
    ]
    ranked = sorted(usable, key=lambda item: (-abs(item.point - ITEM_MIDPOINT), order[item.item]))
    return [item.item for item in ranked[:2]]


def parse(raw: str) -> tuple[str | None, str | None]:
    """JSON 두 필드를 꺼낸다. **파싱이 깨지면 둘 다 `None`** 이고 조립기가 폴백으로 간다.

    마크다운 펜스로 감싸 오는 것은 흔하므로 객체 부분만 떼어 본다.
    """
    found = _JSON_OBJECT.search(raw)
    if found is None:
        return None, None
    try:
        payload = json.loads(found.group())
    except (ValueError, TypeError):
        return None, None
    if not isinstance(payload, dict):
        return None, None
    actual, gap = payload.get("actual"), payload.get("gap")
    return (actual if isinstance(actual, str) else None, gap if isinstance(gap, str) else None)


def _is_negated(text: str, end: int) -> bool:
    """그 어휘 바로 뒤에 부정 표현이 붙었나. "오래 머무르지 않았습니다" 를 살려 준다."""
    return any(mark in text[end : end + NEGATION_WINDOW] for mark in NEGATIONS)


def check_direction(text: str, axes: dict[str, str]) -> str:
    """판정과 반대 방향의 말이 섞였는지 본다. 걸리면 그 항목 이름을, 없으면 빈 문자열.

    **모델에 알려 준 항목만 본다.** 다섯을 다 보면 스쳐 지나가는 말에 걸려 멀쩡한 문장이
    버려진다 — 언급하지 않은 항목의 어휘는 애초에 판정과 맞출 근거가 없다.

    **`gap` 에는 걸지 않는다.** ⑦ 은 설계상 선언과 실제를 한 문장에 맞붙이므로 반대 방향
    말이 반드시 들어간다 ("오래 들고 갈 생각이라 하셨지만, 실제로는 빠르게 정리하셨습니다").
    """
    for axis, direction in axes.items():
        opposite = next(pole for pole in ITEM_DIRECTION_WORDS[axis] if pole != direction)
        for word in ITEM_DIRECTION_WORDS[axis][opposite]:
            start = text.find(word)
            while start != -1:
                if not _is_negated(text, start + len(word)):
                    return f"{axis}:{word}"
                start = text.find(word, start + 1)
    return ""


def check_pair(
    raw: str, *, declared: bool, axes: dict[str, str] | None = None
) -> tuple[Narration, str]:
    """두 필드를 따로 검사한다. 통과한 것만 담고, 걸린 자리는 사유를 모아 남긴다."""
    actual_raw, gap_raw = parse(raw)
    if actual_raw is None and gap_raw is None:
        return Narration(), "JSON 파싱 실패"

    forbidden = SEASON_FORBIDDEN if declared else UNDECLARED_FORBIDDEN
    reasons: list[str] = []

    actual, reason = shared_check(
        actual_raw or "",
        max_length=MAX_ACTUAL,
        forbidden=forbidden,
        allowed_numerals=ALLOWED_NUMERALS,
    )
    if actual is None:
        reasons.append(f"actual:{reason}")
    elif axes:
        wrong = check_direction(actual, axes)
        if wrong:
            actual = None
            reasons.append(f"actual:방향({wrong})")

    # `gap` 이 와도 쓰지 않는다. 모델이 덤으로 보내는 일이 있어 자리는 남겨 두되 검사하지
    # 않는다 — ⑦ 은 블록이 만든다.
    return Narration(actual=actual), " ".join(reasons)


async def generate(client: LlmClient, features: SeasonFeatures, type_name: str) -> Narration:
    """한 계좌의 ④⑦. 예외를 밖으로 내보내지 않는다."""
    if not client.configured:
        return Narration()

    instruction, _ = load_prompt()
    try:
        raw = await client.complete(instruction, build_message(features, type_name))
    except LlmError as exc:
        logger.warning(
            "시즌 서술 생성 실패, 블록 문장을 쓴다 (계정=%s): %s", features.account_id, exc
        )
        return Narration()

    points = {item.item: item.point for item in features.profile_items}
    told = {item: item_direction(points[item]) for item in _prominent_items(features)}
    narration, reasons = check_pair(raw, declared=features.self_level is not None, axes=told)
    if reasons:
        # 사유와 길이만 남긴다. 생성 문장 원문은 남기지 않는다.
        logger.warning(
            "시즌 서술 일부가 검사를 통과하지 못해 블록 문장을 쓴다 (계정=%s, 사유=%s, 길이=%d)",
            features.account_id,
            reasons,
            len(raw),
        )
    return narration


async def generate_many(
    client: LlmClient, batch: list[tuple[SeasonFeatures, str]]
) -> list[Narration]:
    """여러 계좌를 한 번에. 순서는 입력과 같고 실패한 자리는 빈 `Narration` 이다.

    동시 호출 상한은 `LlmClient` 안의 세마포어가 쥔다. 여기서 또 나누면 상한이 두 겹이 된다.
    """
    if not client.configured or not batch:
        return [Narration() for _ in batch]
    return list(
        await asyncio.gather(*(generate(client, features, name) for features, name in batch))
    )
