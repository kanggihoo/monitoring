"""판정기 검사.

리포트 입력은 백엔드 payload 이고(ADR-0002), 그 계약 검사는 `test_inbound.py` 가 한다.
"""

from __future__ import annotations

from decimal import Decimal

from app.schemas.report import DailyFeatures
from app.services.extract import (
    THRESHOLDS,
    extract_daily,
    pick_scenes,
    to_axis,
    to_main_mistake,
    to_rank_rate,
    to_vs_index,
)
from app.services.report_assembly import assemble_daily


def tag(kind: str, change: str, name: str = "바구니", side: str = "BUY") -> dict:
    """백엔드가 보내는 태그 한 건 (`InboundPatternTag` 와 같은 모양)."""
    return {
        "kind": kind,
        "change_rate": Decimal(change),
        "instrument_name": name,
        "side": side,
        "game_month": "2020.02",
    }


def highlight(role: str, pnl: str, name: str = "화면") -> dict:
    return {
        "role": role,
        "instrument_name": name,
        "side": "SELL",
        "game_month": "2020.03",
        "realized_pnl_krw": Decimal(pnl),
        "return_rate": Decimal("12.5"),
        "holding_days": 30,
    }


def test_vs_index_band() -> None:
    band = THRESHOLDS["vs_index_band"]
    assert to_vs_index(Decimal("6"), Decimal("1")) == "ABOVE"
    assert to_vs_index(Decimal("1"), Decimal("6")) == "BELOW"
    assert to_vs_index(Decimal("1"), Decimal("1")) == "SIMILAR"
    # 경계는 SIMILAR 로 남는다.
    assert to_vs_index(Decimal("1") + band, Decimal("1")) == "SIMILAR"


def test_main_mistake_prefers_tags() -> None:
    tags = [{"kind": "PANIC_BUY_HIGH"}, {"kind": "PANIC_SELL_LOW"}]
    # 동수면 우선순위 순서로 자른다.
    assert to_main_mistake(tags, 3) == "PEAK_BUY"
    assert to_main_mistake([{"kind": "PANIC_SELL_LOW"}], 3) == "BOTTOM_SELL"


def test_main_mistake_without_tags() -> None:
    """태그가 없으면 체결 건수로 셋을 가른다. 경계값을 양쪽에서 집는다."""
    assert to_main_mistake([], 40) == "OVERTRADE"
    assert to_main_mistake([], 16) == "OVERTRADE"
    assert to_main_mistake([], 0) == "QUIET"
    assert to_main_mistake([], 2) == "QUIET"
    assert to_main_mistake([], 3) == "NORMAL"
    assert to_main_mistake([], 15) == "NORMAL"


def test_quiet_count_actually_gates_quiet() -> None:
    """`quiet_count` 가 판정에 관여하는지 본다.

    예전에는 `QUIET` 이 else 가지라 이 임계값이 한 번도 읽히지 않았다. 값을 바꿔도 판정이
    그대로면 회귀한 것이다 — 3~15건 거래한 사람이 "아무것도 하지 않았다" 는 문장을 받는다.
    """
    original = THRESHOLDS["quiet_count"]
    try:
        THRESHOLDS["quiet_count"] = 5
        assert to_main_mistake([], 5) == "QUIET"
        assert to_main_mistake([], 6) == "NORMAL"
    finally:
        THRESHOLDS["quiet_count"] = original


def test_main_mistake_ignores_unknown_kind() -> None:
    """모르는 `kind` 가 오면 무시한다. 백엔드가 태그를 늘려도 조립이 깨지지 않는다."""
    assert to_main_mistake([{"kind": "SOMETHING_NEW"}], 40) == "OVERTRADE"


