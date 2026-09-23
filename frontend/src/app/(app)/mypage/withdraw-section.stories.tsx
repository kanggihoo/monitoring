import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { WithdrawSection } from "./withdraw-section";

const meta = {
  title: "Screens/MyPage/WithdrawSection",
  component: WithdrawSection,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/mypage" } } },
  decorators: [
    (Story) => (
      <div className="w-[30rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { providers: ["google"] },
  beforeEach: () => mockFetch({ "/api/v1/users/me": {} }),
} satisfies Meta<typeof WithdrawSection>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 확인을 한 번 더 받는다. 누르면 알림 다이얼로그가 열린다. */
export const Default: Story = {};

/** 서버가 거절했을 때. 지워졌다고 잘못 믿지 않도록 실패 사실과 재시도 안내를 남긴다. */
export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/users/me": 500 }),
};

/**
 * 샌드박스 안에서는 **자리 자체를 감춘다** — 거기서 누르면 관리자에게 배정된 Test User 가
 * 지워지고 그 사실을 알아챌 단서가 화면에 없다. 이 스토리는 빈 화면이 정답이다.
 */
export const InSandbox: Story = {
  args: { providers: [] },
};

export const Dark: Story = { globals: { theme: "dark" } };
