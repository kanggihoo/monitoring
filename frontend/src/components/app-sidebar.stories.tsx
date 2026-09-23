import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { AppSidebar } from "./app-sidebar";

const meta = {
  title: "Layout/AppSidebar",
  component: AppSidebar,
  tags: ["autodocs"],
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/" } },
  },
  decorators: [
    (Story) => (
      <div className="h-screen bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: { isAdmin: false, isLoggedIn: true },
} satisfies Meta<typeof AppSidebar>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 기본. `내 계좌` 타일에 마우스를 올리면 서브탭 목차가 **오른쪽으로** 펼쳐진다 —
 * 타일은 그대로 `/mypage` 링크이고, 목차는 그 위에 얹히는 지름길이다.
 */
export const User: Story = {};

/** 관리자에게만 마지막 타일이 붙는다. */
export const Admin: Story = { args: { isAdmin: true } };

/**
 * 미인증. **개인 진입점(내 계좌·복기·문의)이 사라지지 않는다** — 무엇을 할 수 있는 서비스인지
 * 알 수 있어야 하므로 자리를 남기고 로그인 화면으로 보낸다 (ADR-0005). 공개 항목은 제 경로로 간다.
 */
export const Unauthenticated: Story = { args: { isLoggedIn: false } };

export const Dark: Story = { globals: { theme: "dark" } };
