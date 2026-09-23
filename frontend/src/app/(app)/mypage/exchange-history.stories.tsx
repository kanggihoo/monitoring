import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { Exchange, ExchangePage } from "@/lib/order-api";
import { mockFetch } from "@/stories/mock-fetch";

import { ExchangeHistory } from "./exchange-history";

const EXCHANGES: Exchange[] = [
  {
    exchangeId: 12,
    direction: "KRW_TO_USD",
    rate: "1187.20",
    sendAmount: "1000000",
    feeRate: 0.1,
    fee: "1000",
    receiveAmount: "841.39",
    gameDate: "2020-08-14",
    tickSeq: 131,
    exchangedAt: "2026-09-16T03:04:00Z",
  },
  {
    exchangeId: 11,
    direction: "USD_TO_KRW",
    rate: "1185.50",
    sendAmount: "500.00",
    feeRate: 0.1,
    fee: "0.50",
    receiveAmount: "591,868",
    gameDate: "2020-08-13",
    tickSeq: 74,
    exchangedAt: "2026-09-15T06:22:00Z",
  },
];

const page = (content: Exchange[], overrides: Partial<ExchangePage> = {}) => ({
  "/api/v1/accounts/me/exchanges": {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    hasNext: false,
    ...overrides,
  } satisfies ExchangePage,
});

const meta = {
  title: "Screens/MyPage/ExchangeHistory",
  component: ExchangeHistory,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="flex h-[26rem] bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof ExchangeHistory>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 방향마다 보내는 통화와 받는 통화의 표기가 뒤집힌다 — 그 둘이 한 줄에 같이 있다. */
export const Default: Story = {
  beforeEach: () => mockFetch(page(EXCHANGES)),
};

export const Empty: Story = {
  beforeEach: () => mockFetch(page([])),
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/accounts/me/exchanges": 500 }),
};

export const Paged: Story = {
  beforeEach: () =>
    mockFetch(
      page(EXCHANGES, { totalElements: 31, totalPages: 2, hasNext: true })
    ),
};

export const Dark: Story = {
  beforeEach: () => mockFetch(page(EXCHANGES)),
  globals: { theme: "dark" },
};
