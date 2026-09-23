import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { DeleteInquiry } from "./delete-inquiry";

const meta = {
  title: "Screens/Inquiry/DeleteInquiry",
  component: DeleteInquiry,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/mypage/inquiries/7" } } },
  decorators: [
    (Story) => (
      <div className="w-[30rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { inquiryId: 7 },
  beforeEach: () => mockFetch({ "/api/v1/inquiries/7": {} }),
} satisfies Meta<typeof DeleteInquiry>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 되돌릴 수 없으므로 확인 단계를 둔다. 답변이 달린 문의에는 이 자리를 아예 두지 않는다 —
 * 누를 수 있는 버튼을 두고 거절하는 것은 안내가 아니다.
 */
export const Default: Story = {};

/** 열어 둔 사이 답변이 달렸을 때. 지워졌다고 잘못 믿지 않도록 실패 사실을 남긴다. */
export const Rejected: Story = {
  beforeEach: () => mockFetch({ "/api/v1/inquiries/7": 409 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