def test_pick_scenes_orders_tags_by_change_rate() -> None:
    scenes = pick_scenes(
        [
            tag("PANIC_BUY_HIGH", "-12", "바나나"),
            tag("LUCKY_SELL_HIGH", "-31", "화면", side="SELL"),
            tag("PANIC_SELL_LOW", "18", "바구니", side="SELL"),
        ]
    )
    assert [scene.instrument_name for scene in scenes] == ["화면", "바구니", "바나나"]
    assert [scene.tag for scene in scenes] == ["PEAK_SELL", "BOTTOM_SELL", "PEAK_BUY"]
    assert scenes[0].game_month == "2020.02"


def test_pick_scenes_fills_from_highlights() -> None:
    """태그가 모자라면 최고·최악 매도로 채운다. ±10% 를 넘지 않은 날이 그런 날이다."""
    scenes = pick_scenes(
        [tag("PANIC_BUY_HIGH", "-12", "바나나")],
        [highlight("BEST", "1840000"), highlight("WORST", "-920000", "돌다리")],
    )
    assert [scene.tag for scene in scenes] == ["PEAK_BUY", "BEST_TRADE", "WORST_TRADE"]


def test_pick_scenes_skips_highlights_with_wrong_sign() -> None:
    """손실만 난 날의 '최고'는 덜 잃은 거래다. 잘한 거래로 부르지 않는다."""
    scenes = pick_scenes([], [highlight("BEST", "-100"), highlight("WORST", "-920000", "돌다리")])
    assert [scene.tag for scene in scenes] == ["WORST_TRADE"]


def test_pick_scenes_drops_duplicates() -> None:
    """매도가 한 건인 날은 최고와 최악이 같은 체결을 가리킨다."""
    same = highlight("BEST", "1840000")
    scenes = pick_scenes([], [same, {**same, "role": "WORST", "realized_pnl_krw": Decimal("-1")}])
    assert len(scenes) == 1


def test_pick_scenes_takes_at_most_three() -> None:
    scenes = pick_scenes([tag("PANIC_BUY_HIGH", str(-10 - n), f"종목{n}") for n in range(6)])
    assert len(scenes) == 3


def test_extract_daily_feeds_the_assembler() -> None:
    features = extract_daily(
        account_id=4021,
        season_day=1,
        daily_return=Decimal("-0.5"),
        index_return=Decimal("6.4"),
        execution_count=7,
        pattern_tags=[tag("PANIC_BUY_HIGH", "-12"), tag("PANIC_SELL_LOW", "14", side="SELL")],
    )
    assert isinstance(features, DailyFeatures)
    assert features.vs_index == "BELOW"
    assert features.main_mistake == "PEAK_BUY"
    result = assemble_daily(features)
    assert result.headline
    assert len(result.scene_aliases) == 2


def test_axis_follows_the_result_first() -> None:
    """수익으로 끝난 사람에게 '어긋났다' 문장을 주면 같은 문장 안에서 앞뒤가 맞지 않는다."""
    assert to_axis(win_count=1, sell_count=20, return_rate="+38.2%") == "WORKED"
    assert to_axis(win_count=19, sell_count=20, return_rate="-12.0%") == "BACKFIRED"


def test_axis_falls_back_to_win_rate() -> None:
    """수익률이 0이거나 읽히지 않으면 승률로 가른다."""
    assert to_axis(win_count=12, sell_count=20, return_rate="0.0%") == "WORKED"
    assert to_axis(win_count=3, sell_count=20, return_rate="") == "BACKFIRED"
    # 표본이 안 되게 적게 팔았으면 통한 것으로 보지 않는다.
    assert to_axis(win_count=2, sell_count=2, return_rate="0.0%") == "BACKFIRED"


def test_rank_rate() -> None:
    assert to_rank_rate(1, 100) == "상위 1%"
    assert to_rank_rate(12, 100) == "상위 12%"
    assert to_rank_rate(100, 100) == "상위 100%"
    # 순위를 모르면 빈 문자열이고, 그 값을 요구하는 블록은 후보에서 빠진다.
    assert to_rank_rate(0, 100) == ""
    assert to_rank_rate(3, 0) == ""
