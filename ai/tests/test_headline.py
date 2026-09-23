"""헤드라인 LLM 생성 — 프롬프트 로딩, 검사, 실패 시 폴백."""

from __future__ import annotations

import httpx2
import pytest

from app.core.config import Settings
from app.schemas.report import DailyFeatures, Scene
from app.services.headline import (
    MAX_LENGTH,
    build_message,
    generate,
    generate_many,
    load_prompt,
    validate,
)
from app.services.llm import LlmClient
from app.services.report_assembly import assemble_daily


def features(**overrides: object) -> DailyFeatures:
    base: dict[str, object] = {
        "accountId": 4001,
        "seasonDay": 1,
        "vsIndex": "ABOVE",
        "mainMistake": "PEAK_BUY",
        "executionCount": 12,
        "scenes": [
            Scene(gameMonth="2021-03", instrumentName="바나나", side="BUY", tag="PEAK_BUY"),
        ],
    }
    base.update(overrides)
    return DailyFeatures(**base)  # type: ignore[arg-type]


def stub_client(handler) -> LlmClient:
    settings = Settings(_env_file=None, env="local", llm_api_key="test-gms-key")
    client = LlmClient(settings)
    client._client = httpx2.AsyncClient(
        base_url=settings.llm_base_url,
        transport=httpx2.MockTransport(handler),
    )
    return client


def responds(text: str):
    def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200, json={"choices": [{"message": {"role": "assistant", "content": text}}]}
        )

    return handler


# --- 프롬프트 ---


def test_load_prompt_returns_two_blocks() -> None:
    """프롬프트로딩_파일읽기_지침과사용자틀두개를반환한다"""
    # 블록 개수·순서가 계약이다. 문안을 고치다 블록을 늘리면 여기서 걸린다.
    instruction, template = load_prompt()

    assert "카피라이터" in instruction
    assert "{vsIndex}" in template


def test_build_message_fills_every_placeholder() -> None:
    """사용자메시지_조건벡터주입_치환되지않은자리가없다"""
    message = build_message(features())

    assert "{" not in message
    assert "시장보다 앞섰다" in message


def test_build_message_omits_instrument_name() -> None:
    """사용자메시지_장면포함_종목명을보내지않는다"""
    # 게임 종목은 실제와 다른 임의의 이름이다. 이름을 아예 안 보내면 익명화가 구조로 지켜진다.
    message = build_message(features())

    assert "바나나" not in message


# --- 검사 ---


def test_validate_accepts_plain_sentence() -> None:
    """생성문장검사_평범한한문장_그대로통과한다"""
    assert validate("이긴 날에도 아쉬운 손이 하나 있었습니다.") == (
        "이긴 날에도 아쉬운 손이 하나 있었습니다."
    )


def test_validate_strips_wrapping_quotes() -> None:
    """생성문장검사_따옴표로감싼문장_따옴표를벗겨통과한다"""
    assert validate('"앞선 하루였습니다."') == "앞선 하루였습니다."


def test_validate_collapses_whitespace() -> None:
    """생성문장검사_줄바꿈포함_한줄로눕혀통과한다"""
    assert validate("앞선 하루였고\n한 번 어긋났습니다.") == "앞선 하루였고 한 번 어긋났습니다."


def test_validate_rejects_digits() -> None:
    """생성문장검사_숫자포함_거른다"""
    # 수치는 화면의 지표 칩이 따로 보여준다. 헤드라인이 말하면 두 값이 어긋날 수 있다.
    assert validate("3번 고점을 밟았습니다.") is None


def test_validate_rejects_real_proper_noun() -> None:
    """생성문장검사_실제고유명사_거른다"""
    assert validate("코스피보다 앞선 하루였습니다.") is None


def test_validate_rejects_advice() -> None:
    """생성문장검사_투자권유_거른다"""
    assert validate("다음에는 저점에서 사는 것을 추천합니다.") is None


def test_validate_rejects_too_long() -> None:
    """생성문장검사_길이초과_거른다"""
    assert validate("가" * (MAX_LENGTH + 1) + ".") is None


