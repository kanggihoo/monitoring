import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { LoginRequiredDialog } from "./login-required-dialog";

// 포털로 붙으므로 autodocs 는 걸지 않는다 (`ui/dialog.stories.tsx` 와 같은 이유).
//
// 스토리의 셋이 이 모달이 실제로 받는 전부다 — 레일·탭바의 개인 진입점 세 곳(`lib/nav.ts`
// 의 `personal` 항목과 문의)이 각자의 이름과 경로를 넘긴다.
const meta = {
  title: "Layout/LoginRequiredDialog",
  component: LoginRequiredDialog,
  parameters: { layout: "centered" },
  args: {
    target: "/mypage",
    label: "내 계좌",
    open: true,
    onOpenChange: () => {},
  },
} satisfies Meta<typeof LoginRequiredDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 조사를 골라 붙여 `내 계좌는` 이 된다 — 괄호 표기(`은(는)`)가 나오면 안 된다.
 *
 * 지금 레일의 이름은 넷 다 받침이 없어 `은` 갈래가 실제로 뜨는 경우는 없다. 받침 있는
 * 이름이 생기면 그때 이 스토리에 한 줄 더한다 (`login-required-dialog.tsx` 의 `particle`).
 */
export const MyAccount: Story = {};

export const Review: Story = {
  args: { target: "/review", label: "복기" },
};

export const Inquiry: Story = {
  args: { target: "/mypage/inquiries", label: "문의" },
};

export const Dark: Story = { globals: { theme: "dark" } };
