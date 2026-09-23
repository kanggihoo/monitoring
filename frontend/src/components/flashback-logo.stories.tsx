import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { FlashbackLogo } from "./flashback-logo";

const meta = {
  title: "Common/FlashbackLogo",
  component: FlashbackLogo,
  tags: ["autodocs"],
  args: { className: "size-16" },
} satisfies Meta<typeof FlashbackLogo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 마크는 `currentColor` 를 따른다 — 다크에서도 사라지지 않는다. */
export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
