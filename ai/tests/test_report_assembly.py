"""골든 테스트 — 발표 자료의 두 화면을 조립으로 재현한다.

숫자가 매매 로그와 맞는지가 가장 중요하다. 문장이 자연스러워도 값이 틀리면 신뢰가 무너진다.
"""

from __future__ import annotations

import pytest

from app.schemas.report import DailyFeatures, Scene, SeasonFeatures
from app.services.report_assembly import SOLE_WIN_ALIAS, assemble_daily, assemble_season
from app.services.report_blocks import BlockError, load_blocks, render

# 종목명은 조립기가 만들지 않고 그대로 실어 보낸다. 실제 게임은 임의의 이름을 쓴다.
MOCKUP_DAILY = DailyFeatures(
    accountId=4021,
    seasonDay=1,
    vsIndex="BELOW",
    mainMistake="PEAK_BUY",
    executionCount=7,
    scenes=[
        Scene(gameMonth="2020.02", instrumentName="바나나", side="BUY", tag="PEAK_BUY"),
        Scene(gameMonth="2020.03", instrumentName="바구니", side="SELL", tag="BOTTOM_SELL"),
        Scene(gameMonth="2020.03", instrumentName="화면", side="BUY", tag="BOTTOM_BUY"),
    ],
)

MOCKUP_SEASON = SeasonFeatures(
    accountId=4021,
    seasonId="S1",
    axis="WORKED",
    winCount=17,
    returnRate="+38.2%",
    rankRate="상위 12%",
)


def texts(slot: str, **when: str) -> list[str]:
    return [
        b["text"]
        for b in load_blocks()[slot]
        if all(b["when"].get(k) == v for k, v in when.items()) and not b.get("fallback")
    ]


def test_daily_headline_matches_condition() -> None:
    result = assemble_daily(MOCKUP_DAILY)
    assert result.headline in texts("daily_headline", vsIndex="BELOW", mainMistake="PEAK_BUY")
    assert "{" not in result.headline


def test_daily_scene_aliases() -> None:
    result = assemble_daily(MOCKUP_DAILY)
    assert len(result.scene_aliases) == 3
    # 수익 거래가 한 건뿐이라 코드가 직접 별칭을 붙인다.
    assert result.scene_aliases[2] == SOLE_WIN_ALIAS
    # 손실 거래는 두 건이라 특수 별칭이 아니라 태그별 블록에서 나온다.
    assert result.scene_aliases[0] in texts("trade_alias", tag="PEAK_BUY")
    assert result.scene_aliases[1] in texts("trade_alias", tag="BOTTOM_SELL")


def test_season_verdict_carries_numbers() -> None:
    result = assemble_season(MOCKUP_SEASON)
    # 판정이 안 선 계좌는 카드가 유보다. 카드는 이제 4축이 아니라 성향 5단계에서 온다.
    assert result.type_name == "아직 그리는 중"
    assert (
        assemble_season(MOCKUP_SEASON.model_copy(update={"profile_level": 4})).type_name
        == "변동성을 즐기는 트레이더"
    )
    # 국면 문장은 그 구간의 지수 방향으로 갈린다. 목업에는 지수 값이 없어 폴백이다.
    assert result.verdict.startswith("지금 지나온 구간에서 ")
    up = assemble_season(MOCKUP_SEASON.model_copy(update={"market_move": "UP"}))
    assert not up.verdict.startswith("지금 지나온 구간에서 ")
    assert "+38.2%" in result.verdict
    assert "{" not in result.verdict


def test_same_input_gives_same_text() -> None:
    """리포트는 폐장 스냅샷이라 다시 열면 같은 문장이어야 한다."""
    assert assemble_daily(MOCKUP_DAILY) == assemble_daily(MOCKUP_DAILY)
    assert assemble_season(MOCKUP_SEASON) == assemble_season(MOCKUP_SEASON)


def test_different_accounts_get_different_variants() -> None:
    """같은 조건이어도 옆자리와 같은 문장이 뜨지 않는다."""
    seen = {
        assemble_daily(MOCKUP_DAILY.model_copy(update={"account_id": i})).headline
        for i in range(1, 60)
    }
    assert len(seen) > 1


