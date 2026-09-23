import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { TourReplayButton } from "./tour-replay-button";

const meta = {
  title: "Common/TourReplayButton",
  component: TourReplayButton,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/" } } },
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof TourReplayButton>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 마켓 화면에는 튜토리얼이 있으므로 버튼이 보인다. */
export const OnMarket: Story = {};

/**
 * 튜토리얼이 없는 화면에서는 **아무것도 그리지 않는다** — 눌러도 아무 일이 없는 버튼은
 * 고장으로 읽힌다. 이 스토리는 빈 화면이 정답이다.
 */
export const Elsewhere: Story = {
  parameters: { nextjs: { navigation: { pathname: "/mypage" } } },
};

export const Dark: Story = { globals: { theme: "dark" } };
