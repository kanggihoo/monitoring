/**
 * 목의 보유 종목. **주문이 실제로 잔고를 바꾸게 하려고 둔다** — 이것이 없으면 매수해도
 * 보유가 늘지 않아 매도를 시험할 수 없다.
 *
 * 평균단가는 **매수 체결가의 가중평균**이다. 매도는 수량만 줄이고 평균단가를 건드리지
 * 않는다 (판 만큼 원가가 함께 나가므로 남은 주식의 단가는 그대로다).
 *
 * 예약 매도로 잠기는 수량(`lockedQuantity`)은 늘 0 이다 — 목은 예약을 체결하지 않는다.
 */
import { accountCash } from "./account.ts";
import { instrumentById } from "./instruments.ts";
import type { PriceSnapshot } from "./prices.ts";

type Holding = {
  instrumentId: number;
  quantity: number;
  /** 매수에 들인 돈의 합 (현지 통화). 평균단가는 여기서 나눠 쓴다. */
  totalCost: number;
};

/** 시드 보유. 되감기가 여기로 되돌린다 — 빈 계좌에서 시작한다. */
const SEED: Holding[] = [];

let holdings: Holding[] = SEED.map((item) => ({ ...item }));

/** 되감기가 부른다 (`state.ts` 의 `resetGameState`). */
export function resetPositions(): void {
  holdings = SEED.map((item) => ({ ...item }));
}

export function heldQuantity(instrumentId: number): number {
  return (
    holdings.find((item) => item.instrumentId === instrumentId)?.quantity ?? 0
  );
}

/**
 * 체결을 보유에 반영한다.
 *
 * @returns 매도면 실현 손익(현지 통화), 매수면 null
 */
export function applyFill(
  instrumentId: number,
  side: "BUY" | "SELL",
  quantity: number,
  price: number
): number | null {
  const found = holdings.find((item) => item.instrumentId === instrumentId);

  if (side === "BUY") {
    if (found) {
      found.quantity += quantity;
      found.totalCost += price * quantity;
    } else {
      holdings.push({ instrumentId, quantity, totalCost: price * quantity });
    }
    return null;
  }

  if (!found || found.quantity < quantity) {
    throw new Error("보유 수량보다 많이 팔 수 없습니다.");
  }

  // 판 만큼의 원가를 덜어낸다. 남은 주식의 평균단가는 그대로다.
  const avg = found.totalCost / found.quantity;
  found.totalCost -= avg * quantity;
  found.quantity -= quantity;
  if (found.quantity === 0) {
    holdings = holdings.filter((item) => item.instrumentId !== instrumentId);
  }
  return (price - avg) * quantity;
}

/** 보유 종목의 원화 환산 평가액 합. 계좌 요약의 `holdingsValuationKrw` 다. */
export function holdingsValuationKrw(prices: PriceSnapshot): number {
  const rate = Number(prices.usdKrwRate);
  return holdings.reduce((sum, item) => {
    const instrument = instrumentById(item.instrumentId);
    const price = prices.prices.find(
      (entry) => entry.instrumentId === item.instrumentId
    );
    if (!instrument || !price) return sum;
    const value = Number(price.close) * item.quantity;
    return sum + (instrument.currency === "USD" ? value * rate : value);
  }, 0);
}

/** `GET /api/v1/accounts/me/positions` 의 `data` (`acc.md`). */
export function positionsStub(prices: PriceSnapshot) {
  const rate = Number(prices.usdKrwRate);
  const cash = accountCash();
  const totalKrw =
    cash.krwCash + cash.usdCash * rate + holdingsValuationKrw(prices);

  const positions = holdings.flatMap((item) => {
    const instrument = instrumentById(item.instrumentId);
    const price = prices.prices.find(
      (entry) => entry.instrumentId === item.instrumentId
    );
    if (!instrument || !price) return [];

    const scale = instrument.currency === "KRW" ? 0 : 2;
    const current = Number(price.close);
    const avg = item.totalCost / item.quantity;
    const valuation = current * item.quantity;
    const valuationKrw =
      instrument.currency === "USD" ? valuation * rate : valuation;
    const pnl = valuation - item.totalCost;

    return [
      {
        instrumentId: item.instrumentId,
        currency: instrument.currency,
        quantity: item.quantity,
        // 목은 예약을 체결하지 않으므로 잠기는 수량이 없다.
        lockedQuantity: 0,
        totalCost: item.totalCost.toFixed(scale),
        avgPrice: avg.toFixed(instrument.priceScale),
        currentPrice: current.toFixed(instrument.priceScale),
        valuation: valuation.toFixed(scale),
        valuationKrw: String(Math.round(valuationKrw)),
        unrealizedPnl: pnl.toFixed(scale),
        unrealizedPnlKrw: String(
          Math.round(instrument.currency === "USD" ? pnl * rate : pnl)
        ),
        returnRate:
          item.totalCost === 0
            ? 0
            : Math.round((pnl / item.totalCost) * 10000) / 100,
        weight:
          totalKrw === 0
            ? 0
            : Math.round((valuationKrw / totalKrw) * 10000) / 100,
      },
    ];
  });

  return {
    asOfGameDate: prices.asOfGameDate,
    asOfTickSeq: prices.asOfTickSeq,
    usdKrwRate: prices.usdKrwRate,
    positions,
  };
}
