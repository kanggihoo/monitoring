import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { ScrollArea } from "@/components/ui/scroll-area";

const meta = {
  title: "UI/ScrollArea",
  component: ScrollArea,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof ScrollArea>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 막대는 내용이 넘칠 때만 잡힌다. 높이를 주지 않으면 스크롤 자체가 생기지 않는다. */
export const Vertical: Story = {
  render: () => (
    <ScrollArea className="h-56 w-64 rounded-lg ring-1 ring-border">
      <ul className="p-3 text-sm">
        {Array.from({ length: 40 }, (_, index) => (
          <li key={index} className="py-1">
            체결 #{index + 1} · 삼성전자 10주
          </li>
        ))}
      </ul>
    </ScrollArea>
  ),
};

// 가로 스크롤 스토리는 없다. `ScrollArea` 가 세로 막대만 렌더하므로(`scroll-area.tsx`)
// 가로로는 볼 것이 없다 — 필요해지면 그때 `ScrollBar` 를 노출한다.

export const Dark: Story = { ...Vertical, globals: { theme: "dark" } };
