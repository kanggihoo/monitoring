import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { SignalLight } from "./signal-light";

const meta = {
  title: "Common/SignalLight",
  component: SignalLight,
  tags: ["autodocs"],
  argTypes: {
    level: { control: "inline-radio", options: ["good", "fair", "caution"] },
  },
  args: { level: "good", question: "이 회사가 성장하고 있는가?" },
} satisfies Meta<typeof SignalLight>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Good: Story = {};

export const Fair: Story = {
  args: { level: "fair", question: "재무적으로 안전한가?" },
};

export const Caution: Story = {
  args: { level: "caution", question: "현재 가격이 비싼가?" },
};

/** 초보자는 신호등만 봐도 되고, 숙련자는 지표 원값을 본다 (기획 9장). */
export const AllLevels: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex w-72 flex-col gap-2 bg-card p-6">
      <SignalLight level="good" question="이 회사가 성장하고 있는가?" />
      <SignalLight level="fair" question="재무적으로 안전한가?" />
      <SignalLight level="caution" question="현재 가격이 비싼가?" />
    </div>
  ),
};

export const Dark: Story = { ...AllLevels, globals: { theme: "dark" } };
