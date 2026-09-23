import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { AnswerForm } from "./answer-form";

const PATH = "/api/v1/admin/inquiries/7/answer";

const meta = {
  title: "Screens/Admin/AnswerForm",
  component: AnswerForm,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/admin/inquiries" } } },
  decorators: [
    (Story) => (
      <div className="w-[36rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { inquiryId: 7, answer: null },
  beforeEach: () => mockFetch({ [PATH]: {} }),
} satisfies Meta<typeof AnswerForm>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 아직 답변이 없는 문의. 빈 칸으로 시작한다. */
export const New: Story = {};

/**
 * 이미 답변이 달린 문의. 등록과 수정이 같은 요청이라 화면도 하나다 — 오타 하나로
 * 사용자에게 새 문의를 파게 할 수 없다.
 */
export const Editing: Story = {
  args: {
    answer:
      "확인했습니다. 해당 주문은 폐장 시각에 자동 취소된 것으로, 증거금은 정산과 함께 환급됐습니다.",
  },
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ [PATH]: 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
