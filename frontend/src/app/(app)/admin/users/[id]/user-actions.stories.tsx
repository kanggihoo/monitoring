import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { UserActions } from "./user-actions";

const meta = {
  title: "Screens/Admin/UserActions",
  component: UserActions,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/admin/users/42" } } },
  decorators: [
    (Story) => (
      <div className="w-[32rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { userId: 42, role: "USER", isSelf: false },
  beforeEach: () => mockFetch({ "/api/v1/admin/users/": {} }),
} satisfies Meta<typeof UserActions>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 일반 사용자. 승격 버튼이 나온다. */
export const RegularUser: Story = {};

/** 관리자. 강등 버튼으로 바뀐다. */
export const AdminUser: Story = { args: { role: "ADMIN" } };

/**
 * 자기 자신. 강등 버튼을 감춘다 — 백엔드도 409 로 막지만, 여기서 감추는 것은 안내일 뿐
 * 방어선이 아니다.
 */
export const Self: Story = { args: { role: "ADMIN", isSelf: true } };

/** 서버가 거절했을 때. 무엇이 실패했는지 그 자리에 남긴다. */
export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/admin/users/": 409 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
