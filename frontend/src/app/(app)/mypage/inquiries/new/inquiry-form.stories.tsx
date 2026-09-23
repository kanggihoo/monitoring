import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { InquiryForm } from "./inquiry-form";

const meta = {
  title: "Screens/Inquiry/InquiryForm",
  component: InquiryForm,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/mypage/inquiries/new" } } },
  decorators: [
    (Story) => (
      <div className="w-[36rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  beforeEach: () => mockFetch({ "/api/v1/inquiries": {} }),
} satisfies Meta<typeof InquiryForm>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 보낸 뒤에는 본문을 고칠 수 없으므로(수정 경로가 없다) 길이 제한도 화면에서 알린다 —
 * 제목 100자, 본문 2000자.
 */
export const Default: Story = {};

/** 서버가 거절했을 때. 필드별 이유를 그대로 보여준다. */
export const Rejected: Story = {
  beforeEach: () => mockFetch({ "/api/v1/inquiries": 400 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
