"""백엔드 payload 를 조건 벡터로 옮기는 어댑터.

[ADR-0002](../../docs/adr/0002-backend-sends-preprocessed-payload.md) 의 입력 경로다.
**계약 변화를 흡수하는 지점이 여기 하나다.**

판정 규칙은 `extract.py` 가 그대로 갖는다. 이 모듈은 모양만 바꾼다 — 계약이 바뀌어도 판정
로직을 손대지 않고, 임계값을 조정해도 계약은 그대로다.
"""

from __future__ import annotations

from decimal import Decimal

from app.schemas.inbound import (
    DailyReportRequest,
    InboundAggregate,
    InboundDailyAccount,
    InboundSeasonAccount,
    SeasonReportRequest,
)
from app.schemas.report import DailyFeatures, SeasonFacts, SeasonFeatures
from app.services.extract import extract_daily, extract_season
from app.services.profile import RawItems, to_loss_tolerance_pct, to_profile_features


def has_daily_trades(account: InboundDailyAccount) -> bool:
    """그 일차에 체결이 있었나. **모델을 부를지 가르는 값이다.**

    예전에는 이 값이 리포트를 만들지 말지까지 갈랐다. 헤드라인만 내던 때의 판단이고, 그때
    근거는 "관망한 날에 할 말이 '아무것도 하지 않으셨습니다' 하나뿐" 이라는 것이었다. 지금은
    둘 다 사실이 아니다 — `daily_headline` 에 `HEAD_ABOVE_QUIET_*`·`HEAD_SIMILAR_QUIET_*`·
    `HEAD_BELOW_QUIET_*` 아홉이 있어 시장 대비 방향까지 말하고, 무엇보다 **결과지는 그날이
    아니라 그때까지의 누적을 본다.** 하루 쉰 것으로 성향 카드를 지울 이유가 없다.

    그래서 거르는 자리를 `routers/reports.py` 로 옮겼다 — 여기는 "모델에게 물어볼 하루였나"
    만 답한다. 없는 체결을 두고 모델에게 문장을 시키면 지어낸다.

    **`executionCount` 하나만 믿지 않는다.** 그 값은 정산 행에서 오고 건수는 집계에서
    오는데, 둘 중 하나만 채워 보내는 배포가 있으면 조용히 갈린다.
    """
    return bool(
        account.execution_count
        or account.metrics.buy_count
        or account.metrics.sell_count
        or account.pattern_tags
        or account.highlights
    )


def has_season_trades(account: InboundAggregate) -> bool:
    """시즌 내내 체결이 한 건도 없었나. 판정이 설 근거가 없다.

    이 계좌는 걸러지지 않으면 네 축이 모두 50 이고 `UNDETERMINED` 카드를 받는다 (명세 8절).
    카드는 나오지만 **그 안에 그 사람에 대한 내용이 없다.**
    """
    return bool(account.buy_count or account.sell_count)


def to_daily_features(request: DailyReportRequest, account: InboundDailyAccount) -> DailyFeatures:
    """한 계좌의 일일 조건 벡터.

    태그와 하이라이트를 그대로 넘긴다. 종목명·연월이 이미 붙어 있어 종목 표도 종가 시계열도
    필요 없다 — 계약이 v5 에서 그렇게 바뀐 이유가 이것이다.
    """
    return extract_daily(
        account_id=account.account_id,
        season_day=request.season_day,
        daily_return=account.daily_return,
        index_return=request.index_return,
        execution_count=account.execution_count,
        pattern_tags=[tag.model_dump() for tag in account.pattern_tags],
        highlights=[highlight.model_dump() for highlight in account.highlights],
    )


def _ratio(numerator: Decimal | int, denominator: Decimal | int) -> Decimal:
    """0으로 나누면 0. 거래가 없는 계좌를 별도 분기 없이 통과시키기 위한 것이다."""
    if not denominator:
        return Decimal(0)
    return Decimal(numerator) / Decimal(denominator)


