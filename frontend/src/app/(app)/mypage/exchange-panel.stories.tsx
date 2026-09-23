import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { clockSnapshot, VALUATION } from "@/components/market/fixtures";
import type { Phase } from "@/lib/market-types";
import type { ExchangePreview } from "@/lib/order-api";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { ExchangePanel } from "./exchange-panel";

/** 요율은 항상 0.05% 이고 무료 타임 대상이 아니다 (`acc.md`). */
const PREVIEW: ExchangePreview = {
  direction: "KRW_TO_USD",
  rate: "1187.20",
  sendAmount: "1000000",
  feeRate: 0.05,
  fee: "500",
  receiveAmount: "842.03",
  availableCash: "12500000",
};

function Seed({ phase = "TRADING" }: { phase?: Phase }) {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot({ phase }));
    sessionStore.getState().setConnected(true);
    tickStore.setState({ valuation: VALUATION, usdKrwRate: "1187.20" });
  }, [phase]);
  return null;
}

const meta = {
  title: "Screens/MyPage/ExchangePanel",
  component: ExchangePanel,
  tags: ["autodocs"],
  beforeEach: () =>
    mockFetch({ "/api/v1/accounts/me/exchanges/preview": PREVIEW }),
  decorators: [
    (Story) => (
      <div className="w-88 max-w-full bg-background p-6">
        <Seed />
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof ExchangePanel>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 금액을 치면 잠깐 뒤 미리보기를 묻고, 그 답이 와야 환전 버튼이 열린다 — 화면이 어림치를
 * 대신 그리면 사용자가 그 어림치를 보고 결정하게 된다 (`acc.md`).
 */
export const Trading: Story = {};

/**
 * `TRADING` 이 아닐 때. 입력만 막고 이유를 적지 않으면 고장으로 읽히므로 왜 못 하는지를
 * 화면이 말한다.
 */
export const Closed: Story = {
  decorators: [
    (Story) => (
      <div className="w-88 max-w-full bg-background p-6">
        <Seed phase="REVIEW_ONLY" />
        <Story />
      </div>
    ),
  ],
};

/** 미리보기가 실패하면 버튼이 닫힌 채로 남는다. 어림치로 되돌아가지 않는다. */
export const PreviewFailed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/accounts/me/exchanges/preview": 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
