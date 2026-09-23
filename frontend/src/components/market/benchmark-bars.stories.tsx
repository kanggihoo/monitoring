import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { BenchmarkBars } from "./benchmark-bars";

const meta = {
  title: "Screens/Review/BenchmarkBars",
  component: BenchmarkBars,
  tags: ["autodocs"],
  args: {
    benchmarks: [
      { label: "QQQ 단순 보유", returnRate: 62.1 },
      { label: "KODEX 200 단순 보유", returnRate: 18.4 },
      { label: "나", returnRate: 84.2 },
    ],
  },
  decorators: [
    (Story) => (
      <div className="w-96">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof BenchmarkBars>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Winning: Story = {};

/** 기준선에 못 미친 시즌. 음수는 0 축 왼쪽으로 뻗는다. */
export const Losing: Story = {
  args: {
    benchmarks: [
      { label: "QQQ 단순 보유", returnRate: 62.1 },
      { label: "KODEX 200 단순 보유", returnRate: 18.4 },
      { label: "나", returnRate: -24.7 },
    ],
  },
};
