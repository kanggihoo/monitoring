import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { AbsenceSummaryDialog } from "./absence-summary";

const SUMMARY = {
  absent: true,
  fromTickSeq: 412,
  toTickSeq: 501,
  fromGameDate: "2022-02-01",
  toGameDate: "2022-04-15",
  filledOrderCount: 2,
  valuationChangeKrw: "-1240000",
  returnChange: -1.12,
  eventCount: 3,
} as const;

const meta = {
  title: "Layout/AbsenceSummaryDialog",
  component: AbsenceSummaryDialog,
  tags: ["autodocs"],
  args: {
    summary: SUMMARY,
    open: true,
    onOpenChange: () => {},
    onAcknowledge: () => {},
  },
} satisfies Meta<typeof AbsenceSummaryDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 출근 전 걸어 둔 예약 주문이 장중에 체결되는 것이 정상 플레이다 (기획 3장). */
export const Default: Story = {};

/** 자리를 비운 사이 오른 경우. "비워도 지지 않는다" 가 이 기능의 요점이다. */
export const Gained: Story = {
  args: {
    summary: {
      ...SUMMARY,
      valuationChangeKrw: "3820000",
      returnChange: 3.44,
      filledOrderCount: 4,
      eventCount: 5,
    },
  },
};

export const Dark: Story = { globals: { theme: "dark" } };
