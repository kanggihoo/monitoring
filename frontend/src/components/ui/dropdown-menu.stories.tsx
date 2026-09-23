import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

// 포털로 붙는 팝업이라 autodocs 는 걸지 않는다 (`dialog.stories.tsx` 와 같은 이유).
//
// 쓰는 곳은 헤더의 사용자 메뉴와 레일의 `내 계좌` 목차 둘뿐이라, 둘이 실제로 쓰는 조각만
// 담는다. 체크·라디오·하위 메뉴·단축키·`variant="destructive"` 는 아직 쓰는 곳이 없다.
const meta = {
  title: "UI/DropdownMenu",
  component: DropdownMenu,
  parameters: { layout: "centered" },
  args: { defaultOpen: true },
} satisfies Meta<typeof DropdownMenu>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 헤더의 사용자 메뉴 그대로 — 라벨 · 항목 · 구분선. */
export const Default: Story = {
  render: (args) => (
    <DropdownMenu {...args}>
      <DropdownMenuTrigger render={<Button variant="outline" size="sm" />}>
        메뉴
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        {/* Base UI 의 GroupLabel 은 Group 안에서만 렌더된다. */}
        <DropdownMenuGroup>
          <DropdownMenuLabel>hong@example.com</DropdownMenuLabel>
          <DropdownMenuItem>마이페이지</DropdownMenuItem>
          <DropdownMenuItem>관리자</DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        {/* 왕복 중에는 잠근다 (`user-menu.tsx` 의 로그아웃). */}
        <DropdownMenuItem disabled>로그아웃</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
