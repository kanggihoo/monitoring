import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { NicknameForm } from "./nickname-form";

const meta = {
  title: "Screens/MyPage/NicknameForm",
  component: NicknameForm,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/mypage" } } },
  decorators: [
    (Story) => (
      <div className="w-96 max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { nickname: "홍길동" },
} satisfies Meta<typeof NicknameForm>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 저장하면 서버에 `PATCH` 한다. 여기서는 성공으로 답하므로 오류 줄이 뜨지 않는다. */
export const Default: Story = {
  beforeEach: () => mockFetch({ "/api/v1/users/me": {} }),
};

/** 비우면 저장이 닫힌다 — 값이 없는 채로 보낼 수 있으면 400 을 받으러 가는 셈이다. */
export const Empty: Story = {
  args: { nickname: "" },
  beforeEach: () => mockFetch({ "/api/v1/users/me": {} }),
};

/**
 * 서버가 거절했을 때. 무엇을 고쳐야 하는지 알 수 있도록 필드별 이유를 그대로 보여준다 —
 * `저장` 을 눌러 보면 오류 줄이 붙는다.
 */
export const Rejected: Story = {
  beforeEach: () => mockFetch({ "/api/v1/users/me": 400 }),
};

export const Dark: Story = {
  beforeEach: () => mockFetch({ "/api/v1/users/me": {} }),
  globals: { theme: "dark" },
};
