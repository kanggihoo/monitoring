import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import type { OpenOrder, OpenOrders } from "@/lib/order-api";
import { mockFetch } from "@/stories/mock-fetch";

import { ReservedOrders } from "./reserved-orders";

const ORDERS: OpenOrder[] = [
  {
    orderId: 91,
    side: "BUY",
    instrumentId: 1,
    quantity: 10,
    limitPrice: "52000",
    currency: "KRW",
    // 매수에 수수료가 없어 잠금액이 곧 체결 금액이다.
    lockedAmount: "520000",
    lockedQuantity: null,
    expiresAt: "2026-09-16T08:00:00Z",
    createdGameDate: "2020-08-14",
    createdAt: "2026-09-16T02:40:00Z",
  },
  {
    orderId: 92,
    side: "SELL",
    instrumentId: 2,
    quantity: 5,
    limitPrice: "81000",
    currency: "KRW",
    lockedAmount: null,
    lockedQuantity: 5,
    expiresAt: "2026-09-16T08:00:00Z",
    createdGameDate: "2020-08-14",
    createdAt: "2026-09-16T02:51:00Z",
  },
  {
    orderId: 93,
    side: "BUY",
    instrumentId: 11,
    quantity: 3,
    limitPrice: "112.50",
    currency: "USD",
    lockedAmount: "337.50",
    lockedQuantity: null,
    expiresAt: "2026-09-16T08:00:00Z",
    createdGameDate: "2020-08-14",
    createdAt: "2026-09-16T03:02:00Z",
  },
];

const orders = (list: OpenOrder[]) => ({
  "/api/v1/orders": { asOfTickSeq: 137, orders: list } satisfies OpenOrders,
});

const meta = {
  title: "Screens/MyPage/ReservedOrders",
  component: ReservedOrders,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="flex h-[26rem] bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS },
} satisfies Meta<typeof ReservedOrders>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 매수는 금액이, 매도는 수량이 잠긴다. `정정` 을 누르면 **줄 안에서** 입력이 열린다 —
 * 모달을 띄우면 지금 걸린 조건이 가려진다.
 */
export const Default: Story = {
  beforeEach: () => mockFetch(orders(ORDERS)),
};

/** 폐장 뒤에는 서버가 항상 빈 목록을 준다 (`ord.md`). 화면이 phase 를 보고 거르지 않는다. */
export const Empty: Story = {
  beforeEach: () => mockFetch(orders([])),
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/orders": 500 }),
};

export const Dark: Story = {
  beforeEach: () => mockFetch(orders(ORDERS)),
  globals: { theme: "dark" },
};
