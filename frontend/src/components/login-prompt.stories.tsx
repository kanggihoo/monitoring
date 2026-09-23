import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { LoginPrompt } from "@/components/login-prompt";

/**
 * 개인 기능의 로그인 안내. **진입점을 지우지 않고 이것으로 바꾼다** — 빈 값으로 두면 데이터가
 * 없는 것과 권한이 없는 것을 구별할 수 없고, 아무 일도 일어나지 않으면 고장으로 오해한다
 * (ADR-0005).
 */
const meta = {
  title: "Common/LoginPrompt",
  component: LoginPrompt,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="max-w-sm rounded-xl border border-border bg-card p-4">
        <Story />
      </div>
    ),
  ],
  args: { message: "내 자산은 로그인 후에 보입니다" },
} satisfies Meta<typeof LoginPrompt>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 행동까지 문구에 담는 자리 — 채팅 입력창·주문 버튼처럼 무엇을 하려던 것인지가 분명할 때. */
export const WithAction: Story = {
  args: {
    message: "로그인하면 대화에 참여할 수 있습니다",
    label: "로그인하고 쓰기",
  },
};

export const Dark: Story = { globals: { theme: "dark" } };
