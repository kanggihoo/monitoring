import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { Valuation } from "@/lib/market-types";

import { PortfolioSummary } from "./portfolio-summary";

const base: Valuation = {
  asOfGameDate: "2022-04-15",
  asOfTickSeq: 137,
  usdKrwRate: "1243.50",
  totalValuationKrw: "112434000",
  krwCash: "45000000",
  usdCash: "12000.00",
  lockedKrw: "3000000",
  lockedUsd: "2553.83",
  holdingsValuationKrw: "52512000",
  startingBalanceKrw: "100000000",
  cumulativeReturn: 12.43,
  dailyReturn: -1.87,
};

const meta = {
  title: "Screens/Dashboard/PortfolioSummary",
  component: PortfolioSummary,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="max-w-md bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { valuation: base },
} satisfies Meta<typeof PortfolioSummary>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Profit: Story = {};

export const Loss: Story = {
  args: {
    valuation: {
      ...base,
      totalValuationKrw: "73600000",
      holdingsValuationKrw: "40000000",
      cumulativeReturn: -26.4,
      dailyReturn: -9.62,
    },
  },
};

/** 현금 100% 도 정상적인 전략이다. 빈 화면에서 매매를 부추기지 않는다 (기획 6장). */
export const AllCash: Story = {
  args: {
    valuation: {
      ...base,
      totalValuationKrw: "100000000",
      krwCash: "100000000",
      usdCash: "0.00",
      lockedKrw: "0",
      lockedUsd: "0.00",
      holdingsValuationKrw: "0",
      cumulativeReturn: 0,
      dailyReturn: 0,
    },
  },
};

export const Dark: Story = { globals: { theme: "dark" } };
