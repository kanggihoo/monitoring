import {
  accountCash,
  availableCash,
  type Direction,
  sendScale,
  shortfallSendAmount,
} from "./account.ts";
import { instrumentById } from "./instruments.ts";
import { applyFill, heldQuantity } from "./positions.ts";
import type { PriceSnapshot } from "./prices.ts";

/**
 * 주문 미리보기와 **시장가 체결** (`ord.md`).
 *
 * 체결을 더한 이유는 하나다 — 매수한 뒤 그것을 파는 흐름을 목만으로 끝까지 볼 수 있어야
 * 한다. 미리보기만 있으면 보유가 늘지 않아 매도를 시험할 수 없다.
 *
 * **예약(지정가)은 받지 않는다.** 예약은 이후 종가가 조건을 만족하는지 매 틱 판정해야
 * 하는데, 거기까지 흉내 내면 목이 제2의 백엔드가 된다. 목의 목적은 화면을 보는 것이다.
 *
 * **매수 수수료는 언제나 0 이다.** 계약이 기획서 6.6.1 과 어긋나기로 한 지점이고, 그래야
 * `잠금액 = 지정가 × 수량` 이 되어 `shortfall` 이 실제 문턱과 같아진다. 수수료를 매수에도
 * 물리면 환전한 직후에 수수료만큼 다시 모자라는 막다른 길이 생긴다.
 */
export type PreviewQuery = {
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  limitPrice: number | null;
};

export function parsePreviewQuery(
  params: URLSearchParams
): PreviewQuery | { error: string } {
  const orderType = params.get("orderType");
  const side = params.get("side");
  const instrumentId = Number(params.get("instrumentId"));
  const quantity = Number(params.get("quantity"));
  const limitPrice = params.get("limitPrice");

  if (orderType !== "MARKET" && orderType !== "LIMIT") {
    return { error: "orderType 은 MARKET 또는 LIMIT 이어야 합니다." };
  }
  if (side !== "BUY" && side !== "SELL") {
    return { error: "side 는 BUY 또는 SELL 이어야 합니다." };
  }
  if (!Number.isInteger(quantity) || quantity < 1) {
    return { error: "수량은 1 이상의 정수여야 합니다." };
  }
  if (orderType === "LIMIT" && !(Number(limitPrice) > 0)) {
    return { error: "지정가는 0보다 커야 합니다." };
  }

  return {
    orderType,
    side,
    instrumentId,
    quantity,
    limitPrice: limitPrice === null ? null : Number(limitPrice),
  };
}

/** @returns 미리보기 응답, 또는 종목을 못 찾았음을 뜻하는 null */
export function previewStub(
  query: PreviewQuery,
  prices: PriceSnapshot,
  feeFree: boolean
) {
  const instrument = instrumentById(query.instrumentId);
  const price = prices.prices.find(
    (entry) => entry.instrumentId === query.instrumentId
  );
  if (!instrument || !price) {
    return null;
  }

  const rate = Number(prices.usdKrwRate);
  const currency = instrument.currency;
  const scale = currency === "KRW" ? 0 : 2;

  const referencePrice =
    query.orderType === "LIMIT" ? (query.limitPrice ?? 0) : Number(price.close);
  const amount = referencePrice * query.quantity;

  // 수수료는 매도에만 붙는다. 무료 타임이면 그것도 0 이다 (`_common.md §6.1`).
  const feeRate = query.side === "SELL" ? (feeFree ? 0 : 0.15) : 0;
  const fee = (amount * feeRate) / 100;
  // 매수는 내야 할 돈, 매도는 받을 돈이다.
  const total = query.side === "SELL" ? amount - fee : amount;

  const available = availableCash(currency);
  // 부족분은 매수에만 생긴다. 매도는 파는 쪽이라 현금이 필요 없다 (`ord.md`).
  const shortfall =
    query.side === "BUY" ? Math.max(0, round(total - available, scale)) : 0;

  return {
    currency,
    referencePrice: referencePrice.toFixed(instrument.priceScale),
    estimatedAmount: amount.toFixed(scale),
    appliedFeeRate: feeRate,
    estimatedFee: fee.toFixed(scale),
    totalAmount: total.toFixed(scale),
    availableCash: available.toFixed(scale),
    shortfall: shortfall.toFixed(scale),
    shortfallExchange: exchangeFor(currency, shortfall, rate),
    feeFree,
  };
}

