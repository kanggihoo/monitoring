import { expect, test } from "vitest";

import type { Envelope } from "@/lib/ws";

import {
  updateExchanges,
  updateExecutions,
  updateInstruments,
  updateOfferings,
  updateOpenOrders,
} from "./live-query-reducers";

const wrap = (type: string, payload: unknown): Envelope =>
  ({
    type,
    payload,
    tickSeq: null,
    gameDate: null,
    replayEpoch: null,
  }) as unknown as Envelope;

const order = (orderId: number, extra: Record<string, unknown> = {}) =>
  ({
    orderId,
    instrumentId: 1,
    side: "BUY",
    quantity: 3,
    limitPrice: "40000.00",
    currency: "KRW",
    lockedAmount: "120000.00",
    lockedQuantity: null,
    createdAt: "2026-01-01T00:00:00Z",
    ...extra,
  }) as never;

// ── 주문 변경 ────────────────────────────────────────────────────────────────
test("order.changed 는 같은 ID 를 교체하고 ID 순서를 지킨다", () => {
  const data = { orders: [order(1), order(3)] } as never as Parameters<
    typeof updateOpenOrders
  >[0];
  const added = updateOpenOrders(data, wrap("order.changed", order(2)));
  expect(added.orders.map((item) => item.orderId)).toEqual([1, 2, 3]);

  const amended = updateOpenOrders(
    added,
    wrap("order.changed", order(2, { quantity: 9 }))
  );
  expect(amended.orders.map((item) => item.orderId)).toEqual([1, 2, 3]);
  expect(amended.orders.find((item) => item.orderId === 2)?.quantity).toBe(9);
});

test("order.cancelled 는 그 예약만 지운다", () => {
  const data = { orders: [order(1), order(2)] } as never as Parameters<
    typeof updateOpenOrders
  >[0];
  expect(
    updateOpenOrders(data, wrap("order.cancelled", { orderId: 2 })).orders.map(
      (o) => o.orderId
    )
  ).toEqual([1]);
});

test("예약 체결은 원 주문을 지우고, 시장가 체결(orderId=null)은 목록을 건드리지 않는다", () => {
  const data = { orders: [order(1), order(2)] } as never as Parameters<
    typeof updateOpenOrders
  >[0];
  expect(
    updateOpenOrders(
      data,
      wrap("order.filled", { orderId: 2, executionId: 9 })
    ).orders.map((o) => o.orderId)
  ).toEqual([1]);
  // 시장가는 서버가 orderId 를 null 로 보낸다 (OrderService#place). 목록이 바뀌면 안 된다.
  const market = updateOpenOrders(
    data,
    wrap("order.filled", { orderId: null, executionId: 9 })
  );
  expect(market.orders.map((o) => o.orderId)).toEqual([1, 2]);
});

test("order.expired 는 한 통의 여러 건을 모두 지운다", () => {
  const data = {
    orders: [order(1), order(2), order(3)],
  } as never as Parameters<typeof updateOpenOrders>[0];
  const left = updateOpenOrders(
    data,
    wrap("order.expired", { orders: [{ orderId: 1 }, { orderId: 3 }] })
  );
  expect(left.orders.map((o) => o.orderId)).toEqual([2]);
});

test("관련 없는 사건은 같은 참조를 돌려준다 — 리렌더가 나지 않는다", () => {
  const data = { orders: [order(1)] } as never as Parameters<
    typeof updateOpenOrders
  >[0];
  expect(
    updateOpenOrders(data, wrap("ipo.competition", { offeringId: 1 }))
  ).toBe(data);
  expect(
    updateOpenOrders(data, wrap("account.exchanged", { exchangeId: 1 }))
  ).toBe(data);
});

// ── 체결 내역 ────────────────────────────────────────────────────────────────
const page = (content: unknown[], over: Record<string, unknown> = {}) =>
  ({
    content,
    page: 0,
    size: 2,
    totalElements: content.length,
    totalPages: 1,
    hasNext: false,
    ...over,
  }) as never;

const execution = (executionId: number, executedAt: string) =>
  ({
    executionId,
    orderId: null,
    executedAt,
    instrumentId: 1,
    side: "BUY",
    quantity: 1,
    executedPrice: "1",
    currency: "KRW",
    amount: "1",
    appliedFeeRate: 0,
    fee: "0",
    realizedPnl: null,
  }) as never;

