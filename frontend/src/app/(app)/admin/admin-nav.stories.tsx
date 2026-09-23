import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { AdminNav } from "./admin-nav";

const meta = {
  title: "Screens/Admin/AdminNav",
  component: AdminNav,
  tags: ["autodocs"],
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/admin/users" } },
  },
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof AdminNav>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 선택 표시는 무채색 `accent` 면이다 — 파랑은 하락과 혼동된다 (DESIGN.md). */
export const Users: Story = {};

export const Inquiries: Story = {
  parameters: { nextjs: { navigation: { pathname: "/admin/inquiries" } } },
};

/** 하위 경로에서도 그 탭이 켜진다 (접두사로 판정한다). */
export const UserDetail: Story = {
  parameters: { nextjs: { navigation: { pathname: "/admin/users/42" } } },
};

export const Dark: Story = { globals: { theme: "dark" } };