def _avg_holding_days(account: InboundSeasonAccount) -> int:
    return int(_ratio(account.holding_days_sum, account.closed_position_count))


def _optional(numerator: Decimal | int, denominator: Decimal | int) -> Decimal | None:
    """분모가 0 이면 `None`. **0 이 아니라 `None` 이다** — 잴 수 없었던 것과 바닥인 것은 다르다.

    0 으로 두면 청산이 한 건도 없는 사람이 "가장 짧게 들고 있었다" 로 판정된다.
    """
    if not denominator:
        return None
    return Decimal(numerator) / Decimal(denominator)


def to_raw_items(account: InboundAggregate) -> RawItems:
    """건수·금액 → 5단계 판정의 원지표 다섯.

    **백엔드에 새로 요청할 필드가 없다.** 다섯 항목이 전부 지금 오는 값으로 계산된다 —
    온보딩 문항을 "측정 가능한 것" 으로 바꾼 결과다 (기준 문서 4절).

    `to_raw_metrics`(4축용) 와 분모가 겹치는 자리가 있지만 합치지 않았다. 둘은 서로 다른
    판정 체계이고, 한쪽 공식을 고칠 때 다른 쪽이 조용히 딸려 움직이면 안 된다.
    """
    cash = account.total_cash_krw if account.total_cash_krw is not None else account.krw_cash
    dip = account.bottom_snipe_buy_count + account.panic_bottom_sell_count
    quick = account.quick_stop_count + account.quick_profit_count
    trades = account.buy_count + account.sell_count
    return RawItems(
        # ① 얼마나 넣었나. 현금은 원화+달러를 먼저 보고 없으면 원화로 내려간다 —
        #    달러가 안 오는 동안은 크게 들고 관망한 사람이 "전부 넣었다" 로 잡힌다.
        invested=_optional(account.total_valuation_krw - cash, account.total_valuation_krw),
        # ② 얼마나 큰 하락을 안고 갔나. **시장이 절반쯤 정하는 값**이라 다섯 중 하나로만 쓴다.
        #    안 온 낙폭은 `None` 이다 — 0 으로 접으면 "한 번도 물리지 않았다" 가 되어
        #    이 항목이 가장 보수적인 쪽으로 판정된다.
        drawdown=None if account.max_drawdown is None else abs(account.max_drawdown),
        # ③ 오른 자리에서 샀나.
        entry=_optional(account.fomo_buy_count, account.buy_count),
        # ④ 떨어질 때 담았나 팔았나. 분모가 그 둘의 합이라 "급락을 만난 횟수" 가 된다.
        dip=_optional(account.bottom_snipe_buy_count, dip),
        # ⑤ 빨리 판 것 중 손절 비중. 분모를 `sellCount` 로 두면 75%가 같은 값(0)이 되어
        #    변별이 사라진다 — 목업 3,000계좌에서 확인했다.
        stop=_optional(account.quick_stop_count, quick),
        n_invested=trades,
        n_drawdown=trades,
        n_entry=account.buy_count,
        n_dip=dip,
        n_stop=quick,
    )


def to_facts(account: InboundAggregate, items: RawItems) -> SeasonFacts:
    """⑥ 특징 본문이 인용할 수치. **payload 값을 그대로 옮기기만 한다** — 여기서 지어내면
    "매수 40건 중 33건" 이 거짓이 된다."""
    return SeasonFacts(
        maxDrawdown=account.max_drawdown,
        cashRatio=None if items.invested is None else Decimal(1) - items.invested,
        closedCount=account.closed_position_count,
        holdDays=_optional(account.holding_days_sum, account.closed_position_count),
        buyCount=account.buy_count,
        fomoBuyCount=account.fomo_buy_count,
        profitSells=account.peak_sell_win_count + account.quick_profit_count,
        lossSells=account.panic_bottom_sell_count + account.quick_stop_count,
        dipBuys=account.bottom_snipe_buy_count,
        dipSells=account.panic_bottom_sell_count,
        quickStops=account.quick_stop_count,
        quickProfits=account.quick_profit_count,
    )


