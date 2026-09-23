"""생성 문장을 기계로 거르는 공통 검사.

**사람이 검수하지 않고 그대로 화면에 나간다.** 프롬프트 지침이 대부분 막지만 지침만 믿을 수
없어서, 나가기 직전에 한 번 더 거른다. 걸리면 조용히 블록 문장으로 되돌아간다.

헤드라인(`headline.py`)과 결과지 서술(`season_narrative.py`)이 같은 기준을 쓴다 — 두 곳에
복사해 두면 한쪽만 고쳐져 톤이 갈린다.
"""

from __future__ import annotations

import re
from typing import NamedTuple

#: 요구하는 종결. 블록 전부가 예외 없이 이걸로 끝난다 (`습니다.`·`입니다.`·`봅니다.`).
#: 마침표만 보면 부족하다 — 실측에서 "~평가된다" 같은 문어체 이탈이 그대로 통과할 뻔했다.
SENTENCE_ENDING = "니다."

_DIGIT = re.compile(r"\d")
#: 문장을 감싸고 온 따옴표를 벗기기 위한 패턴. 곧은 것과 둥근 것을 모두 본다.
#: 둥근 따옴표는 ruff 가 모호한 문자로 막지만(RUF001), 여기서는 그 문자 자체가 대상이다.
_WRAPPING_QUOTES = re.compile("^[\"'“‘](.*)[\"'”’]$", re.DOTALL)  # noqa: RUF001

#: 나오면 안 되는 말. 근거는 `prompts/report-blocks.md` 의 "절대 금지".
FORBIDDEN: tuple[str, ...] = (
    # 실제 고유명사 - 익명화가 무너진다
    "코스피",
    "코스닥",
    "나스닥",
    "다우",
    "S&P",
    "삼성",
    "카카오",
    "네이버",
    "테슬라",
    "애플",
    "비트코인",
    "코로나",
    "리먼",
    # 투자 권유·훈계
    "추천",
    "매수하세",
    "매도하세",
    "사세요",
    "파세요",
    "하셔야",
    "명심",
)

#: 기간을 한글 수사로 쓴 것. **아라비아 숫자 검사를 그대로 통과한다.**
#:
#: 게임 1거래일이 실시간 약 36초라 평균 보유가 수백 거래일인데, 모델은 실투자 감각으로
#: "나흘" 을 쓴다 — 틀린 값이 문장에 실린다. 수치는 ⑥ 이 템플릿으로 넣는다.
COUNTING_WORDS: tuple[str, ...] = (
    "하루",
    "삼일",
    "이틀",
    "사흘",
    "나흘",
    "닷새",
    "엿새",
    "이레",
    "여드레",
    "며칠",
    "한두",
    "두세",
    "서너",
)

#: 선언이 없는 계좌에 쓰면 **없는 선언을 지어낸 것**이 되는 표현.
#: 폴백으로는 못 막는다 — 이미 나간 문장이기 때문이다 (명세 8절).
CONTRAST_WORDS: tuple[str, ...] = (
    "생각과",
    "예상과",
    "달리",
    "반대로",
    "의외로",
    "하셨지만",
    "보셨지만",
)


class Verdict(NamedTuple):
    """검사 결과. `text` 가 `None` 이면 `reason` 에 어느 규칙에 걸렸는지 들어간다."""

    text: str | None
    reason: str = ""


#: 숫자 검사에서 빼 주는 말. **지금은 비어 있다.**
#:
#: 예전에는 `("3일간", "3일")` 이 들어 있었다. 시즌 길이는 게임의 고정된 사실이라 모델이
#: 지어낼 여지가 없다고 봤기 때문인데, **리포트가 매일 나오고 그때까지를 누적해서 보는
#: 설계로 바뀌면서 첫날과 둘째 날에 거짓이 됐다.** 프롬프트도 '지금까지'·'여기까지' 를
#: 쓰게 바뀌었으므로, 여기서 빼 두면 옛 문안이 되살아나도 검사에 걸린다.
#:
#: 자리는 남겨 둔다 — 다시 통과시킬 말이 생기면 여기에 적는다.
ALLOWED_NUMERALS: tuple[str, ...] = ()


def check(
    raw: str,
    *,
    max_length: int,
    forbidden: tuple[str, ...] = FORBIDDEN,
    allow_digits: bool = False,
    allowed_numerals: tuple[str, ...] = (),
) -> Verdict:
    """생성 문장을 검사한다. 통과하면 다듬은 문장을, 걸리면 사유를 함께 준다.

    사유를 남기는 이유는 **어느 규칙이 얼마나 걸리는지 봐야 문안을 고칠 수 있기 때문**이다.
    생성 문장 자체는 로그에 남기지 않는다.
    """
    candidate = " ".join(raw.split())
    wrapped = _WRAPPING_QUOTES.match(candidate)
    if wrapped:
        # 따옴표로 감싸 오는 것은 흔하고 문장 자체는 멀쩡하다. 거르지 않고 벗긴다.
        candidate = wrapped.group(1).strip()

    if not candidate:
        return Verdict(None, "빈 문장")
    if len(candidate) > max_length:
        return Verdict(None, f"길이 초과({len(candidate)}자)")
    if not candidate.endswith(SENTENCE_ENDING):
        return Verdict(None, f"'{SENTENCE_ENDING}' 종결 아님")
    scanned = candidate
    for phrase in allowed_numerals:
        scanned = scanned.replace(phrase, "")
    if not allow_digits and _DIGIT.search(scanned):
        return Verdict(None, "숫자 포함")
    hit = next((word for word in forbidden if word in candidate), None)
    if hit is not None:
        return Verdict(None, f"금지어({hit})")
    return Verdict(candidate)
