"""결과지 ④⑦ 생성. 명세 9절."""

from __future__ import annotations

import pytest

from app.schemas.report import ProfileItem, SeasonFeatures
from app.services.narrative import ITEM_HINTS
from app.services.season_narrative import (
    MAX_ACTUAL,
    Narration,
    build_message,
    check_pair,
    load_prompt,
    parse,
)

OK_ACTUAL = "지금까지의 거래를 보면 크게 거셨습니다. 한 자리에 오래 머무르지 않았습니다."
OK_GAP = "오래 들고 갈 생각이라 하셨지만, 실제로는 빠르게 정리하셨습니다."


def payload(actual: str = OK_ACTUAL, gap: str | None = OK_GAP) -> str:
    body = f'{{"actual": "{actual}"'
    body += f', "gap": "{gap}"}}' if gap is not None else "}"
    return body


def features(**extra) -> SeasonFeatures:
    return SeasonFeatures(
        accountId=4021,
        seasonId="S1",
        # ④ 는 항목 다섯을 본다. 흐린 항목은 후보에서 빠지므로 provisional 을 꺼 둔다.
        profileLevel=4,
        profileName="적극투자형",
        profileItems=[
            ProfileItem(item="INVESTED", label="투입 비중", point=4),
            ProfileItem(item="DRAWDOWN", label="손실 감내", point=4),
            ProfileItem(item="ENTRY", label="진입 타이밍", point=3),
            ProfileItem(item="DIP", label="급락 대응", point=2),
            ProfileItem(item="STOP", label="손실 정리", point=1),
        ],
        **extra,
    )


def test_prompt_has_two_text_blocks() -> None:
    instruction, template = load_prompt()
    assert "JSON" in instruction
    assert "{typeName}" in template


def test_message_carries_direction_sentences_not_scores() -> None:
    """점수를 주면 모델이 82를 '길다'로 읽는다 — 방향을 문장으로 확정해 넘긴다."""
    message = build_message(features(selfAnswers="11111"), "변동성을 즐기는 트레이더")
    # 항목 방향이 문장으로 들어간다. 점수(1~4)나 원지표는 넘어가지 않는다.
    assert any(
        hint in message for directions in ITEM_HINTS.values() for hint in directions.values()
    )
    assert "89" not in message and "82" not in message
    # 계좌 번호·수익률·순위는 넘기지 않는다.
    assert "4021" not in message


def test_message_marks_a_missing_declaration() -> None:
    assert "선언: 없음" in build_message(features(), "돌파 승부사")


@pytest.mark.parametrize(
    "raw",
    ["그냥 문장", "", "{망가진 json", "[1, 2]"],
)
def test_broken_json_drops_both_fields(raw: str) -> None:
    narration, reason = check_pair(raw, declared=True)
    assert narration == Narration()
    assert reason


def test_fenced_json_is_accepted() -> None:
    assert parse("```json\n" + payload() + "\n```")[0] == OK_ACTUAL


def test_gap_is_ignored_even_when_the_model_sends_one() -> None:
    """⑦ 은 블록이 만든다 — 모델이 덤으로 보내도 쓰지 않고 검사하지도 않는다."""
    narration, reason = check_pair(payload(gap="사흘 만에 정리하셨습니다."), declared=True)
    assert narration.actual == OK_ACTUAL
    assert narration.gap is None
    assert reason == ""


def test_gap_is_not_required_without_a_declaration() -> None:
    narration, reason = check_pair(payload(gap=None), declared=False)
    assert narration.actual == OK_ACTUAL
    assert narration.gap is None
    assert reason == ""


def test_contrast_words_are_blocked_without_a_declaration() -> None:
    """선언한 적이 없는 사람에게 쓰면 없는 선언을 지어낸 문장이 된다."""
    text = "생각과 달리 크게 거셨습니다. 오래 머무르지 않았습니다."
    assert check_pair(payload(actual=text), declared=False)[0].actual is None
    # 선언이 있으면 같은 표현이 통과한다.
    assert check_pair(payload(actual=text), declared=True)[0].actual == text


def test_counting_words_are_blocked() -> None:
    """한글 수사는 아라비아 숫자 검사를 그대로 통과한다 — 따로 막는다."""
    assert (
        check_pair(payload(actual="나흘 만에 정리하셨습니다. 크게 거셨습니다."), declared=False)[
            0
        ].actual
        is None
    )


def test_the_season_length_in_days_is_rejected() -> None:
    """`3일` 은 이제 걸린다.

    리포트가 매일 나오고 그때까지를 누적해서 보므로 첫날과 둘째 날에 거짓이다. 예전에는
    게임의 고정된 사실이라며 숫자 검사에서 빼 줬다 (`ALLOWED_NUMERALS`).
    """
    stale = "3일간의 거래를 보면 크게 거셨습니다. 한 자리에 오래 머무르지 않았습니다."
    assert check_pair(payload(actual=stale), declared=True)[0].actual is None


def test_length_cap() -> None:
    long = "가" * (MAX_ACTUAL + 1) + "습니다."
    assert check_pair(payload(actual=long), declared=False)[0].actual is None


#: 모델에 알려 준 항목 둘. 손실은 빨리 끊는 쪽(L), 투입은 많이 넣는 쪽(H).
DIRECTIONS = {"STOP": "L", "INVESTED": "H"}


@pytest.mark.parametrize(
    ("text", "caught"),
    [
        # 부정이 붙으면 반대 뜻이다. 이걸 못 보면 멀쩡한 문장이 버려진다.
        ("돌아올 때까지 기다리지 않았습니다.", False),
        ("오래 안고 가지 않고 정리하셨습니다.", False),
        ("빠르게 끊고 많이 넣으셨습니다.", False),
        # 판정이 '빨리 끊는다' 인데 오래 안고 갔다고 말한다.
        ("돌아올 때까지 기다리셨습니다.", True),
        ("손실을 오래 안고 가셨습니다.", True),
        # 판정이 '많이 넣었다' 인데 현금을 남겼다고 말한다.
        ("현금을 넉넉히 남기셨습니다.", True),
    ],
)
def test_direction_check_reads_negation(text: str, caught: bool) -> None:
    from app.services.season_narrative import check_direction

    assert bool(check_direction(text, DIRECTIONS)) is caught


def test_direction_check_ignores_items_we_did_not_mention() -> None:
    """언급하지 않은 항목의 어휘는 판정과 맞출 근거가 없다 — 보면 오탐만 는다."""
    from app.services.season_narrative import check_direction

    assert check_direction("빠진 자리를 노려 담으셨습니다.", {"STOP": "L"}) == ""


def test_direction_violation_drops_the_actual() -> None:
    narration, reason = check_pair(
        payload(actual="돌아올 때까지 기다리셨습니다. 많이 넣으셨습니다."),
        declared=True,
        axes=DIRECTIONS,
    )
    assert narration.actual is None
    assert "방향" in reason


def test_a_clean_actual_passes_with_no_reason() -> None:
    narration, reason = check_pair(payload(), declared=True, axes=DIRECTIONS)
    assert narration.actual == OK_ACTUAL
    assert reason == ""
