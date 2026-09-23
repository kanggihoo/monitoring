import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import { mockFetch } from "@/stories/mock-fetch";

import { WHAT_IF } from "./fixtures";
import { WhatIfCard } from "./what-if";

const PATH = "/api/v1/reports/season/what-if";

const meta = {
  title: "Screens/Review/WhatIf",
  component: WhatIfCard,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="w-[26rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS },
  beforeEach: () => mockFetch({ [PATH]: WHAT_IF }),
} satisfies Meta<typeof WhatIfCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** *"1일차 개장에 전액 매수해 3일차까지 들고 있었다면"* 의 가상 수익률. */
export const Default: Story = {};

/**
 * 3일차 발표 전. 시즌 누적이 없고 일차 분해만 온다 — 같은 축끼리만 견준다.
 */
export const BeforeSeasonClose: Story = {
  beforeEach: () =>
    mockFetch({
      [PATH]: { ...WHAT_IF, returnRate: null, myReturnRate: null },
    }),
};

/**
 * 두 `null` 의 뜻이 다르다. 종목 쪽이 비면 그 일차에 상장 전이라 살 수 없었다는 뜻이고,
 * 내 쪽이 비면 그 일차에 참가하지 않았다는 뜻이다.
 */
export const NotListedAndAbsent: Story = {
  beforeEach: () =>
    mockFetch({
      [PATH]: {
        ...WHAT_IF,
        days: [
          { seasonDay: 1, returnRate: null, myReturnRate: 12.4 },
          { seasonDay: 2, returnRate: 44.7, myReturnRate: null },
        ],
      },
    }),
};

/** 조회가 깨졌을 때. **시뮬레이터만 비운다** — 시즌 성적과 매매 지점은 그대로 보인다. */
export const Failed: Story = {
  beforeEach: () => mockFetch({ [PATH]: 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
