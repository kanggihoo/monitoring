"""온보딩 한 줄 해석 — 자연어를 다섯 문항의 답으로 옮긴다. 명세 9절 ②.

**이 기능에서 LLM 이 없으면 사라지는 유일한 자리다.** 버튼 4개는 코드로 되지만 자연어 해석은
안 된다.

리포트 생성과 성격이 정반대라 클라이언트 설정을 따로 쓴다.

```
리포트   폐장 뒤 20분 예산의 배치     느긋하게 기다려도 된다
온보딩   사용자가 화면에서 기다린다   15초를 끄는 것은 이탈이다
```

그래서 타임아웃이 짧고, 재시도가 없고, 동시 실행 자리를 못 받으면 곧바로 버튼 경로로
돌려보낸다.
"""

from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path

from app.core.logging import get_logger
from app.services.headline import PromptError
from app.services.llm import LlmClient, LlmError
from app.services.profile import ANSWER_LENGTH, LEVEL_NAMES, PERSONAS, to_level
from app.services.report_blocks import render

logger = get_logger(__name__)

PROMPT_PATH = Path(__file__).resolve().parents[2] / "prompts" / "onboarding-interpret.md"

#: 입력 상한. 넘으면 부르지 않는다 — 긴 글을 성향으로 옮길 수도 없고, 넘길 이유도 없다.
MAX_INPUT = 200

#: 사용자가 기다리는 경로라 리포트(15초)보다 훨씬 짧게 끊는다.
REQUEST_TIMEOUT = 4.0

#: 동시 실행 자리를 기다리는 상한. 시즌 시작에 온보딩이 몰리면 줄이 서는데, 거기서
#: 오래 기다리느니 버튼 경로로 돌려보내는 편이 낫다.
GATE_WAIT = 1.5

#: 짧은 JSON 하나만 받으므로 리포트 예산(1024)을 그대로 쓸 이유가 없다.
MAX_TOKENS = 256

#: 답 하나의 허용 범위. 1~4 밖이면 그 응답을 통째로 버린다.
CHOICES = (1, 2, 3, 4)
CONFIDENCES = ("HIGH", "MID", "LOW")

#: 가운데 값. 모델이 "적힌 것이 없다" 고 판단한 자리다.
MIDDLE = (2, 3)

#: 이만큼이 가운데 값이면 해석하지 못한 것으로 본다. **모델의 자기평가를 그대로 믿지
#: 않는다** — 경계에서 흔들린다는 것이 실측으로 확인됐다 (5회 재실행 폭 21).
#:
#: **한 문항이라도 읽혔으면 그것은 선언이다.** 4축 시절 임계를 3 으로 뒀다가 "오르는 거
#: 따라 사는 편이에요" 처럼 한 축만 말한 문장이 `LOW` 로 내려가 버튼 경로로 되돌려졌다.
#: 아무것도 못 읽은 것만 거른다.
LOW_CONFIDENCE_NEUTRALS = ANSWER_LENGTH

#: 해석하지 못했을 때 돌려주는 값. 실패와 `LOW` 를 같은 모양으로 낸다 — 프론트는 어느 쪽이든
#: 버튼 경로로 되돌리므로, 나누면 처리만 늘고 얻는 것이 없다.
#:
#: **`33333` 이 아니다.** 답 다섯이 전부 3이면 합계가 15라 `적극투자형`(14~16)으로 채점된다 —
#: 구간 폭이 비대칭(5~7은 3점, 17~20은 4점)이라 "가운데 값 다섯"이 가운데 단계가 아니다.
#: 아무것도 읽지 못한 문장이 공격적인 선언으로 저장되면 결과지 ③⑦ 이 거짓이 된다.
#: 2와 3을 번갈아 두면 합계가 12가 되어 `위험중립형`(11~13) 한가운데에 앉는다.
UNREADABLE = ("23232", "LOW")

#: ```text 펜스만 본다. 문서에 설명용 예시 블록이 섞여도 흔들리지 않는다.
_FENCE = re.compile(r"^```text\n(.*?)^```", re.MULTILINE | re.DOTALL)
_JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)


