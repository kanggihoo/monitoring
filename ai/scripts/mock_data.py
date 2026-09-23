"""목업 거래 데이터 생성기. 서버도 DB 도 필요 없다.

**난수를 필드에 직접 넣지 않는다.** 가격 시계열과 매매 규칙을 먼저 만들고, 그 위에서 거래를
시뮬레이션한 결과를 센다. 그래서 payload 안의 값들이 서로 어긋나지 않는다 — 실현 손익은 실제
체결가와 취득 원가의 차이고, 수익률은 그 손익에서 나오고, 시즌 집계는 같은 거래를 센 값이다.
백엔드와 연동만 되지 않은 상태이고 데이터의 성질은 실제와 같다.

    uv run python scripts/mock_data.py                      # 유저 1명, JSON 출력
    uv run python scripts/mock_data.py --users 30            # 30명
    uv run python scripts/mock_data.py --seed 7 --out mock.json

리포트 문장까지 보려면 `scripts/preview_report.py` 를 쓴다 — 이 파일은 데이터만 만든다.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.schemas.inbound import DailyReportRequest, SeasonReportRequest

# --- 편성 상수. 값의 정본은 V18 마이그레이션이다 ---

SEASON_START = date(2020, 1, 2)
SEASON_END = date(2026, 8, 31)

#: 일차별 게임월 구간 (V18: 2020-01~2022-03 · 2022-04~2024-06 · 2024-07~2026-08).
SEASON_DAY_RANGE: dict[int, tuple[date, date]] = {
    1: (date(2020, 1, 2), date(2022, 3, 31)),
    2: (date(2022, 4, 1), date(2024, 6, 30)),
    3: (date(2024, 7, 1), date(2026, 8, 31)),
}

SEED_KRW = Decimal(100_000_000)
TRADE_FEE_RATE = Decimal("0.0015")  # 0.15%

#: 게임 종목. 실제 고유명사를 쓰지 않는다 (기획 규칙).
#: `asset_type` 은 인버스 비중에, `currency` 는 원화 환산에 쓰인다.
INSTRUMENTS: tuple[tuple[int, str, str, str], ...] = (
    (1, "바나나", "STOCK", "USD"),
    (2, "바구니", "STOCK", "USD"),
    (3, "화면", "STOCK", "KRW"),
    (4, "돌다리", "STOCK", "KRW"),
    (5, "고요", "ETF", "USD"),
    (6, "물결", "INVERSE_ETF", "USD"),
    (7, "구름다리", "STOCK", "KRW"),
    (8, "나침반", "ETF", "KRW"),
)

#: 지수 대리 종목. 실제로는 미결이라(`backend-data-contract.md` 5절) 목업은 ETF 하나를 쓴다.
INDEX_PROXY_ID = 5

META = {row[0]: {"name": row[1], "asset_type": row[2], "currency": row[3]} for row in INSTRUMENTS}


# --- 성향 ---


@dataclass(frozen=True)
class Persona:
    """매매 규칙. 확률만 다르고 절차는 같다.

    성향 이름과 판정 결과(`investorType`)가 일치하는지 확인하는 데도 쓴다 — 어긋나면 판정
    임계값을 의심할 근거가 된다.
    """

    label: str
    #: 거래일 하루에 거래를 시도할 상대 빈도. 1.0 이 기준이다.
    activity: float = 1.0
    #: 전일 상승/하락일 때 매수 쪽을 고를 확률.
    buy_on_up: float = 0.5
    buy_on_down: float = 0.5
    #: 보유 중이고 전일 하락일 때 매도할 확률.
    sell_on_down: float = 0.3
    #: 인버스 종목을 고를 가중치 (1.0 이면 다른 종목과 같다).
    inverse_bias: float = 1.0
    #: 최소 보유 일수. 이 기간이 지나야 매도 후보가 된다.
    min_hold_days: int = 3


PERSONAS: tuple[Persona, ...] = (
    Persona("추격형", activity=1.2, buy_on_up=0.85, buy_on_down=0.2),
    Persona("역발상형", activity=1.0, buy_on_up=0.2, buy_on_down=0.85, sell_on_down=0.15),
    Persona("패닉형", activity=1.1, sell_on_down=0.8),
    Persona("관망형", activity=0.08, sell_on_down=0.2),
    Persona("장기보유형", activity=0.6, buy_on_down=0.6, sell_on_down=0.05, min_hold_days=180),
    Persona("인버스형", buy_on_up=0.4, buy_on_down=0.6, inverse_bias=9.0),
)


# --- 시장. 사용자와 무관하게 한 번만 만든다 ---


def _money(value: Decimal | float, places: str = "0.01") -> Decimal:
    return Decimal(str(value)).quantize(Decimal(places), rounding=ROUND_HALF_UP)


def trading_days() -> list[date]:
    """거래일 달력. 실제는 한·미 거래일의 합집합이지만 목업은 평일로 근사한다."""
    days, cursor = [], SEASON_START
    while cursor <= SEASON_END:
        if cursor.weekday() < 5:
            days.append(cursor)
        cursor += timedelta(days=1)
    return days


@dataclass
class Market:
    """종가 시계열과 환율. 랜덤워크로 만들되 한 번 만들면 고정이다."""

    days: list[date]
    closes: dict[int, list[Decimal]]
    usd_krw: dict[date, Decimal]
    index_of: dict[date, int] = field(default_factory=dict)

    def close(self, instrument_id: int, day: date) -> Decimal:
        return self.closes[instrument_id][self.index_of[day]]

    def next_close(self, instrument_id: int, day: date) -> Decimal | None:
        """다음 **거래일**의 종가. 휴장일을 건너뛴 '다음 행'이다. 마지막 날은 `None`."""
        position = self.index_of[day] + 1
        if position >= len(self.days):
            return None
        return self.closes[instrument_id][position]

    def rose(self, instrument_id: int, day: date) -> bool | None:
        """직전 거래일보다 올랐나. 첫날과 등락 0 은 `None` (어느 쪽에도 세지 않는다)."""
        position = self.index_of[day]
        if position == 0:
            return None
        series = self.closes[instrument_id]
        if series[position] == series[position - 1]:
            return None
        return series[position] > series[position - 1]

    def relative_position(self, instrument_id: int, day: date, price: Decimal) -> Decimal | None:
        """창 20거래일(전후) 종가의 최고·최저 대비 체결가의 위치. 분모가 0 이면 `None`.

        게임에 고가·저가가 없어서(단일 가격 모델) 종가로 창을 잡는다. 창이 달력 밖으로
        나가면 있는 만큼만 쓴다 — 시즌 첫·마지막 20거래일이 그렇다.
        """
        center = self.index_of[day]
        window = self.closes[instrument_id][max(0, center - WINDOW) : center + WINDOW + 1]
        high, low = max(window), min(window)
        if high == low:
            return None
        return (price - low) / (high - low)

    def to_krw(self, instrument_id: int, day: date, amount: Decimal) -> Decimal:
        if META[instrument_id]["currency"] == "KRW":
            return amount
        return amount * self.usd_krw[day]


#: 급변동일 비율과 그때 변동성이 몇 배가 되는지. 재생 구간에 2020년 3월 같은 국면이 있어야
#: 고점 매수·저점 매도 태그가 실제로 붙는다 — 평시 변동성만으로는 ±10% 날이 거의 없다.
#: 상대 가격 위치를 재는 창의 반폭(거래일). 확정값 20거래일이다.
WINDOW = 20

SHOCK_RATE = 0.02
SHOCK_MULTIPLIER = 6.0


def build_market(rng: random.Random) -> Market:
    days = trading_days()
    closes: dict[int, list[Decimal]] = {}
    for instrument_id, _, asset_type, currency in INSTRUMENTS:
        # 인버스는 시장과 반대로 가도록 표류를 음수로 둔다. 그래야 인버스 비중이 성향으로 읽힌다.
        drift = -0.0004 if asset_type == "INVERSE_ETF" else rng.uniform(0.0001, 0.0006)
        volatility = rng.uniform(0.012, 0.028)
        # 통화에 맞는 자릿수를 준다. 원화 종목을 달러 자릿수로 두면 한 번에 수십만 주를 산다.
        price = rng.uniform(8.0, 90.0) if currency == "USD" else rng.uniform(15_000, 250_000)
        series = []
        for _ in days:
            sigma = volatility * SHOCK_MULTIPLIER if rng.random() < SHOCK_RATE else volatility
            price = max(0.5, price * (1 + rng.gauss(drift, sigma)))
            series.append(_money(price))
        closes[instrument_id] = series

    rate, usd_krw = 1150.0, {}
    for day in days:
        rate = max(900.0, min(1500.0, rate * (1 + rng.gauss(0, 0.004))))
        usd_krw[day] = _money(rate, "0.0001")

    return Market(
        days=days,
        closes=closes,
        usd_krw=usd_krw,
        index_of={day: index for index, day in enumerate(days)},
    )


# --- 한 사용자의 3일치 거래 ---


@dataclass
class Lot:
    """보유 묶음. 매도 시 FIFO 로 소진해 취득 원가와 보유 일수를 낸다."""

    day: date
    quantity: int
    price: Decimal


@dataclass
class SeasonTally:
    """시즌 집계. 백엔드가 SQL 로 셀 것과 같은 항목이다."""

    buy_count: int = 0
    sell_count: int = 0
    fomo_buy_count: int = 0
    bottom_snipe_buy_count: int = 0
    peak_sell_win_count: int = 0
    panic_bottom_sell_count: int = 0
    quick_profit_count: int = 0
    quick_stop_count: int = 0
    win_count: int = 0
    total_amount_krw: Decimal = Decimal(0)
    total_fee_krw: Decimal = Decimal(0)
    holding_days_sum: int = 0
    closed_position_count: int = 0


def change_rate(price: Decimal, next_close: Decimal | None) -> Decimal | None:
    """체결가 대비 다음 거래일 종가의 변화율(%). 태그 판정과 태그의 `changeRate` 가 같은 값이다."""
    if next_close is None or price == 0:
        return None
    return _money((next_close - price) / price * 100, "0.0001")


def pattern_kind(side: str, change: Decimal | None) -> str | None:
    """`rpt.md` 13.1.4 의 네 가지 판정을 백엔드가 하는 그대로 재현한다 (±10%)."""
    if change is None:
        return None
    if side == "BUY":
        return "PANIC_BUY_HIGH" if change <= -10 else "LUCKY_BUY_LOW" if change >= 10 else None
    return "PANIC_SELL_LOW" if change >= 10 else "LUCKY_SELL_HIGH" if change <= -10 else None


def _pick_instrument(rng: random.Random, persona: Persona) -> int:
    weights = [
        persona.inverse_bias if META[row[0]]["asset_type"] == "INVERSE_ETF" else 1.0
        for row in INSTRUMENTS
    ]
    return rng.choices([row[0] for row in INSTRUMENTS], weights=weights, k=1)[0]


@dataclass
class Simulated:
    """한 계좌의 3일치 결과."""

    executions: dict[int, list[dict]]
    daily_return: dict[int, Decimal]
    tally: SeasonTally
    krw_cash: Decimal
    total_valuation_krw: Decimal
    #: 일차 경계의 평가액. 최대 낙폭을 이 계열로 근사한다 (실제는 틱 시계열이다).
    valuations: list[Decimal]


def simulate_account(
    rng: random.Random, persona: Persona, market: Market, *, target_per_day: int
) -> Simulated:
    """3일치를 돌려 일차별 체결·수익률과 시즌 집계를 낸다."""
    cash = SEED_KRW
    holdings: dict[int, list[Lot]] = {instrument_id: [] for instrument_id in META}
    executions: dict[int, list[dict]] = {1: [], 2: [], 3: []}
    daily_return: dict[int, Decimal] = {}
    tally = SeasonTally()
    valuations: list[Decimal] = []
    execution_id = 9000

    def valuation(day: date) -> Decimal:
        total = cash
        for instrument_id, lots in holdings.items():
            quantity = sum(lot.quantity for lot in lots)
            if quantity:
                total += market.to_krw(
                    instrument_id, day, market.close(instrument_id, day) * quantity
                )
        return total

    for season_day, (start, end) in SEASON_DAY_RANGE.items():
        days = [day for day in market.days if start <= day <= end]
        if not days:
            continue
        opening = valuation(days[0])
        # 목표 체결 건수를 확률로 옮긴다. 성향의 activity 가 그 위에서 편차를 준다.
        attempt_rate = min(1.0, target_per_day / len(days) * persona.activity)

        for day in days:
            if rng.random() > attempt_rate:
                continue
            instrument_id = _pick_instrument(rng, persona)
            rose = market.rose(instrument_id, day)
            price = market.close(instrument_id, day)
            lots = holdings[instrument_id]
            held = sum(lot.quantity for lot in lots)

            # 방향은 전일 등락과 성향이 함께 정한다.
            if held and rose is False and rng.random() < persona.sell_on_down:
                side = "SELL"
            elif held and rose is True and rng.random() < 0.25:
                side = "SELL"
            elif rng.random() < (persona.buy_on_up if rose else persona.buy_on_down):
                side = "BUY"
            else:
                side = "SELL" if held else "BUY"

            if side == "SELL":
                if not held or (day - lots[0].day).days < persona.min_hold_days:
                    continue
                quantity = max(1, int(held * rng.uniform(0.3, 1.0)))
                # FIFO 로 소진하면서 취득 원가와 보유 일수를 모은다.
                remaining, cost, weighted_days = quantity, Decimal(0), 0
                while remaining and lots:
                    lot = lots[0]
                    taken = min(remaining, lot.quantity)
                    cost += lot.price * taken
                    weighted_days += (day - lot.day).days * taken
                    lot.quantity -= taken
                    remaining -= taken
                    if lot.quantity == 0:
                        lots.pop(0)
                quantity -= remaining
                if quantity < 1:
                    continue
                amount_krw = _money(market.to_krw(instrument_id, day, price * quantity))
                fee = _money(amount_krw * TRADE_FEE_RATE)
                cash += amount_krw - fee
                # 실현 손익은 체결가와 취득 원가의 차이다. 난수가 아니다.
                realized_krw = _money(market.to_krw(instrument_id, day, (price * quantity) - cost))
                tally.sell_count += 1
                tally.win_count += 1 if realized_krw > 0 else 0
                tally.holding_days_sum += weighted_days // quantity
                tally.closed_position_count += 1
                # 유형 판정의 분자. 백엔드가 SQL 로 셀 것을 여기서 그대로 센다.
                holding_days = weighted_days // quantity
                rate = (price * quantity - cost) / cost * 100 if cost else Decimal(0)
                position = market.relative_position(instrument_id, day, price)
                if position is not None and position >= Decimal("0.85") and rate > 0:
                    tally.peak_sell_win_count += 1
                if position is not None and position <= Decimal("0.15") and rate <= -5:
                    tally.panic_bottom_sell_count += 1
                if holding_days <= WINDOW and 1 <= rate <= 3:
                    tally.quick_profit_count += 1
                if holding_days <= WINDOW and -5 <= rate <= -3:
                    tally.quick_stop_count += 1
            else:
                budget = cash * Decimal(str(rng.uniform(0.02, 0.12)))
                unit_krw = market.to_krw(instrument_id, day, price)
                quantity = int(budget / unit_krw) if unit_krw else 0
                if quantity < 1:
                    continue
                amount_krw = _money(unit_krw * quantity)
                fee = _money(amount_krw * TRADE_FEE_RATE)
                if amount_krw + fee > cash:
                    continue
                cash -= amount_krw + fee
                lots.append(Lot(day=day, quantity=quantity, price=price))
                realized_krw = None
                tally.buy_count += 1
                position = market.relative_position(instrument_id, day, price)
                if position is not None and position >= Decimal("0.85"):
                    tally.fomo_buy_count += 1
                if position is not None and position <= Decimal("0.15"):
                    tally.bottom_snipe_buy_count += 1

            change = change_rate(price, market.next_close(instrument_id, day))
            execution_id += 1
            # 백엔드가 payload 를 만들 때 쓰는 재료를 한 행에 담아 둔다. 이 행 자체는
            # AI 에 가지 않는다 — v5 계약이 체결 원본을 보내지 않기 때문이다.
            executions[season_day].append(
                {
                    "id": execution_id,
                    "instrumentId": instrument_id,
                    "instrumentName": META[instrument_id]["name"],
                    "side": side,
                    "quantity": quantity,
                    "executedPrice": price,
                    "amountKrw": amount_krw,
                    "feeKrw": fee,
                    "realizedPnlKrw": realized_krw,
                    "returnRate": rate if side == "SELL" else None,
                    "holdingDays": holding_days if side == "SELL" else None,
                    "gameDate": day,
                    "gameMonth": f"{day.year}.{day.month:02d}",
                    "changeRate": change,
                    "kind": pattern_kind(side, change),
                }
            )

            tally.total_amount_krw += amount_krw
            tally.total_fee_krw += fee

        closing = valuation(days[-1])
        valuations.extend([opening, closing])
        daily_return[season_day] = (
            _money((closing - opening) / opening * 100, "0.0001") if opening else Decimal(0)
        )

    return Simulated(
        executions=executions,
        daily_return=daily_return,
        tally=tally,
        krw_cash=_money(cash),
        total_valuation_krw=_money(valuation(market.days[-1])),
        valuations=valuations,
    )


def index_return(market: Market, season_day: int) -> Decimal:
    """지수 대리 종목의 그 일차 구간 수익률(%). 백엔드가 계산할 값과 같은 정의다."""
    start, end = SEASON_DAY_RANGE[season_day]
    days = [day for day in market.days if start <= day <= end]
    first, last = market.close(INDEX_PROXY_ID, days[0]), market.close(INDEX_PROXY_ID, days[-1])
    return _money((last - first) / first * 100, "0.0001")


# --- payload 두 개를 같은 시뮬레이션에서 만든다 ---


MAX_PATTERN_TAGS = 6
QUICK_SELL_DAYS = 20


def _pattern_tags(rows: list[dict]) -> list[dict]:
    """태그가 붙은 거래. 백엔드처럼 변화율 절대값 순으로 여섯 건까지 보낸다."""
    tagged = [row for row in rows if row["kind"]]
    tagged.sort(key=lambda row: (-abs(row["changeRate"]), row["id"]))
    return [
        {
            "kind": row["kind"],
            "changeRate": str(row["changeRate"]),
            "instrumentName": row["instrumentName"],
            "side": row["side"],
            "gameMonth": row["gameMonth"],
        }
        for row in tagged[:MAX_PATTERN_TAGS]
    ]


def _metrics(rows: list[dict], rank: int, participants: int) -> dict:
    """계좌의 그날 요약. 백엔드 `AiDailyReportService.metrics` 와 같은 정의다."""
    sells = [row for row in rows if row["side"] == "SELL"]
    closed = [row for row in sells if row["holdingDays"] is not None]
    amount_by_instrument: dict[str, Decimal] = {}
    for row in rows:
        name = row["instrumentName"]
        amount_by_instrument[name] = amount_by_instrument.get(name, Decimal(0)) + row["amountKrw"]
    traded = sum(amount_by_instrument.values(), Decimal(0))
    top = max(amount_by_instrument.items(), key=lambda item: (item[1], item[0]), default=None)
    return {
        "buyCount": len(rows) - len(sells),
        "sellCount": len(sells),
        "winCount": sum(1 for row in sells if row["realizedPnlKrw"] > 0),
        "realizedPnlKrw": str(_money(sum((row["realizedPnlKrw"] for row in sells), Decimal(0)))),
        "totalFeeKrw": str(_money(sum((row["feeKrw"] for row in rows), Decimal(0)))),
        "avgHoldingDays": (
            round(sum(row["holdingDays"] for row in closed) / len(closed)) if closed else None
        ),
        "quickSellCount": sum(1 for row in closed if row["holdingDays"] <= QUICK_SELL_DAYS),
        "tradedAmountKrw": str(_money(traded)),
        "topInstrumentName": top[0] if top else None,
        "topInstrumentShareKrw": (
            str(_money(top[1] / traded, "0.0001")) if top and traded else None
        ),
        "executionCountRank": rank,
        "participantCount": participants,
    }


def _highlights(rows: list[dict]) -> list[dict]:
    """실현 손익 기준 최고·최악 매도. 매도가 없는 날은 빈 배열이다."""
    sells = [row for row in rows if row["side"] == "SELL"]
    if not sells:
        return []
    picked = [
        ("BEST", max(sells, key=lambda row: (row["realizedPnlKrw"], -row["id"]))),
        ("WORST", min(sells, key=lambda row: (row["realizedPnlKrw"], row["id"]))),
    ]
    return [
        {
            "role": role,
            "instrumentName": row["instrumentName"],
            "side": row["side"],
            "gameMonth": row["gameMonth"],
            "realizedPnlKrw": str(row["realizedPnlKrw"]),
            "returnRate": str(_money(row["returnRate"], "0.0001"))
            if row["returnRate"] is not None
            else None,
            "holdingDays": row["holdingDays"],
        }
        for role, row in picked
    ]


def _max_drawdown(valuations: list[Decimal]) -> Decimal:
    """최대 낙폭(%). 목업은 일차 경계의 평가액으로 근사한다 — 실제는 틱 시계열이다."""
    peak, worst = None, Decimal(0)
    for value in valuations:
        peak = value if peak is None or value > peak else peak
        if peak:
            worst = min(worst, (value - peak) / peak * 100)
    return _money(worst, "0.0001")


def _ranks(values: list[int]) -> list[int]:
    """내림차순 순위. 동점은 같은 순위를 주고 다음을 건너뛴다 (`rank.md` 와 같은 규칙)."""
    order = sorted(range(len(values)), key=lambda index: -values[index])
    ranks = [0] * len(values)
    rank, previous = 0, None
    for seen, index in enumerate(order, start=1):
        if values[index] != previous:
            rank, previous = seen, values[index]
        ranks[index] = rank
    return ranks


def simulate(
    *, seed: int, users: int = 1, season_day: int = 1, executions_per_day: int = 100
) -> tuple[dict, dict, list[str]]:
    """일일 payload, 시즌 payload, 성향 목록을 함께 낸다.

    **하나의 시뮬레이션에서 둘 다 나온다.** 따로 만들면 같은 계좌의 일일과 시즌이 어긋난다 —
    일일은 체결 100건인데 시즌은 "거래 0회"가 되는 식이다.

    체결 원본은 payload 에 담지 않는다. 백엔드가 그것으로 태그·요약·하이라이트를 만들어
    보내기 때문이다 (v5 계약) — 이 함수가 그 전처리를 같이 흉내낸다.
    """
    rng = random.Random(seed)  # noqa: S311 — 목업이라 암호학적 난수가 필요없다
    market = build_market(rng)

    # 계정 번호를 seed 에 묶는다. 고정하면 블록 선택 시드도 고정돼서, 조건이 같을 때
    # **항상 같은 변주**만 나온다 (조건당 문장이 3개인데 1번만 계속 뽑혔다).
    # 실제 서비스는 사람마다 번호가 달라 이 문제가 없다 — 목업이 한 사람만 흉내낸 탓이다.
    account_base = 4000 + seed % 9000

    results, labels = [], []
    for offset in range(users):
        persona = PERSONAS[offset % len(PERSONAS)]
        labels.append(persona.label)
        results.append(simulate_account(rng, persona, market, target_per_day=executions_per_day))

    # 순위는 전원을 봐야 나온다 — 백엔드가 계좌를 나눠 보내지 않는 이유와 같다.
    execution_ranks = _ranks([len(result.executions[season_day]) for result in results])
    final_ranks = _ranks(
        [int(result.total_valuation_krw) for result in results]  # 평가액 내림차순이 곧 순위다
    )

    daily_accounts, season_accounts = [], []
    for offset, result in enumerate(results):
        rows = result.executions[season_day]
        tally = result.tally
        profit = result.total_valuation_krw - SEED_KRW

        # 누적 집계. **일일과 시즌이 같은 이것을 쓴다** — 리포트가 매일 나오고 그때까지를
        # 누적해서 보므로 구간만 다르다. 목업은 체결을 일차로 쪼개 집계하지 않아 어느 일차를
        # 골라도 시즌 전체 값이다. 3일차와 같은 모양을 보는 셈이라 문장 확인에는 충분하다.
        aggregate = {
            "buyCount": tally.buy_count,
            "sellCount": tally.sell_count,
            "fomoBuyCount": tally.fomo_buy_count,
            "bottomSnipeBuyCount": tally.bottom_snipe_buy_count,
            "peakSellWinCount": tally.peak_sell_win_count,
            "panicBottomSellCount": tally.panic_bottom_sell_count,
            "quickProfitCount": tally.quick_profit_count,
            "quickStopCount": tally.quick_stop_count,
            "krwCash": str(result.krw_cash),
            "totalValuationKrw": str(result.total_valuation_krw),
            "holdingDaysSum": tally.holding_days_sum,
            "closedPositionCount": tally.closed_position_count,
            "winCount": tally.win_count,
            "maxDrawdown": str(_max_drawdown(result.valuations)),
            "turnover": str(_money(tally.total_amount_krw / SEED_KRW, "0.0001")),
            "totalFeeKrw": str(_money(tally.total_fee_krw)),
            "finalRank": final_ranks[offset],
            "totalParticipants": users,
            "returnRate": f"{profit / SEED_KRW * 100:+.1f}%",
        }

        daily_accounts.append(
            {
                "accountId": account_base + offset,
                "dailyReturn": str(result.daily_return.get(season_day, Decimal(0))),
                "executionCount": len(rows),
                "patternTags": _pattern_tags(rows),
                "metrics": _metrics(rows, execution_ranks[offset], users),
                "highlights": _highlights(rows),
                "cumulative": aggregate,
            }
        )
        season_accounts.append(
            {
                "accountId": account_base + offset,
                "profileHint": persona_label(labels, offset),
                **aggregate,
            }
        )

    daily = {
        "seasonId": 1,
        "seasonDay": season_day,
        "indexReturn": str(index_return(market, season_day)),
        "accounts": daily_accounts,
    }
    season = {"seasonId": 1, "accounts": season_accounts}
    # 계약과 어긋난 목업이 나오지 않게 스키마로 한 번 통과시킨다.
    DailyReportRequest.model_validate(daily)
    SeasonReportRequest.model_validate(season)
    return daily, season, labels


def persona_label(labels: list[str], offset: int) -> str:
    """목업에만 있는 힌트. 판정 결과와 성향이 어긋나는지 볼 때 쓴다 (계약에는 없는 필드다)."""
    return labels[offset]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="목업 거래 데이터 생성")
    parser.add_argument("--seed", type=int, default=None, help="생략하면 매번 다른 데이터")
    parser.add_argument("--users", type=int, default=1)
    parser.add_argument("--day", type=int, default=1, choices=[1, 2, 3])
    parser.add_argument("--executions", type=int, default=100, help="사용자당 일차별 목표 체결 수")
    parser.add_argument("--out", type=Path, default=None, help="파일로 저장 (생략하면 표준 출력)")
    return parser.parse_args()


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    seed = args.seed if args.seed is not None else random.randrange(1, 10_000)  # noqa: S311
    daily, season, labels = simulate(
        seed=seed, users=args.users, season_day=args.day, executions_per_day=args.executions
    )
    bundle = {"seed": seed, "personas": labels, "daily": daily, "season": season}
    text = json.dumps(bundle, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
        print(f"저장했다: {args.out}  (seed {seed})")
    else:
        print(text)


if __name__ == "__main__":
    main()
