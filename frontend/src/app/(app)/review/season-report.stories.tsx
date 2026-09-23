import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import { mockFetch } from "@/stories/mock-fetch";

import { DAILY_REPORT, DAYS, SEASON_REPORT, WHAT_IF } from "./fixtures";
import { SeasonReportPanel } from "./season-report";

/**
 * 시즌 지표와 매매 지점을 따로 읽는다 — 후자가 실패해도 지표는 보여야 한다.
 *
 * `/what-if` 를 **먼저** 둔다. `mockFetch` 는 먼저 맞는 것을 쓰므로, 뒤에 두면
 * `/reports/season` 이 그 경로까지 삼켜 '만약에' 카드가 시즌 리포트를 받는다.
 */
const ROUTES = {
  "/api/v1/reports/season/what-if": WHAT_IF,
  "/api/v1/reports/season": SEASON_REPORT,
  "/api/v1/reports/daily/": DAILY_REPORT,
};

const meta = {
  title: "Screens/Review/SeasonReport",
  component: SeasonReportPanel,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: { days: DAYS, instruments: INSTRUMENTS },
  beforeEach: () => mockFetch(ROUTES),
} satisfies Meta<typeof SeasonReportPanel>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 3일차 발표 뒤. 지표 타일 · 기준선 · 투자자 유형 · 결과 카드가 모두 찬 상태다. */
export const Ready: Story = {};

/**
 * 3일차 정산 발표 전. 계약이 409 로 답하므로 실패가 아니라 '열람 대기' 로 그린다
 * (`rpt.md`).
 */
export const Locked: Story = {
  beforeEach: () => mockFetch({ ...ROUTES, "/api/v1/reports/season": 409 }),
};

/** AI 판정이 실패했을 때. 유형과 결과 카드 자리를 비우고 지표는 그대로 둔다. */
export const WithoutAi: Story = {
  beforeEach: () =>
    mockFetch({
      ...ROUTES,
      "/api/v1/reports/season": {
        ...SEASON_REPORT,
        investorType: null,
        shareCard: null,
      },
    }),
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ ...ROUTES, "/api/v1/reports/season": 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
