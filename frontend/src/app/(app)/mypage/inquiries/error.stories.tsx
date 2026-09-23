import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import InquiriesError from "./error";

/** 문의 묶음의 복구 경계. 목록이 비어 보이는 것과 못 불러온 것을 구별한다. */
const meta = {
  title: "Screens/Error/InquiriesError",
  component: InquiriesError,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background">
        <Story />
      </div>
    ),
  ],
  args: { error: new Error("문의 조회 실패"), reset: () => {} },
} satisfies Meta<typeof InquiriesError>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