def test_validate_rejects_unterminated_sentence() -> None:
    """생성문장검사_마침표없음_거른다"""
    # 잘려서 온 문장이다. 그대로 내보내면 화면에서 말이 끊긴다.
    assert validate("앞선 하루였지만 꼭대기를") is None


def test_validate_rejects_empty() -> None:
    """생성문장검사_빈문자열_거른다"""
    assert validate("   ") is None


# --- 생성 ---


async def test_generate_returns_validated_headline() -> None:
    """헤드라인생성_정상응답_문장을반환한다"""
    client = stub_client(responds("앞선 하루였고, 타이밍은 한 번 어긋났습니다."))

    assert await generate(client, features()) == "앞선 하루였고, 타이밍은 한 번 어긋났습니다."


async def test_generate_returns_none_when_validation_fails() -> None:
    """헤드라인생성_검사불통과_None을반환한다"""
    client = stub_client(responds("코스피 대비 3% 앞섰습니다."))

    assert await generate(client, features()) is None


async def test_generate_returns_none_on_upstream_failure() -> None:
    """헤드라인생성_게이트웨이오류_예외대신None을반환한다"""
    # 헤드라인 하나 때문에 리포트 전체가 실패하면 안 된다.
    client = stub_client(lambda request: httpx2.Response(401))

    assert await generate(client, features()) is None


async def test_generate_returns_none_when_disabled() -> None:
    """헤드라인생성_LLM비활성_호출없이None을반환한다"""
    client = LlmClient(Settings(_env_file=None, env="local", llm_api_key=""))

    assert await generate(client, features()) is None


async def test_generate_many_keeps_input_order() -> None:
    """헤드라인일괄생성_여러계정_입력순서를유지한다"""
    client = stub_client(responds("담백한 하루였습니다."))
    batch = [features(accountId=account) for account in (4001, 4002, 4003)]

    assert await generate_many(client, batch) == ["담백한 하루였습니다."] * 3


async def test_generate_many_when_disabled_returns_all_none() -> None:
    """헤드라인일괄생성_LLM비활성_전부None을반환한다"""
    client = LlmClient(Settings(_env_file=None, env="local", llm_api_key=""))

    assert await generate_many(client, [features(), features()]) == [None, None]


# --- 조립기와의 연결 ---


def test_assemble_daily_uses_override_when_given() -> None:
    """일일조립_생성헤드라인주입_그문장을쓴다"""
    text = assemble_daily(features(), headline_override="생성된 한 문장입니다.")

    assert text.headline == "생성된 한 문장입니다."


def test_assemble_daily_override_is_not_counted_as_block() -> None:
    """일일조립_생성헤드라인주입_쓰지않은블록을기록하지않는다"""
    # block_ids 는 다음 일차의 dedup 입력이다. 쓰지 않은 블록이 섞이면 멀쩡한 문장이 빠진다.
    with_override = assemble_daily(features(), headline_override="생성된 한 문장입니다.")
    without = assemble_daily(features())

    assert len(with_override.block_ids) == len(without.block_ids) - 1
    assert all(not block.startswith("HEAD_") for block in with_override.block_ids)


def test_assemble_daily_falls_back_to_block_without_override() -> None:
    """일일조립_생성실패로None전달_블록문장을쓴다"""
    text = assemble_daily(features(), headline_override=None)

    assert text.headline
    assert any(block.startswith("HEAD_") for block in text.block_ids)


@pytest.mark.parametrize("vs_index", ["ABOVE", "SIMILAR", "BELOW"])
def test_build_message_covers_every_vs_index(vs_index: str) -> None:
    """사용자메시지_모든지수대비값_라벨이빠지지않는다"""
    assert "{" not in build_message(features(vsIndex=vs_index))


@pytest.mark.parametrize("mistake", ["PEAK_BUY", "BOTTOM_SELL", "OVERTRADE", "QUIET"])
def test_build_message_covers_every_main_mistake(mistake: str) -> None:
    """사용자메시지_모든주요실수값_라벨이빠지지않는다"""
    assert "{" not in build_message(features(mainMistake=mistake))
