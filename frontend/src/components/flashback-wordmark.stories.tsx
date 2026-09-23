import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { FlashbackWordmark } from "./flashback-wordmark";

const meta = {
  title: "Common/FlashbackWordmark",
  component: FlashbackWordmark,
  tags: ["autodocs"],
  args: { className: "h-6" },
} satisfies Meta<typeof FlashbackWordmark>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 높이만 주면 폭은 비율대로 따라온다 (`w-auto`). */
export const Default: Story = {
  decorators: [
    (Story) => (
      <div className="bg-background p-6 text-foreground">
        <Story />
      </div>
    ),
  ],
};

/** 색은 `currentColor` 다 — 놓는 자리의 글자색을 그대로 따른다. */
export const Colors: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-col gap-4 bg-background p-6">
      <FlashbackWordmark className="h-6 text-foreground" />
      <FlashbackWordmark className="h-6 text-muted-foreground" />
      <FlashbackWordmark className="h-6 text-primary" />
      <div className="rounded-lg bg-surface-feature p-4">
        <FlashbackWordmark className="h-6 text-surface-feature-foreground" />
      </div>
    </div>
  ),
};

/** 헤더(20px)부터 표지(64px)까지. 작은 쪽에서 획이 뭉치지 않는지 본다. */
export const Sizes: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-col items-start gap-4 bg-background p-6 text-foreground">
      {["h-5", "h-6", "h-10", "h-16"].map((size) => (
        <FlashbackWordmark key={size} className={size} />
      ))}
    </div>
  ),
};

export const Dark: Story = { ...Colors, globals: { theme: "dark" } };