@lru_cache
def load_prompt() -> tuple[str, str]:
    """`prompts/onboarding-interpret.md` 의 코드 블록 두 개를 (지침, 사용자 틀) 로 읽는다."""
    if not PROMPT_PATH.exists():
        raise PromptError(f"프롬프트 파일이 없습니다: {PROMPT_PATH.name}")
    blocks = [block.strip() for block in _FENCE.findall(PROMPT_PATH.read_text(encoding="utf-8"))]
    if len(blocks) != 2:
        raise PromptError(f"코드 블록이 2개여야 합니다 (현재 {len(blocks)}개): {PROMPT_PATH.name}")
    return blocks[0], blocks[1]


def parse(raw: str) -> tuple[str, str] | None:
    """모델 출력을 답안 다섯 자리와 등급으로 옮긴다. **값 하나라도 어긋나면 통째로 버린다.**

    자유 문장을 받지 않으므로, 입력이 지시를 덮어쓰려 해도 여기를 통과하지 못한다 —
    피해 범위가 지침이 아니라 구조로 막힌다.
    """
    found = _JSON_OBJECT.search(raw)
    if found is None:
        return None
    try:
        payload = json.loads(found.group())
    except (ValueError, TypeError):
        return None
    if not isinstance(payload, dict):
        return None

    answers = payload.get("answers")
    if not isinstance(answers, list) or len(answers) != ANSWER_LENGTH:
        return None
    if any(value not in CHOICES for value in answers):
        return None

    confidence = payload.get("confidence")
    if confidence not in CONFIDENCES:
        return None
    return "".join(str(value) for value in answers), confidence


def demote(answers: str, confidence: str) -> str:
    """가운데 값이 너무 많으면 등급을 내린다.

    해석하지 못한 것을 `HIGH` 로 올려 보내면 결과지의 대조가 무의미해진다. 모델이 스스로
    매긴 등급만 믿지 않는 이유가 이것이다.
    """
    neutrals = sum(1 for value in answers if int(value) in MIDDLE)
    return "LOW" if neutrals >= LOW_CONFIDENCE_NEUTRALS else confidence


async def interpret(client: LlmClient, text: str) -> tuple[str, str]:
    """한 줄을 다섯 문항의 답으로. 실패하면 `UNREADABLE` 이고 프론트가 버튼 경로로 되돌린다.

    예외를 밖으로 내보내지 않는다 — 온보딩이 이 자리에서 막히면 게임을 시작하지 못한다.
    **입력 원문은 로그에 남기지 않는다.**
    """
    stripped = text.strip()
    if not stripped or len(stripped) > MAX_INPUT or not client.configured:
        return UNREADABLE

    instruction, template = load_prompt()
    try:
        raw = await client.complete(
            instruction,
            render(template, {"text": stripped}),
            request_timeout=REQUEST_TIMEOUT,
            retry=False,
            wait=GATE_WAIT,
            max_completion_tokens=MAX_TOKENS,
        )
    except LlmError as exc:
        logger.warning("온보딩 해석 실패, 버튼 경로로 되돌린다: %s", exc)
        return UNREADABLE

    parsed = parse(raw)
    if parsed is None:
        logger.warning("온보딩 해석 응답이 형식을 벗어나 버린다 (길이=%d)", len(raw))
        return UNREADABLE

    answers, confidence = parsed
    return answers, demote(answers, confidence)


def to_reply(answers: str, confidence: str) -> dict[str, object]:
    """응답 본문. **채점도 같은 규칙으로 한다** — 버튼 경로로 들어오든 이 경로로 들어오든
    같은 표를 쓴다 (`services/profile`).

    `confidence` 가 `LOW` 면 답안은 실어 보내되 **단계를 비운다.** 화면은 그 답안을 버튼의
    기본 선택으로 깔면 되고, 저장하는 쪽은 단계가 비어 있는 것을 보고 "아직 선언이 아니다"
    로 읽는다. 읽지 못한 문장에 단계를 붙여 보내면 그것이 그대로 선언으로 저장된다.
    """
    total = sum(int(value) for value in answers)
    level = None if confidence == "LOW" else to_level(total)
    return {
        "selfAnswers": answers,
        "selfTotal": total,
        "selfLevel": level,
        "selfName": LEVEL_NAMES[level] if level else "",
        "selfPersona": PERSONAS[level] if level else "",
        "confidence": confidence,
    }
