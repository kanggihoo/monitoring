import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";

import { NotificationsMenu } from "@/components/notifications-menu";
import type {
  NotificationItem,
  NotificationPage,
} from "@/lib/notification-api";
import { sessionStore } from "@/stores/session-store";

/**
 * 배지가 사라지는 조건만 본다. **열 때마다 읽음 처리가 나가야 한다** — 예전에는 "이미
 * 보냈는가" 를 컴포넌트가 들고 있어서, 첫 열람 뒤 도착한 알림은 벨을 눌러도 읽음 처리가
 * 나가지 않고 배지가 그대로 남았다.
 */

const markNotificationsRead = vi.fn(() => Promise.resolve());

vi.mock("@/lib/notification-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/notification-api")>()),
  markNotificationsRead: () => markNotificationsRead(),
}));

const item = (notificationId: number): NotificationItem => ({
  notificationId,
  kind: "ORDER_FILLED",
  title: "예약 매수가 체결됐습니다",
  body: "엔비디아 3주를 $875.43에 매수했습니다.",
  referenceId: 9812,
  tickSeq: 137,
  gameDate: "2022-04-15",
  read: false,
  createdAt: "2026-09-21T05:31:40Z",
});

const page = (ids: number[]): NotificationPage => ({
  unreadCount: ids.length,
  content: ids.map(item),
  page: 0,
  size: 20,
  totalElements: ids.length,
  totalPages: 1,
  hasNext: false,
});

beforeEach(() => {
  // 전역(globals)을 켜지 않아 RTL 의 자동 정리가 걸리지 않는다 — 앞 테스트의 DOM 이 남으면
  // 벨이 둘이 된다.
  cleanup();
  markNotificationsRead.mockClear();
  sessionStore.getState().seedNotifications(page([1]));
});

it("알림이 새로 오면_두 번째로 열어도_읽음 처리가 나가고 배지가 사라진다", async () => {
  const user = userEvent.setup();
  render(<NotificationsMenu />);

  await user.click(screen.getByRole("button", { name: /알림/ }));
  expect(markNotificationsRead).toHaveBeenCalledOnce();
  expect(sessionStore.getState().unreadCount).toBe(0);

  await user.keyboard("{Escape}");
  // 새 알림 한 통. 배지 수는 서버가 보낸 값을 그대로 쓴다 (`noti.md`).
  sessionStore.getState().receiveEvent({
    type: "noti.created",
    tickSeq: 138,
    gameDate: "2022-04-15",
    payload: { notification: item(2), unreadCount: 1 },
  });

  await user.click(screen.getByRole("button", { name: /알림/ }));
  expect(markNotificationsRead).toHaveBeenCalledTimes(2);
  expect(sessionStore.getState().unreadCount).toBe(0);
});

it("신규 상장 알림은_그 종목을 연다", async () => {
  sessionStore.getState().seedNotifications({
    ...page([1]),
    content: [
      {
        ...item(1),
        kind: "NEW_LISTING",
        title: "스왑엑스 거래가 시작됐습니다",
        body: "오늘 신규 상장된 종목입니다.",
        referenceId: 133,
      },
    ],
  });
  const user = userEvent.setup();
  render(<NotificationsMenu />);

  await user.click(screen.getByRole("button", { name: /알림/ }));

  // 마켓까지만 데려가면 목록이 식별자 순이라 어느 것이 새 종목인지 사용자가 찾아야 한다.
  expect(
    screen.getByRole("link", { name: /스왑엑스 거래가 시작됐습니다/ })
  ).toHaveAttribute("href", "/?instrumentId=133");
});

it("읽지 않은 알림이 없으면_열어도_읽음 처리를 보내지 않는다", async () => {
  sessionStore.getState().seedNotifications({ ...page([1]), unreadCount: 0 });
  const user = userEvent.setup();
  render(<NotificationsMenu />);

  await user.click(screen.getByRole("button", { name: /알림/ }));

  expect(markNotificationsRead).not.toHaveBeenCalled();
});
