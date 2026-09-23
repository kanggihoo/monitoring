import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { InquiryStatusBadge } from "@/components/inquiry-status-badge";

/** 문의 상태는 답변 전·후 둘뿐이다. 사용자 목록과 관리자 목록이 같은 뱃지를 쓴다. */
const meta = {
  title: "Common/InquiryStatusBadge",
  component: InquiryStatusBadge,
  tags: ["autodocs"],
  argTypes: {
    status: { control: "inline-radio", options: ["OPEN", "ANSWERED"] },
  },
  args: { status: "OPEN" },
} satisfies Meta<typeof InquiryStatusBadge>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Open: Story = {};
export const Answered: Story = { args: { status: "ANSWERED" } };

/** 색이 아니라 글자가 값을 말한다 — 나란히 두고 읽히는지 본다. */
export const Both: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex gap-2">
      <InquiryStatusBadge status="OPEN" />
      <InquiryStatusBadge status="ANSWERED" />
    </div>
  ),
};

export const Dark: Story = { ...Both, globals: { theme: "dark" } };
