import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import { mockFetch } from "@/stories/mock-fetch";

import { DailyReportPanel } from "./daily-report";
import { DAILY_REPORT, DAYS, MANY_HOLDINGS } from "./fixtures";

const meta = {
  title: "Screens/Review/DailyReport",
  component: DailyReportPanel,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: {
    seasonDay: 2,
    entry: DAYS[1],
    instruments: INSTRUMENTS,
    // 화면 전환은 선택 줄이 쥐고 있고 스토리에는 그 줄이 없다. 자리만 채운다 —
    // 주지 않으면 선언 없는 사람의 안내에서 버튼이 통째로 빠져 검수할 수 없다.
    onStartOnboarding: () => {},
  },
  beforeEach: () => mockFetch({ "/api/v1/reports/daily/": DAILY_REPORT }),
} satisfies Meta<typeof DailyReportPanel>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 발표된 일차. 요약 타일 · 매매 지점 · 후회 목록 · AI 문구가 한 화면에 있다. */
export const Ready: Story = {};

/**
 * 아직 정산 발표 전. **실패가 아니라 '열람 대기' 다** — 계약이 409 로 정한 정상 상태라
 * (`rpt.md`) 비어 있는 것과 못 불러온 것을 구별할 수 있어야 한다.
 */
export const Locked: Story = {
  args: { seasonDay: 3, entry: DAYS[2] },
};

/** AI 호출이 실패한 날. 문구 자리와 결과지가 함께 비고 리포트는 그대로 열린다. */
export const WithoutCommentary: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        commentary: null,
        review: null,
      },
    }),
};

/**
 * 보유 종목이 많은 날. **종목명이 길고 달러 평단이 섞여도 줄이 접히지 않아야 한다** —
 * 유니버스에 68 종이 있어 실제로 열 줄을 넘을 수 있고, 목록이 열 높이를 넘으면 카드
 * 안에서만 스크롤되고 옆 열을 밀지 않아야 한다.
 */
export const ManyClosingHoldings: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        closingHoldings: MANY_HOLDINGS,
        // 보유가 늘면 합계도 늘어야 한다 — 84,544,348 + 4,939,652 + 166,664,801.
        closing: {
          ...DAILY_REPORT.closing!,
          holdingsValuationKrw: "166664801",
          totalValuationKrw: "256148801",
        },
      },
    }),
};

/** 아무것도 들고 끝내지 않은 날. 보유 줄이 '없음' 이고 펼칠 것이 없다. */
export const WithoutClosingHoldings: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        closing: { ...DAILY_REPORT.closing!, holdingsValuationKrw: "0" },
        closingHoldings: [],
      },
    }),
};

/**
 * 구성이 없는 옛 일차. **버튼째 그려지지 않는다** — 눌렀더니 빈 상자가 나오는 것보다
 * 없는 편이 낫다.
 */
export const WithoutClosing: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": { ...DAILY_REPORT, closing: null },
    }),
};

/** 시즌의 마지막 일차. 맺음말이 "다시 시작" 이 아니라 "시즌을 끝냈다" 여야 한다. */
export const FinalDayClosing: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        closing: { ...DAILY_REPORT.closing!, resetsNextDay: false },
      },
    }),
};

/**
 * 설문을 건너뛴 사람. **결과지의 '내가 생각한 나' 와 '예상과 차이' 가 빠진다** — 실패가
 * 아니라 정상이라, 빈 칸에 왜 비었는지가 적혀야 한다.
 *
 * 가운데 카드 머리에 설문을 권하는 안내와 버튼이 붙는다. 두 칸이 왜 비었는지는 빈 칸
 * 옆의 회색 한 줄로는 전해지지 않는다.
 */
export const WithoutDeclaration: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        review: {
          ...DAILY_REPORT.review!,
          selfLevel: null,
          selfTotal: null,
          selfPersona: "",
          profileMatch: null,
          profileItems: DAILY_REPORT.review!.profileItems.map((item) => ({
            ...item,
            selfPoint: null,
          })),
          narrative: {
            ...DAILY_REPORT.review!.narrative,
            selfView: null,
            profileGap: null,
          },
        },
      },
    }),
};

/**
 * 거래가 얇아 판정이 서지 않은 사람. 카드는 '아직 그리는 중' 이고 막대가 비지만
 * **빈 화면을 주지 않는다** (AI 명세 7.7).
 */
export const Undetermined: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        review: {
          ...DAILY_REPORT.review!,
          investorType: "UNDETERMINED",
          typeEmoji: "🌫️",
          typeName: "아직 그리는 중",
          typeDescription:
            "모습이 드러나기에 짧았습니다. 거래가 쌓이면 다시 봅니다.",
          profileLevel: null,
          profileTotal: null,
          profileName: "",
          profileMatch: null,
          profileItems: DAILY_REPORT.review!.profileItems.map((item) => ({
            ...item,
            point: null,
            provisional: true,
          })),
        },
      },
    }),
};

/**
 * 손실로 끝난 매도가 하나도 없던 날. **'최악 거래' 줄이 통째로 빠진다** — 두 번째로 잘한
 * 거래를 최악이라고 부르면 없는 실수를 만든다.
 */
export const NoLosingTrade: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        worstTrade: {
          executionId: 301,
          instrumentId: 15,
          realizedPnlKrw: "120000",
          returnRate: 2.4,
        },
      },
    }),
};

/** 거래가 없던 날. 매매 지점과 후회가 비고 상·하위 체결도 없다. */
export const NoTrades: Story = {
  beforeEach: () =>
    mockFetch({
      "/api/v1/reports/daily/": {
        ...DAILY_REPORT,
        summary: {
          dailyReturn: 0,
          executionCount: 0,
          totalFeeKrw: "0",
          realizedPnlKrw: "0",
        },
        bestTrade: null,
        worstTrade: null,
        instruments: [],
        patternTags: [],
        regret: null,
        commentary: { headline: "조용한 하루였습니다.", sceneAliases: [] },
        review: null,
      },
    }),
};

/** 조회가 깨졌을 때. 열람 대기와 다른 문구여야 한다. */
export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/reports/daily/": 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
