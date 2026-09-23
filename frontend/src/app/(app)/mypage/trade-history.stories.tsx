import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import type { Execution, ExecutionPage } from "@/lib/order-api";
import { mockFetch } from "@/stories/mock-fetch";

import { TradeHistory } from "./trade-history";

const EXECUTIONS: Execution[] = [
  {
    executionId: 301,
    orderId: null,
    orderType: "MARKET",
    side: "BUY",
    instrumentId: 1,
    quantity: 10,
    executedPrice: "54200",
    currency: "KRW",
    amount: "542000",
    // 매수는 언제나 0 이다 (`ord.md`).
    appliedFeeRate: 0,
    fee: "0",
    realizedPnl: null,
    gameDate: "2020-08-14",
    tickSeq: 137,
    executedAt: "2026-09-16T03:12:00Z",
  },
  {
    executionId: 300,
    orderId: 91,
    orderType: "LIMIT",
    side: "SELL",
    instrumentId: 2,
    quantity: 5,
    executedPrice: "78300",
    currency: "KRW",
    amount: "391500",
    appliedFeeRate: 0.15,
    fee: "587",
    realizedPnl: "42150",
    gameDate: "2020-08-14",
    tickSeq: 131,
    executedAt: "2026-09-16T03:04:00Z",
  },
  {
    executionId: 299,
    orderId: null,
    orderType: "MARKET",
    side: "SELL",
    instrumentId: 11,
    quantity: 2,
    executedPrice: "118.42",
    currency: "USD",
    amount: "236.84",
    appliedFeeRate: 0.15,
    fee: "0.36",
    realizedPnl: "-18.20",
    gameDate: "2020-08-13",
    tickSeq: 88,
    executedAt: "2026-09-15T07:41:00Z",
  },
];

const page = (
  content: Execution[],
  overrides: Partial<ExecutionPage> = {}
) => ({
  "/api/v1/executions": {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    hasNext: false,
    ...overrides,
  } satisfies ExecutionPage,
});

const meta = {
  title: "Screens/MyPage/TradeHistory",
  component: TradeHistory,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="flex h-[30rem] bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS },
} satisfies Meta<typeof TradeHistory>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 매수·매도, 원화·달러, 실현손익 있는 줄과 없는 줄이 한 화면에 있다. */
export const Default: Story = {
  beforeEach: () => mockFetch(page(EXECUTIONS)),
};

/** 거래한 적이 없을 때. 첫 조회 전과 문구를 가른다. */
export const Empty: Story = {
  beforeEach: () => mockFetch(page([])),
};

/** 조회가 깨졌을 때는 그 이유를 그 자리에 적는다. */
export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/executions": 500 }),
};

/** 여러 페이지. 표 아래에 앞뒤 이동이 붙는다 — 주소는 바꾸지 않는다. */
export const Paged: Story = {
  beforeEach: () =>
    mockFetch(
      page(EXECUTIONS, { totalElements: 48, totalPages: 3, hasNext: true })
    ),
};

export const Dark: Story = {
  beforeEach: () => mockFetch(page(EXECUTIONS)),
  globals: { theme: "dark" },
};