#: 지수가 이만큼 움직였으면 방향이 있었다고 본다(%). 일차 하나가 게임 2년 3개월이라
#: 몇 %로는 "올랐다" 고 말하기 어렵다 — 목업에서 일차 지수가 ±60% 까지 움직였다.
MARKET_MOVE_CUT = Decimal(5)


def to_market_move(index_return: Decimal | None) -> str:
    """그 구간의 지수 방향. **모르면 빈 문자열이고, 그때는 국면 문장이 폴백으로 간다.**"""
    if index_return is None:
        return ""
    if index_return >= MARKET_MOVE_CUT:
        return "UP"
    if index_return <= -MARKET_MOVE_CUT:
        return "DOWN"
    return "FLAT"


def to_review_features(
    *, account_id: int, season_id: int, aggregate: InboundAggregate, market_move: str = ""
) -> SeasonFeatures:
    """누적 집계 → 결과지 조건 벡터. **일일과 시즌이 같은 이 함수를 지난다.**

    리포트가 매일 나오고 그때까지를 누적해서 보므로, 1일차와 3일차의 차이는 들어오는 집계의
    구간뿐이다. 판정 경로를 하나로 두면 "3일차에만 맞는 값" 이 생길 자리가 없다.

    **요청 안의 다른 계좌를 보지 않는다.** 참조 분위수 표가 배치 분포를 대신하므로 같은
    계좌는 요청 구성과 무관하게 같은 점수를 받는다 (명세 6절 ②).
    """
    items = to_raw_items(aggregate)
    return extract_season(
        profile=to_profile_features(items, aggregate.self_answers),
        account_id=account_id,
        # 블록의 `when.seasonId` 가 `S1` 이다 — 국면 문장은 시즌 편성에 붙은 것이라 운영자가
        # 시즌 이름으로 쓴다. 숫자 ID 를 그 이름으로 맞춰 준다.
        season_id=f"S{season_id}",
        # 백엔드가 보내 주면 그것을 쓰고, 안 오면 설문 답에서 낸다. 지금은 늘 후자다 —
        # 서버는 채점을 하지 않아 보낼 값이 없다 (`to_loss_tolerance_pct`).
        loss_tolerance_pct=(
            aggregate.loss_tolerance_pct
            if aggregate.loss_tolerance_pct is not None
            else to_loss_tolerance_pct(aggregate.self_answers)
        ),
        max_drawdown=aggregate.max_drawdown,
        facts=to_facts(aggregate, items),
        win_count=aggregate.win_count,
        sell_count=aggregate.sell_count,
        return_rate=aggregate.return_rate,
        final_rank=aggregate.final_rank,
        total_participants=aggregate.total_participants,
        market_move=market_move,
    )


def to_season_features(
    request: SeasonReportRequest,
    account: InboundSeasonAccount,
) -> SeasonFeatures:
    """시즌 요청의 한 계좌. 통합 전의 경로라 남겨 둔다 — 안이 `to_review_features` 다."""
    return to_review_features(
        account_id=account.account_id,
        season_id=request.season_id,
        aggregate=account,
        # 지수 값이 오면 일일 경로와 같은 국면 문장을 쓴다. 안 오면 방향을 말하지 않는다.
        market_move=to_market_move(request.index_return),
    )


def to_daily_review_features(
    request: DailyReportRequest, account: InboundDailyAccount
) -> SeasonFeatures | None:
    """일일 요청에서 결과지 조건 벡터. **누적 집계가 없으면 `None`.**

    백엔드가 아직 `cumulative` 를 안 보내는 동안에도 헤드라인과 장면 별칭은 그대로 나가야
    한다 — 이미 배포된 화면이 그 둘을 쓰고 있다.
    """
    if account.cumulative is None or not has_season_trades(account.cumulative):
        return None
    return to_review_features(
        account_id=account.account_id,
        season_id=request.season_id,
        aggregate=account.cumulative,
        market_move=to_market_move(request.index_return),
    )
