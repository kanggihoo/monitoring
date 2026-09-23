import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { NotificationItem } from "@/lib/notification-api";
import { sessionStore } from "@/stores/session-store";

import { NotificationsMenu } from "./notifications-menu";

/**
 * 여덟 유형을 모두 덮는다. 앞의 넷은 내 계좌의 사건이고, 뒤의 넷이 전원 공지다 —
 * 그중 `ADMIN_NOTICE`·`URGENT_NOTICE` 만 **사람이 보낸 말**이라 제목 앞에 표시가 붙는다
 * (`NOTICE_LABEL`). 긴급은 그 위에 글자색까지 바뀐다.
 */
const ITEMS: NotificationItem[] = [
  {
    notificationId: 8,
    kind: "URGENT_NOTICE",
    title: "17:00 정산 발표가 10분 늦어집니다",
    body: "재생 지연으로 오늘 정산 발표가 17:10 으로 미뤄집니다.",
    referenceId: null,
    tickSeq: null,
    gameDate: "2020-08-14",
    read: false,
    createdAt: "2026-09-16T03:40:00Z",
  },
  {
    notificationId: 7,
    kind: "ADMIN_NOTICE",
    title: "채팅 이용 수칙을 안내드립니다",
    body: "욕설·도배는 경고 없이 삭제됩니다.",
    referenceId: null,
    tickSeq: null,
    gameDate: "2020-08-14",
    read: false,
    createdAt: "2026-09-16T03:30:00Z",
  },
  {
    notificationId: 6,
    kind: "IPO_NOTICE",
    title: "캐니언모터스 청약 마감이 임박했습니다",
    body: "이번 거래일이 지나면 청약할 수 없습니다.",
    referenceId: null,
    tickSeq: 133,
    gameDate: "2020-08-14",
    read: false,
    createdAt: "2026-09-16T03:20:00Z",
  },
  {
    notificationId: 5,
    kind: "ORDER_FILLED",
    title: "예약 주문이 체결됐습니다",
    body: "삼성전자 10주를 54,200원에 샀습니다.",
    referenceId: 91,
    tickSeq: 137,
    gameDate: "2020-08-14",
    read: false,
    createdAt: "2026-09-16T03:12:00Z",
  },
  {
    notificationId: 4,
    kind: "IPO_RESULT",
    title: "청약 결과가 나왔습니다",
    body: "카카오게임즈 3주를 배정받았습니다.",
    referenceId: 12,
    tickSeq: 130,
    gameDate: "2020-08-14",
    read: false,
    createdAt: "2026-09-16T02:41:00Z",
  },
  {
    notificationId: 3,
    kind: "ORDER_EXPIRED",
    title: "예약 주문이 자동 취소됐습니다",
    body: "SK하이닉스 5주 매도 주문이 폐장으로 취소됐습니다.",
    referenceId: 88,
    tickSeq: null,
    gameDate: "2020-08-13",
    read: true,
    createdAt: "2026-09-15T08:00:00Z",
  },
  {
    notificationId: 2,
    kind: "AWARD",
    title: "일간 수익률 3위에 올랐습니다",
    body: "8월 13일 기준 +4.21% 입니다.",
    referenceId: null,
    tickSeq: null,
    gameDate: "2020-08-13",
    read: true,
    createdAt: "2026-09-15T07:50:00Z",
  },
  {
    notificationId: 1,
    kind: "NOTICE",
    title: "수수료 무료 타임이 시작됩니다",
    body: "잠시 뒤 10분 동안 수수료를 받지 않습니다.",
    referenceId: null,
    tickSeq: 96,
    gameDate: "2020-08-13",
    read: true,
    createdAt: "2026-09-15T03:00:00Z",
  },
];

/** 목록·배지는 세션 스토어가 든다. 스토리가 그 자리에 값을 심는다. */
function Seed({
  items,
  unread,
  loaded = true,
  hasNext = false,
}: {
  items: NotificationItem[];
  unread: number;
  loaded?: boolean;
  hasNext?: boolean;
}) {
  useEffect(() => {
    sessionStore.setState({
      notifications: items,
      unreadCount: unread,
      notificationsLoaded: loaded,
      notificationsHasNext: hasNext,
    });
  }, [items, unread, loaded, hasNext]);
  return null;
}

// 팝오버가 포털로 붙으므로 autodocs 는 걸지 않는다 (`ui/dialog.stories.tsx` 와 같은 이유).
const meta = {
  title: "Layout/NotificationsMenu",
  component: NotificationsMenu,
  parameters: { layout: "centered" },
} satisfies Meta<typeof NotificationsMenu>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 벨을 누르면 열리고, 그 순간 배지가 내려간다 — 열람 단위가 알림 센터다 (기획 15.1.4).
 *
 * 여덟 유형이 한 목록에 섞여 있다. **관리자가 보낸 둘만 제목 앞에 표시를 단다** — 게임이
 * 내는 공지(체결·공모·무료 타임)와 섞이면 사람이 보낸 말인지 알 수 없다.
 */
export const Unread: Story = {
  render: () => (
    <div className="bg-background p-6">
      <Seed items={ITEMS} unread={5} />
      <NotificationsMenu />
    </div>
  ),
};

/** 100 건이 넘으면 배지가 `99+` 로 멈춘다. */
export const ManyUnread: Story = {
  render: () => (
    <div className="bg-background p-6">
      <Seed items={ITEMS} unread={137} hasNext />
      <NotificationsMenu />
    </div>
  ),
};

/** 다 읽은 뒤. 읽지 않은 줄에만 남던 바탕색이 사라진다. */
export const AllRead: Story = {
  render: () => (
    <div className="bg-background p-6">
      <Seed items={ITEMS.map((item) => ({ ...item, read: true }))} unread={0} />
      <NotificationsMenu />
    </div>
  ),
};

/** 비었을 때. 아직 못 불러온 것과 정말 없는 것을 문구로 가른다. */
export const Empty: Story = {
  render: () => (
    <div className="bg-background p-6">
      <Seed items={[]} unread={0} />
      <NotificationsMenu />
    </div>
  ),
};

export const Loading: Story = {
  render: () => (
    <div className="bg-background p-6">
      <Seed items={[]} unread={0} loaded={false} />
      <NotificationsMenu />
    </div>
  ),
};

export const Dark: Story = { ...Unread, globals: { theme: "dark" } };
