import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { ThemeToggle, ThemeToggleRow } from "./theme-toggle";

/**
 * 실제로 `<html>` 의 클래스와 `localStorage` 를 바꾼다 — 누르면 스토리북 툴바의 테마와
 * 어긋날 수 있다. 그게 이 컴포넌트가 하는 일이고, 원래대로 되돌리려면 툴바에서 다시 고른다.
 */
const meta = {
  title: "Common/ThemeToggle",
  component: ThemeToggle,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof ThemeToggle>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 헤더에 놓는 아이콘 버튼. 라이트면 해, 다크면 달이다. */
export const IconButton: Story = {};

/** 폰 `더보기` 시트의 한 줄. 미로그인도 닿아야 하므로 사용자 메뉴가 아니라 시트에 있다. */
export const Row: Story = {
  render: () => (
    <div className="w-72 rounded-lg ring-1 ring-border">
      <ThemeToggleRow />
    </div>
  ),
};

export const Dark: Story = { globals: { theme: "dark" } };
