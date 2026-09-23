import { describe, expect, it } from "vitest";

import type { NotificationCreated } from "@/lib/notification-api";
import type { Envelope } from "@/lib/ws";
import { createSessionStore } from "@/stores/session-store";

/**
 * 알림 적재. **`events` 와 `notifications` 는 다른 목록이다** — 저쪽은 이번 접속에 도착한
 * 모든 WS 사건이고, 이쪽은 서버가 문구까지 확정해 저장한 개인 알림이다 (`noti.md`).
 */

const notification = (notificationId: number) => ({
  notificationId,
  kind: "ORDER_FILLED" as const,
  title: "예약 매수가 체결됐습니다",
  body: "엔비디아 3주를 $875.43에 매수했습니다.",
  referenceId: 9812,
  tickSeq: 137,
  gameDate: "2022-04-15",
  read: false,
  createdAt: "2026-09-21T05:31:40Z",
});

const created = (notificationId: number, unreadCount: number): Envelope => ({
  type: "noti.created",
  tickSeq: 137,
  gameDate: "2022-04-15",
  payload: {
    notification: notification(notificationId),
    unreadCount,
  } satisfies NotificationCreated,
});

describe("알림", () => {
  it("noti.created 가 목록에 얹히고 배지가 페이로드의 수를 쓴다", () => {
    const store = createSessionStore();

    store.getState().receiveEvent(created(5521, 4));

    expect(store.getState().notifications).toHaveLength(1);
    expect(store.getState().unreadCount).toBe(4);
  });

  // **배지를 세지 않는다.** 여기서 +1 하면 미접속 중 쌓인 것과 어긋난다 — 서버가 센 값이
  // 유일한 진실이다 (`noti.md` 미읽음 수 전용 경로를 두지 않는 이유).
  it("배지는 직전 값에 더하지 않고 서버 값으로 갈아 끼운다", () => {
    const store = createSessionStore();

    store.getState().receiveEvent(created(5521, 4));
    store.getState().receiveEvent(created(5522, 9));

    expect(store.getState().unreadCount).toBe(9);
  });

  it("다른 사건은 알림 목록을 건드리지 않는다", () => {
    const store = createSessionStore();

    store.getState().receiveEvent({
      type: "ipo.disclosed",
      tickSeq: 100,
      gameDate: "2022-04-15",
      payload: {},
    });

    expect(store.getState().notifications).toEqual([]);
    expect(store.getState().unreadCount).toBe(0);
    // 사건 목록에는 들어간다 — 둘이 다른 목록이라는 것이 이 줄이다.
    expect(store.getState().events).toHaveLength(1);
  });

  it("조회 결과가 배지와 목록을 세운다", () => {
    const store = createSessionStore();

    store.getState().seedNotifications({
      unreadCount: 3,
      content: [notification(5521), notification(5498)],
      page: 0,
      size: 20,
      totalElements: 41,
      totalPages: 3,
      hasNext: true,
    });

    expect(store.getState().notificationsLoaded).toBe(true);
    expect(store.getState().unreadCount).toBe(3);
    expect(store.getState().notifications).toHaveLength(2);
  });

  // 오프셋 페이지의 경계는 새 알림 하나에 밀린다. 그때 겹치는 줄이 두 번 그려지면 React
  // 키가 충돌하고, 사용자에게는 같은 알림이 두 번 온 것으로 보인다.
  it("이전 페이지를 뒤에 잇고 겹치는 줄을 거른다", () => {
    const store = createSessionStore();
    store.getState().seedNotifications({
      unreadCount: 3,
      content: [notification(5521), notification(5498)],
      page: 0,
      size: 20,
      totalElements: 41,
      totalPages: 3,
      hasNext: true,
    });
    store.getState().markNotificationsRead();

    store.getState().appendNotifications({
      // 경계가 밀려 5498 이 다시 왔다.
      unreadCount: 3,
      content: [notification(5498), notification(5470)],
      page: 1,
      size: 20,
      totalElements: 41,
      totalPages: 3,
      hasNext: false,
    });

    expect(
      store.getState().notifications.map((item) => item.notificationId)
    ).toEqual([5521, 5498, 5470]);
    expect(store.getState().notificationsPage).toBe(1);
    expect(store.getState().notificationsHasNext).toBe(false);
    // 배지는 되돌리지 않는다 — 방금 눌러 내린 값을 응답이 다시 올리면 안 없어지는 배지가 된다.
    expect(store.getState().unreadCount).toBe(0);
  });

  // 열람의 단위가 알림 센터다. 배지가 0 이 된 뒤에도 어느 줄이 새 것이었는지는 보여야
  // 하므로 줄의 `read` 도 함께 바꾼다.
  it("읽음 처리는 배지를 0 으로 내리고 줄을 읽음으로 바꾼다", () => {
    const store = createSessionStore();
    store.getState().receiveEvent(created(5521, 4));

    store.getState().markNotificationsRead();

    expect(store.getState().unreadCount).toBe(0);
    expect(store.getState().notifications[0]?.read).toBe(true);
  });

  it("로그아웃은 알림도 지운다", () => {
    const store = createSessionStore();
    store.getState().receiveEvent(created(5521, 4));

    store.getState().clearPersonal();

    expect(store.getState().notifications).toEqual([]);
    expect(store.getState().unreadCount).toBe(0);
    expect(store.getState().notificationsLoaded).toBe(false);
  });
});
