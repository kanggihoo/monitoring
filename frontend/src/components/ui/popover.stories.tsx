import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover";

// 포털로 붙어 열려 있어야 보이는 컴포넌트라 autodocs 는 걸지 않는다.
//
// 쓰는 곳은 헤더의 알림 센터 하나이고 `align="end"` 로만 연다 — `side` 를 바꿔 다는 곳이
// 없어 방향 스토리는 두지 않는다.
const meta = {
  title: "UI/Popover",
  component: Popover,
  parameters: { layout: "centered" },
  args: { defaultOpen: true },
} satisfies Meta<typeof Popover>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => (
    <Popover {...args}>
      <PopoverTrigger render={<Button variant="outline" size="sm" />}>
        알림
      </PopoverTrigger>
      <PopoverContent align="end">
        <PopoverHeader>
          <PopoverTitle>알림</PopoverTitle>
          <PopoverDescription>
            예약 체결 · 자동 취소 · 청약 · 수상 · 공지
          </PopoverDescription>
        </PopoverHeader>
      </PopoverContent>
    </Popover>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
