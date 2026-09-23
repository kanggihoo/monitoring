import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { User } from "@/lib/auth";

import { UserMenu } from "./user-menu";

const USER: User = {
  id: 1,
  email: "hong@example.com",
  nickname: "홍길동",
  role: "USER",
  providers: ["google"],
};

// 메뉴가 포털로 붙으므로 autodocs 는 걸지 않는다 (`ui/dialog.stories.tsx` 와 같은 이유).
const meta = {
  title: "Layout/UserMenu",
  component: UserMenu,
  parameters: {
    layout: "centered",
    nextjs: { navigation: { pathname: "/" } },
  },
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { user: USER },
} satisfies Meta<typeof UserMenu>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 닉네임 첫 글자가 아바타다. 폰에서는 글자가 빠지고 아바타와 화살표만 남는다. */
export const Default: Story = {};

/** 이메일이 없는 사용자. 라벨 자리에 `이메일 미제공` 이 들어간다. */
export const WithoutEmail: Story = {
  args: {
    user: {
      ...USER,
      email: null,
      nickname: "이메일없음",
      providers: ["ssafy"],
    },
  },
};

/** 관리자에게만 `관리자` 항목이 붙는다. */
export const Admin: Story = {
  args: { user: { ...USER, nickname: "관리자", role: "ADMIN" } },
};

/** 긴 닉네임. 메뉴 폭(`w-48`)은 그대로고 트리거만 늘어난다. */
export const LongNickname: Story = {
  args: { user: { ...USER, nickname: "여덟글자가넘는닉네임입니다" } },
};

export const Dark: Story = { globals: { theme: "dark" } };