/** 부족한 통화를 **받는** 방향이다. 국내 종목이면 대칭으로 달러를 원화로 바꾼다. */
function exchangeFor(currency: "KRW" | "USD", shortfall: number, rate: number) {
  if (shortfall <= 0) {
    return null;
  }

  const direction: Direction = currency === "USD" ? "KRW_TO_USD" : "USD_TO_KRW";
  const sendAmount = shortfallSendAmount(direction, shortfall, rate);

  return sendAmount === null
    ? null
    : { direction, sendAmount: sendAmount.toFixed(sendScale(direction)) };
}

function round(value: number, scale: number): number {
  const factor = 10 ** scale;
  return Math.round(value * factor) / factor;
}

/** 체결 번호. 프로세스가 사는 동안만 증가한다. */
let executionSeq = 1;

export type OrderBody = {
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  limitPrice?: number | null;
};

export function parseOrderBody(raw: unknown): OrderBody | { error: string } {
  const body = (raw ?? {}) as Record<string, unknown>;
  const orderType = body.orderType;
  const side = body.side;
  const instrumentId = Number(body.instrumentId);
  const quantity = Number(body.quantity);

  if (orderType !== "MARKET" && orderType !== "LIMIT") {
    return { error: "orderType 은 MARKET 이나 LIMIT 이어야 합니다." };
  }
  if (side !== "BUY" && side !== "SELL") {
    return { error: "side 는 BUY 나 SELL 이어야 합니다." };
  }
  if (!Number.isInteger(instrumentId) || instrumentId <= 0) {
    return { error: "instrumentId 가 필요합니다." };
  }
  // 1주 단위 정수다 (기획 6.5.2).
  if (!Number.isInteger(quantity) || quantity <= 0) {
    return { error: "수량은 1주 단위 정수여야 합니다." };
  }
  return {
    orderType,
    side,
    instrumentId,
    quantity,
    limitPrice: body.limitPrice == null ? null : Number(body.limitPrice),
  };
}

/**
 * 시장가 주문을 그 자리에서 체결한다. 잔고와 보유가 실제로 움직인다.
 *
 * @returns 체결 결과, 또는 막힌 이유
 */
export function executeOrder(
  body: OrderBody,
  prices: PriceSnapshot,
  feeFree: boolean
): { created: unknown } | { error: string; status: number } {
  const instrument = instrumentById(body.instrumentId);
  const price = prices.prices.find(
    (entry) => entry.instrumentId === body.instrumentId
  );
  if (!instrument || !price) {
    return { error: "종목의 시세가 없어 주문할 수 없습니다.", status: 409 };
  }

  const currency = instrument.currency;
  const scale = currency === "KRW" ? 0 : 2;
  const executedPrice = Number(price.close);
  const amount = executedPrice * body.quantity;
  const feeRate = body.side === "SELL" ? (feeFree ? 0 : 0.15) : 0;
  const fee = round((amount * feeRate) / 100, scale);

  const cash = accountCash();
  if (body.side === "BUY") {
    if (round(amount, scale) > availableCash(currency)) {
      return { error: "주문 가능 현금이 모자랍니다.", status: 409 };
    }
  } else if (heldQuantity(body.instrumentId) < body.quantity) {
    return { error: "보유 수량보다 많이 팔 수 없습니다.", status: 409 };
  }

  const realized = applyFill(
    body.instrumentId,
    body.side,
    body.quantity,
    executedPrice
  );

  // 현금을 옮긴다. 매수는 금액만큼 나가고, 매도는 수수료를 뗀 나머지가 들어온다.
  const delta = body.side === "BUY" ? -amount : amount - fee;
  if (currency === "KRW") {
    cash.krwCash = round(cash.krwCash + delta, 0);
  } else {
    cash.usdCash = round(cash.usdCash + delta, 2);
  }

  return {
    created: {
      orderType: "MARKET",
      order: null,
      execution: {
        executionId: executionSeq++,
        orderId: null,
        orderType: "MARKET",
        side: body.side,
        instrumentId: body.instrumentId,
        quantity: body.quantity,
        executedPrice: executedPrice.toFixed(instrument.priceScale),
        currency,
        amount: amount.toFixed(scale),
        appliedFeeRate: feeRate,
        fee: fee.toFixed(scale),
        realizedPnl: realized === null ? null : realized.toFixed(scale),
        gameDate: prices.asOfGameDate,
        tickSeq: prices.asOfTickSeq,
        executedAt: new Date().toISOString(),
      },
    },
  };
}
