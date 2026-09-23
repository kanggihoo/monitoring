import { beforeEach, expect, test, vi } from "vitest";

import { chatStore } from "@/stores/chat-store";
import { liveQueryStore } from "@/stores/live-query-store";
import { sessionStore } from "@/stores/session-store";
import { createTickStore } from "@/stores/tick-store";

import type { Envelope } from "./ws";
import { dispatch, type Stores } from "./ws-dispatch";

let stores: Stores;

const wrap = (type: string, over: Partial<Envelope> = {}): Envelope =>
  ({
    type,
    payload: {},
    tickSeq: null,
    gameDate: null,
    replayEpoch: null,
    ...over,
  }) as unknown as Envelope;

beforeEach(() => {
  stores = {
    tick: createTickStore({ settleMs: null }),
    session: sessionStore,
    chat: chatStore,
  };
  liveQueryStore.setState({ event: null, generation: 0 });
});

test("처음 본 세대는 되감기가 아니다 — 목록을 버리지 않는다", () => {
  dispatch(wrap("game.clock", { replayEpoch: 3 }), stores);
  expect(liveQueryStore.getState().generation).toBe(0);
  expect(stores.tick.getState().replayEpoch).toBe(3);
});

test("세대가 바뀌면 목록 조회를 다시 걸고, 같은 세대는 그대로 둔다", () => {
  dispatch(wrap("game.clock", { replayEpoch: 3 }), stores);
  dispatch(wrap("game.clock", { replayEpoch: 3 }), stores);
  expect(liveQueryStore.getState().generation).toBe(0);

  dispatch(wrap("game.clock", { replayEpoch: 4 }), stores);
  expect(liveQueryStore.getState().generation).toBe(1);
  expect(stores.tick.getState().needsResync).toBe(true);
});

test("목록 갱신용 타입만 liveQueryStore 로 간다", () => {
  for (const type of [
    "market.instruments",
    "account.exchanged",
    "order.changed",
    "order.cancelled",
    "order.filled",
    "order.expired",
    "ipo.competition",
  ]) {
    liveQueryStore.setState({ event: null });
    expect(dispatch(wrap(type, { tickSeq: 1 }), stores)).toBe(true);
    expect(liveQueryStore.getState().event?.type).toBe(type);
  }
  for (const type of [
    "market.prices",
    "ranking.me",
    "noti.created",
    "chat.message",
  ]) {
    liveQueryStore.setState({ event: null });
    dispatch(wrap(type, { tickSeq: 1, payload: {} }), stores);
    expect(liveQueryStore.getState().event).toBeNull();
  }
});

// 목록 갱신을 얹은 뒤에도 원래 가던 곳으로 계속 가야 한다.
test("체결·만료·경쟁률은 목록과 기존 스토어 양쪽에 모두 도착한다", () => {
  const events = vi.spyOn(sessionStore.getState(), "receiveEvent");
  // 히스토리를 받은 뒤라야 틱 스토어가 조각을 조립한다.
  stores.tick.getState().hydrate({
    asOfTickSeq: 0,
    asOfGameDate: "2026-01-01",
    usdKrwRate: "1300",
    prices: [],
  } as never);
  dispatch(
    wrap("order.filled", {
      tickSeq: 1,
      payload: { orderId: 1, quantity: 2, side: "BUY", executedPrice: "1" },
    }),
    stores
  );
  expect(stores.tick.getState().pendingTickSeq).toBe(1);

  dispatch(wrap("order.expired", { payload: { orders: [] } }), stores);
  expect(events).toHaveBeenCalled();
  expect(liveQueryStore.getState().event?.type).toBe("order.expired");
});

test("모르는 타입은 여전히 false 다", () => {
  expect(dispatch(wrap("nope.whatever"), stores)).toBe(false);
});
