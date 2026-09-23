import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { rememberReturnPath } from "@/lib/login-return";

import { LoginButtons } from "./login-buttons";

const meta = {
  title: "Screens/Login/LoginButtons",
  component: LoginButtons,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/login" } } },
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof LoginButtons>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 주소를 직접 쳐서 들어온 경우. 돌아갈 자리가 없어 버튼만 남는다. */
export const Default: Story = {
  beforeEach: () => {
    window.sessionStorage.clear();
  },
};

/**
 * 앱 안에서 로그인을 시작했다가 온 경우. **복귀 경로가 아니라 취소 경로다** — 복귀 경로로
 * 보내면 프록시가 다시 이 화면으로 돌려보내 제자리를 맴돈다.
 */
export const WithCancelPath: Story = {
  beforeEach: () => {
    window.sessionStorage.clear();
    // 실제로는 안내 모달이 담는다. 지금 주소가 취소 경로로 들어간다.
    rememberReturnPath("/mypage");
  },
};

export const Dark: Story = {
  ...WithCancelPath,
  globals: { theme: "dark" },
};
