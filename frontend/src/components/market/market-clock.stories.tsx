import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { MarketClock } from "./market-clock";

const meta = {
  title: "Screens/Dashboard/MarketClock",
  component: MarketClock,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="max-w-sm bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: {
    gameMonth: "2020-03",
    transitionLabel: "폐장까지",
    transitionSeconds: 2 * 3600 + 8 * 60 + 27,
    nextCloseSeconds: 17,
  },
} satisfies Meta<typeof MarketClock>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Trading: Story = {};

/** 12:00~12:10. 전환 시계가 폐장 대신 무료 종료를 센다 (기획 6.7.2). */
export const FeeFree: Story = {
  args: {
    transitionLabel: "수수료 무료 종료까지",
    transitionSeconds: 6 * 60 + 12,
  },
};

export const Briefing: Story = {
  args: {
    gameMonth: "2020-01",
    transitionLabel: "개장까지",
    transitionSeconds: 180,
    nextCloseSeconds: null,
  },
};

/** 종가가 확정되는 순간. 다음 종가 시각이 새로 오기 전까지 잠깐 보인다. */
export const Committing: Story = { args: { nextCloseSeconds: 0 } };

/** 개발 환경의 정지 상태 — 절대 시각이 없으므로 카운트다운을 그리지 않는다. */
export const Paused: Story = {
  args: { transitionSeconds: null, nextCloseSeconds: null },
};

export const Dark: Story = { globals: { theme: "dark" } };
