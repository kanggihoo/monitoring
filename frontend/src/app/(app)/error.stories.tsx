import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import AppError from "./error";

/**
 * 셸 **안**의 복구 경계. 레일과 헤더는 남고 본문만 이 화면이 된다 — 스토리에는 셸이 없으니
 * 본문 자리만 보인다.
 */
const meta = {
  title: "Screens/Error/AppError",
  component: AppError,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background">
        <Story />
      </div>
    ),
  ],
  args: { error: new Error("조회 실패"), reset: () => {} },
} satisfies Meta<typeof AppError>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
