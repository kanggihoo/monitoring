"""받은 값을 조건 벡터로 옮기는 판정기.

**판정 규칙과 임계값이 이 모듈 하나에 모인다.** 계약(백엔드 payload)이 바뀌면
`services/inbound.py` 만 고치고, 판정 기준이 바뀌면 여기만 고친다. 조립기는 이 모듈의 출력만
알고 payload 를 모른다.

입력 경로의 근거는 [ADR-0002](../../docs/adr/0002-backend-sends-preprocessed-payload.md).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from decimal import Decimal, InvalidOperation
from typing import Any

from app.schemas.report import (
    MISTAKE_PRIORITY,
    DailyFeatures,
    EffectAxis,
    MainMistake,
    ProfileItem,
    Scene,
    SeasonFacts,
    SeasonFeatures,
    TradeTag,
    VsIndex,
)
from app.services.profile import ITEM_LABELS, ProfileFeatures

#: 백엔드 `pattern_tags.kind` 와 블록 `when.tag` 의 이름이 다르다. 매핑을 여기 둔다.
#: **모르는 이름은 조용히 버린다** — 백엔드가 태그를 늘려도 AI 가 깨지지 않는다.
TAG_FROM_PATTERN_KIND: dict[str, TradeTag] = {
    "PANIC_BUY_HIGH": "PEAK_BUY",
    "PANIC_SELL_LOW": "BOTTOM_SELL",
    "LUCKY_BUY_LOW": "BOTTOM_BUY",
    "LUCKY_SELL_HIGH": "PEAK_SELL",
}

#: 그날의 최고·최악 매도에 붙이는 태그. 손익 부호가 분명할 때만 쓴다 — 손실만 난 날의
#: '최고'는 덜 잃은 거래일 뿐이라 잘한 거래로 부르면 문장이 거짓이 된다.
TAG_FROM_HIGHLIGHT_ROLE: dict[str, TradeTag] = {"BEST": "BEST_TRADE", "WORST": "WORST_TRADE"}

#: 판정 임계값. 코드에 박지 않고 여기 모아 베타 테스트에서 이 표만 만진다.
THRESHOLDS = {
    "vs_index_band": Decimal("0.5"),  # 지수와의 차이(%p)가 이 안이면 SIMILAR
    "swing_rate": Decimal("10"),  # 고점 매수·저점 매도 판정 등락률(%)
    "overtrade_count": 15,  # 당일 체결이 이보다 많으면 OVERTRADE
    "quiet_count": 2,  # 당일 체결이 이하이면 QUIET
    "panic_sell_ratio": Decimal("0.5"),
    "chase_buy_ratio": Decimal("0.4"),
    "contrarian_buy_ratio": Decimal("0.5"),
    "inverse_weight": Decimal("0.3"),
    "holder_avg_days": 60,
    "observer_cash_ratio": Decimal("0.6"),
}

MAX_SCENES = 3


def to_vs_index(daily_return: Decimal, index_return: Decimal) -> VsIndex:
    """내 수익률과 지수 수익률의 관계. 둘 다 퍼센트 값이다."""
    gap = daily_return - index_return
    band = THRESHOLDS["vs_index_band"]
    if gap > band:
        return "ABOVE"
    if gap < -band:
        return "BELOW"
    return "SIMILAR"


def to_main_mistake(pattern_tags: Sequence[Mapping[str, Any]], execution_count: int) -> MainMistake:
    """그날 가장 두드러진 패턴.

    태그가 여럿이면 건수 최다, 동수면 `MISTAKE_PRIORITY` 순서로 자른다.
    순서를 고정하지 않으면 같은 입력에 다른 리포트가 나온다.

    **잘한 태그(저점 매수·고점 매도)는 세지 않는다.** 이름 그대로 아쉬웠던 쪽을 고르는 자리다.

    태그가 없으면 체결 건수로 셋을 가른다. **가운데 구간이 `NORMAL` 이다** — 예전에는 이 값이
    없어서 `QUIET` 이 나머지를 다 받았고, 그 바람에 `quiet_count` 가 한 번도 읽히지 않았다.
    목업 6,000장에서 `QUIET` 판정의 98.8%가 3건 이상 거래한 사람이었고, 그들에게 "아무것도
    하지 않았다" 는 문장이 나갔다 — 화면에 체결 건수가 함께 떠 있어 옆 숫자가 문장을 반박한다.
    """
    counts: dict[MainMistake, int] = {}
    for tag in pattern_tags:
        mapped = TAG_FROM_PATTERN_KIND.get(str(tag.get("kind", "")))
        if mapped in ("PEAK_BUY", "BOTTOM_SELL"):
            counts[mapped] = counts.get(mapped, 0) + 1
    if counts:
        top = max(counts.values())
        for candidate in MISTAKE_PRIORITY:
            if counts.get(candidate) == top:
                return candidate
    if execution_count > THRESHOLDS["overtrade_count"]:
        return "OVERTRADE"
    if execution_count <= THRESHOLDS["quiet_count"]:
        return "QUIET"
    return "NORMAL"


def _scene(row: Mapping[str, Any], tag: TradeTag) -> Scene:
    return Scene(
        gameMonth=str(row.get("game_month") or ""),
        instrumentName=str(row.get("instrument_name") or "-"),
        side=row.get("side") or "BUY",
        tag=tag,
    )


def _key(scene: Scene) -> tuple[str, str, str]:
    return (scene.game_month, scene.instrument_name, scene.side)


def pick_scenes(
    pattern_tags: Sequence[Mapping[str, Any]],
    highlights: Sequence[Mapping[str, Any]] = (),
) -> list[Scene]:
    """'오늘의 세 장면'.

    **태그가 먼저다.** 백엔드가 판정해 보낸 거래이고, 변화율이 큰 것일수록 그날을 설명한다.
    셋이 차지 않으면 최고·최악 매도로 채운다 — 태그는 ±10% 를 넘긴 거래에만 붙어서, 크게
    벌거나 잃고도 태그가 하나도 없는 날이 있다.

    같은 체결이 태그와 하이라이트 양쪽에 있을 수 있고(연월·종목·방향이 같으면 같은 것으로
    본다), 매도가 한 건인 날은 최고와 최악이 같은 체결을 가리킨다. 둘 다 여기서 걸러낸다.
    """
    scenes: list[Scene] = []
    seen: set[tuple[str, str, str]] = set()

    tagged = [
        (abs(Decimal(str(row.get("change_rate") or 0))), index, row, mapped)
        for index, row in enumerate(pattern_tags)
        if (mapped := TAG_FROM_PATTERN_KIND.get(str(row.get("kind", "")))) is not None
    ]
    # 변화율 절대값 내림차순. 같으면 받은 순서로 갈라 같은 입력에 같은 셋이 나오게 한다.
    tagged.sort(key=lambda item: (-item[0], item[1]))
    for _, _, row, mapped in tagged:
        scene = _scene(row, mapped)
        if _key(scene) in seen:
            continue
        seen.add(_key(scene))
        scenes.append(scene)
        if len(scenes) == MAX_SCENES:
            return scenes

    for row in highlights:
        tag = TAG_FROM_HIGHLIGHT_ROLE.get(str(row.get("role", "")))
        pnl = Decimal(str(row.get("realized_pnl_krw") or 0))
        # 손익 부호가 역할과 맞지 않으면(손실만 난 날의 '최고') 장면으로 쓰지 않는다.
        if tag is None or (tag == "BEST_TRADE" and pnl <= 0) or (tag == "WORST_TRADE" and pnl >= 0):
            continue
        scene = _scene(row, tag)
        if _key(scene) in seen:
            continue
        seen.add(_key(scene))
        scenes.append(scene)
        if len(scenes) == MAX_SCENES:
            break
    return scenes


def extract_daily(
    *,
    account_id: int,
    season_day: int,
    daily_return: Decimal,
    index_return: Decimal,
    execution_count: int,
    pattern_tags: Sequence[Mapping[str, Any]] = (),
    highlights: Sequence[Mapping[str, Any]] = (),
) -> DailyFeatures:
    """한 계좌의 그날에서 일일 조건 벡터를 만든다."""
    return DailyFeatures(
        accountId=account_id,
        seasonDay=season_day,
        vsIndex=to_vs_index(daily_return, index_return),
        mainMistake=to_main_mistake(pattern_tags, execution_count),
        executionCount=execution_count,
        scenes=pick_scenes(pattern_tags, highlights),
    )


#: `to_axis` 가 승률로 가를 때 필요한 최소 매도 건수. 2건 중 1건이 50% 가 되는 것을 막는다.
AXIS_MIN_SELLS = 5

#: 유형이 '통했다'고 보는 승률. 절반을 넘겼는지로 가른다.
AXIS_WIN_RATE = Decimal("0.5")


def to_axis(*, win_count: int, sell_count: int, return_rate: str) -> EffectAxis:
    """이번 장에서 그 방식이 통했는가.

    **결과의 부호가 먼저다.** 같은 문장 안에 `{returnRate}` 가 함께 실리기 때문에, 수익으로
    끝난 사람에게 '어긋났다' 쪽 문장을 주면 한 문장 안에서 앞뒤가 맞지 않는다 (실측:
    `+39.2%` 인데 "되돌아와" 로 시작하는 문장이 나왔다).

    수익률이 정확히 0이거나 읽히지 않을 때만 승률로 가른다 — 그때 남는 사실이 그것뿐이다.
    표본이 안 되게 적게 팔았으면(`min_sample` 미만) 통한 것으로 보지 않는다.
    """
    profit = _percent(return_rate)
    if profit != 0:
        worked = profit > 0
    elif sell_count >= AXIS_MIN_SELLS:
        worked = Decimal(win_count) / Decimal(sell_count) >= AXIS_WIN_RATE
    else:
        worked = False
    return "WORKED" if worked else "BACKFIRED"


def _percent(text: str) -> Decimal:
    """`+38.2%` 같은 표시 문자열에서 부호 있는 숫자를 꺼낸다. 읽지 못하면 0으로 본다."""
    try:
        return Decimal(text.replace("%", "").replace(",", "").strip() or 0)
    except InvalidOperation:
        return Decimal(0)


def to_rank_rate(final_rank: int, total_participants: int) -> str:
    """`상위 12%`. 순위나 분모가 없으면 빈 문자열이고, 그때는 그 문장을 쓰는 블록이 걸러진다.

    올림한다 — 1등을 '상위 0%'라고 부르지 않기 위해서다.
    """
    if final_rank <= 0 or total_participants <= 0:
        return ""
    percent = -(-final_rank * 100 // total_participants)
    return f"상위 {min(percent, 100)}%"


def extract_season(
    *,
    account_id: int,
    season_id: str,
    profile: ProfileFeatures,
    loss_tolerance_pct: int | None = None,
    max_drawdown: Decimal | None = None,
    facts: SeasonFacts | None = None,
    win_count: int = 0,
    sell_count: int = 0,
    return_rate: str = "",
    final_rank: int = 0,
    total_participants: int = 0,
    market_move: str = "",
) -> SeasonFeatures:
    """시즌 조건 벡터. **판정은 `profile` 이 끝내고 여기서는 모양만 맞춘다.**

    예전에는 이 함수가 임계값으로 유형을 골랐다. 그 방식은 배치 분포에 기대고 있어서 같은
    계좌가 요청 구성에 따라 다른 유형을 받았다 (기준 문서 6절).
    """
    scored = profile.observed
    gap = profile.gap
    return SeasonFeatures(
        accountId=account_id,
        seasonId=season_id,
        marketMove=market_move,
        profileLevel=scored.level,
        profileTotal=scored.total,
        profileName=scored.name,
        profilePersona=scored.persona,
        profileItems=[
            ProfileItem(
                item=item.item,
                label=item.label,
                point=item.rounded,
                provisional=item.provisional,
                selfPoint=profile.self_point(item.item),
            )
            for item in scored.items
        ],
        selfLevel=profile.self_level,
        selfTotal=profile.self_total,
        selfPersona=profile.self_persona,
        profileMatch=profile.match,
        gapItem=gap[0] if gap else None,
        gapItemLabel=ITEM_LABELS[gap[0]] if gap else "",
        gapSelfPoint=gap[1] if gap else None,
        gapPoint=gap[2] if gap else None,
        lossTolerancePct=loss_tolerance_pct,
        maxDrawdown=max_drawdown,
        facts=facts or SeasonFacts(),
        axis=to_axis(win_count=win_count, sell_count=sell_count, return_rate=return_rate),
        winCount=win_count,
        returnRate=return_rate,
        rankRate=to_rank_rate(final_rank, total_participants),
    )
