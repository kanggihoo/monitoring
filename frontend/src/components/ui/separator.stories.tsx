import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Separator } from "@/components/ui/separator";

const meta = {
  title: "UI/Separator",
  component: Separator,
  tags: ["autodocs"],
} satisfies Meta<typeof Separator>;

export default meta;

type Story = StoryObj<typeof meta>;

/** density 7 — 카드 여백보다 1px 구분선을 먼저 쓴다 (DESIGN.md). */
const Sample = () => (
  <div className="flex flex-col gap-3 bg-card p-6 text-sm text-foreground">
    <span>총 자산 10,240,000원</span>
    <Separator />
    <span>현금 3,120,000원</span>
    <div className="flex h-5 items-center gap-3">
      <span>나스닥</span>
      <Separator orientation="vertical" />
      <span>코스피</span>
      <Separator orientation="vertical" />
      <span>코인</span>
    </div>
  </div>
);

export const Default: Story = { render: Sample };

export const Dark: Story = { render: Sample, globals: { theme: "dark" } };
