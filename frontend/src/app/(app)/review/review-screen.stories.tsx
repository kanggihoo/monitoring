import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import { mockFetch } from "@/stories/mock-fetch";

import {
  DAILY_REPORT,
  DAILY_REPORT_DAY1,
  DAYS,
  SEASON_REPORT,
  WHAT_IF,
} from "./fixtures";
import { LockedNotice, ReviewScreen } from "./review-screen";

/** `/what-if` 가 먼저다 — 뒤에 두면 `/reports/season` 이 그 경로까지 삼킨다. */
const ROUTES = {
  "/api/v1/reports/season/what-if": WHAT_IF,
  "/api/v1/reports/season": SEASON_REPORT,
  // 일차별 경로를 **먼저** 둔다. `mockFetch` 는 먼저 맞는 것을 쓰므로 뒤에 두면 아래
  // 포괄 경로가 삼킨다. DAY 1 과 DAY 2 가 서로 다른 리포트를 부르는지 여기서 보인다.
  "/api/v1/reports/daily/1": DAILY_REPORT_DAY1,
  "/api/v1/reports/daily/": DAILY_REPORT,
};

const meta = {
  title: "Screens/Review/ReviewScreen",
  component: ReviewScreen,
  parameters: { layout: "fullscreen" },
  decorators: [
    // **앱 셸과 같은 모양이다** — 뷰포트 높이에 내용이 넘치면 그 안에서 스크롤한다
    // (`app/(app)/layout.tsx` 의 `md:h-dvh` + 본문 래퍼의 `md:overflow-y-auto`).
    // `overflow-hidden` 으로 두면 잘린 카드가 스토리에서 안 보인다.
    (Story) => (
      <div className="flex h-dvh overflow-y-auto bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: { days: DAYS, instruments: INSTRUMENTS },
  beforeEach: () => mockFetch(ROUTES),
} satisfies Meta<typeof ReviewScreen>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 열람 가능한 일차 중 **가장 나중 것**을 처음에 연다 — 방금 끝난 하루를 보러 오는 화면이다. */
export const Default: Story = {};

/**
 * 시즌이 끝난 뒤. **'시즌 결산' 이 그때 열린다** — 마지막 일차가 발표되기 전에는 조회
 * 자체가 409 라 눌리지 않는다 (`rpt.md`).
 */
export const SeasonOpen: Story = {
  args: { days: DAYS.map((day) => ({ ...day, available: true })) },
};

/** 시즌 첫날. 아직 발표된 일차가 하나도 없다. */
export const NothingOpenYet: Story = {
  args: {
    days: DAYS.map((day) => ({ ...day, available: false })),
  },
};

/** 아직 열리지 않은 구역을 알리는 줄. 일차 복기와 시즌 결산이 같은 배너를 쓴다. */
export const Locked: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex w-[34rem] max-w-full flex-col gap-2">
      <LockedNotice>3일차 정산 발표 뒤에 열립니다.</LockedNotice>
      <LockedNotice>오늘의 복기는 폐장 정산 뒤에 열립니다.</LockedNotice>
    </div>
  ),
};

export const Dark: Story = { globals: { theme: "dark" } };
