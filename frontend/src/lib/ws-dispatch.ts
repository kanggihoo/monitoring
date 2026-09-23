import type { ChatMessage, ClockSnapshot } from "@/lib/market-types";
import type { Envelope } from "@/lib/ws";
import type { ChatStore } from "@/stores/chat-store";
import { liveQueryStore } from "@/stores/live-query-store";
import type { SessionStore } from "@/stores/session-store";
import type { TickStore } from "@/stores/tick-store";

/**
 * 봉투를 스토어 셋으로 가르는 디스패처. **기준은 destination 이 아니라 `type` 이다** —
 * destination 은 넷인데 스토어는 셋이고, 개인 큐 하나에 리듬이 다른 5종이 섞여 온다.
 *
 * 경계 규칙: 매 Tick 의 리듬으로 흐르면 틱 스토어, 사건으로 오면 세션 스토어, 사람이 말하면
 * 채팅 스토어 (`market-replay-frontend-foundation.md` §F).
 */
const TICK_TYPES = new Set([
  "market.prices",
  "news.published",
  "social.heatmap",
  "ipo.competition",
  "account.valuation",
  "account.positions",
  "ranking.me",
  "order.filled",
]);

const EVENT_TYPES = new Set([
  "ipo.disclosed",
  "ipo.announced",
  "ipo.listed",
  "ipo.result",
  "ranking.top10Changed",
  "ranking.finalized",
  "ranking.awarded",
  "order.expired",
  "noti.created",
]);

export type Stores = {
  tick: TickStore;
  session: SessionStore;
  chat: ChatStore;
};

/** @returns 아는 타입이면 true. 모르는 타입은 버리고 false 를 돌려준다 */
export function dispatch(envelope: Envelope, stores: Stores): boolean {
  const { type } = envelope;

  // **종류를 가르기 전에 세대부터 본다.** 되감기를 가장 먼저 알리는 것은 가격이 아니라
  // `game.clock`(`SEEK_STARTED`) 이고, 세 종류(`market.prices` · `game.phase` ·
  // `game.clock`) 가 전부 세대를 싣는다. 여기서 한 번 보면 어느 쪽이 먼저 와도 잡힌다.
  if (envelope.replayEpoch != null) {
    const previousEpoch = stores.tick.getState().replayEpoch;
    stores.tick.getState().observeEpoch(envelope.replayEpoch);
    if (previousEpoch !== null && previousEpoch !== envelope.replayEpoch)
      liveQueryStore.getState().reset();
  }

  // 가격 외 목록은 사건 본문을 사용한다. 마지막 한 통만 보관하며 REST 재조회를 유발하지 않는다.
  if (
    type === "market.instruments" ||
    type.startsWith("ipo.") ||
    type.startsWith("order.") ||
    type === "account.exchanged"
  ) {
    liveQueryStore.getState().receive(envelope);
  }
  if (
    type === "market.instruments" ||
    type === "account.exchanged" ||
    type === "order.changed" ||
    type === "order.cancelled"
  )
    return true;
  if (type === "market.prices") {
    const payload = envelope.payload as { clock?: ClockSnapshot };
    if (payload.clock) stores.session.getState().applyClock(payload.clock);
  }

  if (TICK_TYPES.has(type)) {
    stores.tick.getState().receive(envelope);
    return true;
  }
  if (type === "game.phase") {
    stores.session.getState().applyClock(envelope.payload as ClockSnapshot);
    return true;
  }
  // 재생 시계가 움직였다 — 일시정지·재개·배속 변경·지점 이동(`ReplayService`). **payload 가
  // `{reason}` 하나뿐이라 여기서 시계를 세울 수 없다**: 낡았다는 표시만 올리고 값은 세션
  // 구독이 `GET /game/clock` 으로 받아 온다.
  //
  // 이 타입이 없던 동안 봉투가 통째로 버려졌고(`모르는 메시지 타입입니다 game.clock`),
  // 그래서 되감기·초기화 뒤 헤더의 게임 월과 "다음 종가" 가 옛 값에 굳어 있었다.
  if (type === "game.clock") {
    const payload = envelope.payload as { clock?: ClockSnapshot };
    if (payload.clock) stores.session.getState().applyClock(payload.clock);
    else stores.session.getState().markClockStale();
    return true;
  }
  if (EVENT_TYPES.has(type)) {
    stores.session.getState().receiveEvent(envelope);
    return true;
  }
  if (type === "chat.message") {
    stores.chat.getState().receive(envelope.payload as ChatMessage);
    return true;
  }
  if (type === "chat.deleted") {
    const { messageId } = envelope.payload as { messageId: number };
    stores.chat.getState().markDeleted(messageId);
    return true;
  }
  return false;
}
