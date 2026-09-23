"""온보딩 한 줄 해석. 상황형 5문항의 답으로 옮긴다 (기준 문서 3절)."""

from __future__ import annotations

import pytest

from app.services.onboarding import (
    MAX_INPUT,
    UNREADABLE,
    demote,
    interpret,
    load_prompt,
    parse,
    to_reply,
)


class FakeClient:
    """게이트웨이를 부르지 않고 응답만 흉내 낸다. 호출 인자도 함께 붙잡는다."""

    def __init__(self, raw: str | None = None, error: Exception | None = None) -> None:
        self.raw, self.error, self.kwargs = raw, error, {}

    @property
    def configured(self) -> bool:
        return True

    async def complete(self, instruction: str, message: str, **kwargs) -> str:
        self.kwargs = kwargs
        if self.error is not None:
            raise self.error
        return self.raw or ""


def test_prompt_has_two_text_blocks() -> None:
    instruction, template = load_prompt()
    assert "JSON" in instruction
    assert "answers" in instruction
    assert "{text}" in template


def test_parse_takes_only_five_answers_and_a_grade() -> None:
    """**출력이 숫자 다섯과 등급뿐이다.** 자유 문장을 받지 않아 인젝션의 피해 범위가
    지침이 아니라 구조로 막힌다."""
    parsed = parse('앞말 {"answers": [2, 3, 4, 3, 1], "confidence": "HIGH"} 뒷말')
    assert parsed == ("23431", "HIGH")


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "JSON 이 아닙니다",
        '{"answers": [1, 2, 3], "confidence": "HIGH"}',  # 개수 부족
        '{"answers": [1, 2, 3, 4, 5], "confidence": "HIGH"}',  # 범위 밖
        '{"answers": [1, 2, 3, 4, "3"], "confidence": "HIGH"}',  # 문자열
        '{"answers": [1, 2, 3, 4, 3], "confidence": "VERY"}',  # 없는 등급
        '{"confidence": "HIGH"}',  # answers 없음
        '{"answers": "23431", "confidence": "HIGH"}',  # 배열이 아님
    ],
)
def test_parse_drops_anything_off_the_shape(raw: str) -> None:
    """값 하나라도 어긋나면 통째로 버린다. 반쯤 읽은 선언은 선언이 아니다."""
    assert parse(raw) is None


def test_confidence_is_demoted_only_when_nothing_was_read() -> None:
    """모델이 매긴 등급을 그대로 믿지 않는다 — 경계에서 흔들린다(5회 재실행 폭 21).

    다만 **한 문항이라도 읽혔으면 그것은 선언이다.** 4축 시절 임계를 낮게 뒀다가 한 축만
    말한 문장이 버튼 경로로 되돌려졌다.
    """
    assert demote("33333", "HIGH") == "LOW"
    assert demote("23332", "HIGH") == "LOW"
    assert demote("43333", "HIGH") == "HIGH"
    assert demote("33331", "MID") == "MID"


async def test_interpret_uses_its_own_budget_not_the_report_one() -> None:
    """사용자가 화면에서 기다리는 경로다. 리포트용 예산을 그대로 쓰면 안 된다."""
    client = FakeClient('{"answers": [4, 3, 3, 3, 1], "confidence": "HIGH"}')
    answers, confidence = await interpret(client, "크게 걸지만 손실은 빨리 끊어요")
    assert (answers, confidence) == ("43331", "HIGH")
    assert client.kwargs["retry"] is False
    assert client.kwargs["request_timeout"] <= 5


@pytest.mark.parametrize("text", ["", "   ", "가" * (MAX_INPUT + 1)])
async def test_interpret_does_not_call_on_bad_input(text: str) -> None:
    client = FakeClient('{"answers": [4, 4, 4, 4, 4], "confidence": "HIGH"}')
    assert await interpret(client, text) == UNREADABLE
    assert client.kwargs == {}


async def test_interpret_falls_back_to_the_button_path_on_failure() -> None:
    """이 자리의 실패가 온보딩을 막을 이유가 아니다."""
    from app.services.llm import LlmError

    assert await interpret(FakeClient(error=LlmError("끊김")), "아무 말") == UNREADABLE
    assert await interpret(FakeClient("형식을 벗어난 응답"), "아무 말") == UNREADABLE


def test_reply_scores_with_the_same_table_as_the_button_path() -> None:
    """버튼 경로로 들어오든 이 경로로 들어오든 같은 표를 쓴다."""
    reply = to_reply("24312", "HIGH")
    assert reply["selfAnswers"] == "24312"
    assert reply["selfTotal"] == 12
    assert reply["selfLevel"] == 3
    assert reply["selfName"] == "위험중립형"
    assert reply["selfPersona"]
    assert reply["confidence"] == "HIGH"


def test_reply_carries_answers_but_no_level_when_confidence_is_low() -> None:
    """`LOW` 면 답안은 기본 선택으로 깔되 **단계를 말하지 않는다.**

    읽지 못한 문장에 단계를 붙여 보내면 그것이 그대로 선언으로 저장되고, 결과지 ③⑦ 이
    사용자가 한 적 없는 말을 인용하게 된다.
    """
    reply = to_reply("33333", "LOW")
    assert reply["selfAnswers"] == "33333"
    assert reply["selfTotal"] == 15
    assert reply["selfLevel"] is None
    assert reply["selfName"] == "" and reply["selfPersona"] == ""


def test_unreadable_answers_sit_in_the_middle_band() -> None:
    """읽지 못했을 때의 답안은 **가운데 단계**로 채점돼야 한다.

    `33333` 은 합계 15라 `적극투자형`(14~16)이다 — 구간 폭이 비대칭(5~7은 3점, 17~20은
    4점)이라 가운데 값 다섯이 가운데 단계가 아니다. 저장하는 쪽이 등급을 무시하고 답안만
    채점해도 공격적인 선언이 되지 않아야 한다.
    """
    from app.services.profile import to_level

    answers, confidence = UNREADABLE
    assert confidence == "LOW"
    assert to_level(sum(int(value) for value in answers)) == 3
