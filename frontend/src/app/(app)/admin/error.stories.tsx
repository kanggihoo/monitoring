import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import AdminError from "./error";

/** 관리자 묶음의 복구 경계. 세 화면이 같은 API 를 보므로 경계도 하나다. */
const meta = {
  title: "Screens/Error/AdminError",
  component: AdminError,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background">
        <Story />
      </div>
    ),
  ],
  args: { error: new Error("관리자 조회 실패"), reset: () => {} },
} satisfies Meta<typeof AdminError>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