def test_no_trade_user_still_gets_headline() -> None:
    quiet = MOCKUP_DAILY.model_copy(
        update={"main_mistake": "QUIET", "execution_count": 0, "scenes": []}
    )
    result = assemble_daily(quiet)
    assert result.headline
    assert result.scene_aliases == []


def test_scene_aliases_do_not_repeat_within_one_report() -> None:
    """한 화면에 같은 별칭이 두 번 나오지 않아야 한다.

    실제로 겪은 결함이다 — 고점 매도 장면 두 건에 `ALIAS_PEAKSELL_2` 가 함께 붙어
    "때맞춘 작별"이 두 줄에 나왔다. 장면마다 시드를 다르게 해도 후보가 4개면 겹칠 수 있다.
    """
    same_tag_twice = MOCKUP_DAILY.model_copy(
        update={
            "scenes": [
                Scene(gameMonth="2021.11", instrumentName="화면", side="SELL", tag="PEAK_SELL"),
                Scene(gameMonth="2021.11", instrumentName="고요", side="SELL", tag="PEAK_SELL"),
                Scene(gameMonth="2020.10", instrumentName="물결", side="SELL", tag="PEAK_SELL"),
            ]
        }
    )
    result = assemble_daily(same_tag_twice)
    assert len(set(result.scene_aliases)) == len(result.scene_aliases), result.scene_aliases


def test_verdict_does_not_repeat_the_context_phrase() -> None:
    """국면 블록과 작동 블록을 이어 붙일 때 같은 말이 두 번 나오지 않아야 한다.

    실제로 겪은 결함이다 — `CTX_FALLBACK`("이번 시즌 재생 구간에서")과 `EFFECT_FALLBACK`
    ("이번 시즌 거래는 …")이 만나 "이번 시즌 재생 구간에서 이번 시즌 거래는" 이 나왔다.
    조립은 문장 두 개를 공백으로 잇기만 하므로, 겹침은 블록 문안에서 막아야 한다.
    """
    contexts = [b["text"] for b in load_blocks()["season_context"]]
    effects = [b["text"] for b in load_blocks()["type_effect"]]
    for context in contexts:
        # 국면 블록의 앞 두 어절이 뒤 문장에 다시 나오면 읽을 때 걸린다.
        opening = " ".join(context.split()[:2])
        for effect in effects:
            assert opening not in effect, f"'{opening}' 가 겹친다: {context} + {effect}"


def test_dedup_skips_previous_block() -> None:
    first = assemble_daily(MOCKUP_DAILY)
    second = assemble_daily(MOCKUP_DAILY, exclude={first.block_ids[0]})
    assert second.block_ids[0] != first.block_ids[0]


def test_render_rejects_missing_value() -> None:
    """조용히 비우면 '0회 중 회가' 가 사용자에게 나간다."""
    with pytest.raises(BlockError):
        render("하락 구간 매수 {count}회 중 {hitCount}회", {"count": 3})


def test_market_phase_sentence_follows_the_index() -> None:
    """총평을 여는 국면 문장은 **그 구간의 지수 방향**에서 온다.

    예전에는 시즌마다 운영자가 쓴 한 문장을 그대로 붙였다. 리포트가 매일 나오면서 같은
    문장이 모두에게 사흘 내내 나갔다.
    """
    openings = {
        move: assemble_season(MOCKUP_SEASON.model_copy(update={"market_move": move})).verdict.split(
            " 구간"
        )[0]
        for move in ("UP", "FLAT", "DOWN")
    }
    assert len(set(openings.values())) == 3, openings


def test_market_phase_sentence_does_not_repeat_next_day() -> None:
    """어제 쓴 국면 문장은 오늘 빠진다. 지수 방향은 며칠씩 이어지기 때문이다."""
    down = MOCKUP_SEASON.model_copy(update={"market_move": "DOWN"})
    first = assemble_season(down)
    used = next(block for block in first.block_ids if block.startswith("CTX_"))
    second = assemble_season(down, exclude={used})
    assert used not in second.block_ids