test("체결은 최신 페이지 앞에 끼고 페이지 크기를 넘지 않는다", () => {
  const data = page([execution(1, "2026-01-01T00:00:01Z")]);
  const next = updateExecutions(
    data,
    wrap("order.filled", execution(2, "2026-01-01T00:00:02Z"))
  );
  expect(next.content.map((item) => item.executionId)).toEqual([2, 1]);
  expect(next.totalElements).toBe(2);

  const third = updateExecutions(
    next,
    wrap("order.filled", execution(3, "2026-01-01T00:00:03Z"))
  );
  expect(third.content.map((item) => item.executionId)).toEqual([3, 2]);
  expect(third.totalElements).toBe(3);
  expect(third.hasNext).toBe(true);
});

// 초기 조회가 이미 담은 체결이 버퍼에서 한 번 더 흘러도 합계가 어긋나면 안 된다.
test("이미 목록에 있는 체결은 다시 세지 않는다", () => {
  const data = page([execution(1, "2026-01-01T00:00:01Z")]);
  expect(
    updateExecutions(
      data,
      wrap("order.filled", execution(1, "2026-01-01T00:00:01Z"))
    )
  ).toBe(data);
});

test("2페이지 이후를 보는 동안에는 줄이 밀리지 않는다", () => {
  const data = page([execution(1, "2026-01-01T00:00:01Z")], { page: 1 });
  expect(
    updateExecutions(
      data,
      wrap("order.filled", execution(2, "2026-01-01T00:00:02Z"))
    )
  ).toBe(data);
});

// ── 환전 이력 ────────────────────────────────────────────────────────────────
const exchange = (exchangeId: number) =>
  ({
    exchangeId,
    direction: "KRW_TO_USD",
    sendAmount: "1",
    receiveAmount: "1",
    fee: "0",
    feeRate: 0,
    rate: "1300",
    gameDate: "2026-01-01",
    exchangedAt: "2026-01-01T00:00:00Z",
  }) as never;

test("환전은 최신 페이지에만 끼고 중복은 세지 않는다", () => {
  const data = page([exchange(1)]);
  const next = updateExchanges(data, wrap("account.exchanged", exchange(2)));
  expect(next.content.map((item) => item.exchangeId)).toEqual([2, 1]);
  expect(next.totalElements).toBe(2);
  expect(updateExchanges(next, wrap("account.exchanged", exchange(2)))).toBe(
    next
  );
});

// ── 공모 ─────────────────────────────────────────────────────────────────────
const offering = (offeringId: number, over: Record<string, unknown> = {}) =>
  ({ offeringId, status: "DISCLOSED", mySubscription: null, ...over }) as never;

test("공시는 목록에 넣되 이미 넣은 내 청약을 지우지 않는다", () => {
  const data = {
    offerings: [offering(1, { mySubscription: { quantity: 5 } })],
  } as never;
  const next = updateOfferings(
    data,
    wrap("ipo.disclosed", { offeringId: 1, status: "DISCLOSED" })
  );
  expect(next.offerings[0]!.mySubscription).toEqual({ quantity: 5 });
});

test("경쟁률·발표·상장·추첨은 그 공모만 바꾼다", () => {
  const data = {
    offerings: [
      offering(1, { mySubscription: { quantity: 5, result: null } }),
      offering(2),
    ],
  } as never;
  expect(
    updateOfferings(
      data,
      wrap("ipo.competition", { offeringId: 1, competitionRate: 3 })
    ).offerings[0]!
  ).toMatchObject({ competitionRate: 3 });
  expect(
    updateOfferings(data, wrap("ipo.listed", { offeringId: 2 })).offerings[1]!
      .status
  ).toBe("LISTED");
  expect(
    updateOfferings(
      data,
      wrap("ipo.announced", { offeringId: 2, winnerCount: 7 })
    ).offerings[1]!
  ).toMatchObject({ status: "ANNOUNCED", expectedWinnerCount: 7 });
  expect(
    updateOfferings(
      data,
      wrap("ipo.result", { offeringId: 1, allocatedQuantity: 2 })
    ).offerings[0]!.mySubscription?.result
  ).toMatchObject({ allocatedQuantity: 2 });
});

// ── 종목 목록 ────────────────────────────────────────────────────────────────
test("공개 목록이 갈려도 개인 관심은 남는다", () => {
  const data = {
    instruments: [
      { instrumentId: 1, watched: true },
      { instrumentId: 2, watched: false },
    ],
  } as never;
  const next = updateInstruments(
    data,
    wrap("market.instruments", {
      instruments: [
        { instrumentId: 1, watched: false },
        { instrumentId: 3, watched: false },
      ],
    })
  );
  expect(
    next.instruments.map((item) => [item.instrumentId, item.watched])
  ).toEqual([
    [1, true],
    [3, false],
  ]);
});
