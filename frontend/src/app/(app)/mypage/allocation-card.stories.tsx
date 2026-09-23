import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  INSTRUMENTS,
  POSITIONS,
  VALUATION,
} from "@/components/market/fixtures";

import { allocation, AllocationCard } from "./allocation-card";

const meta = {
  title: "Screens/MyPage/AllocationCard",
  component: AllocationCard,
  tags: ["autodocs"],
  args: {
    buckets: allocation(POSITIONS, INSTRUMENTS),
    valuation: VALUATION,
  },
} satisfies Meta<typeof AllocationCard>;

export default meta;

type Story = StoryObj<typeof meta>;

// 실제 자리는 자산 현황 탭 오른쪽 기둥(`22rem`)이다. 폭을 맞춰야 갈래 칸이 좁아지는
// 모습을 여기서 볼 수 있다 — 넓게 두면 min-w-16 이 걸리지 않아 실제와 다르게 보인다.
const wrap: Story["decorators"] = [
  (Story) => (
    <div className="w-[22rem] bg-background p-6">
      <Story />
    </div>
  ),
];

export const Default: Story = { decorators: wrap };

/** 보유가 없으면 현금 100% 한 칸이다. 다른 세 갈래는 0 이라 걸러진다. */
export const CashOnly: Story = {
  decorators: wrap,
  args: { buckets: allocation([], INSTRUMENTS) },
};

/** 한 갈래에 몰린 경우. 좁은 칸이 `min-w-16` 에 걸려 비중대로 줄지 않는다. */
export const Concentrated: Story = {
  decorators: wrap,
  args: {
    buckets: [
      { category: "CASH", weight: 2.5 },
      { category: "NASDAQ", weight: 95.4 },
      { category: "KRX", weight: 1.4 },
      { category: "CRYPTO", weight: 0.7 },
    ],
  },
};

/** 최초 공개 전에는 환율이 오지 않는다 — 곁말에서 환율만 빠지고 평가액은 남는다. */
export const NoExchangeRate: Story = {
  decorators: wrap,
  args: { valuation: { ...VALUATION, usdKrwRate: null } },
};

/** 계좌가 아직 도착하지 않은 상태. 곁말 줄 전체가 빠진다. */
export const NoValuation: Story = {
  decorators: wrap,
  args: { valuation: null },
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
