import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  clockSnapshot,
  HEATMAP,
  INSTRUMENTS,
  PRICES,
  RANKING_ME,
  VALUATION,
} from "@/components/market/fixtures";
import type { ClockSnapshot, NewsItem } from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { HomeDashboard } from "./home-dashboard";

const NEWS: NewsItem[] = [
  {
    newsId: 41,
    channel: "KR",
    headline: "삼성전자, 2분기 반도체 영업이익이 전분기 대비 늘었다고 밝혔다.",
    body: "메모리 가격 반등이 실적에 반영됐다.",
    publishedGameDate: "2020-08-14",
    publishedTickSeq: 131,
    publishedTime: "09:05:00",
    major: true,
    instrumentIds: [1],
    tags: ["반도체"],
    relevance: "HOLDING",
  },
  {
    newsId: 40,
    channel: "US",
    headline: "나스닥이 사상 최고치를 다시 썼다.",
    body: "기술주가 지수를 끌어올렸다.",
    publishedGameDate: "2020-08-14",
    publishedTickSeq: 128,
    publishedTime: "06:30:00",
    major: false,
    instrumentIds: [],
    tags: ["지수"],
    relevance: "MARKET",
  },
];

const ROUTES = {
  "/api/v1/news": {
    asOfTickSeq: 137,
    items: NEWS,
    nextCursor: null,
    hasNext: false,
  },
  "/api/v1/executions": {
    content: [],
    page: 0,
    size: 100,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  },
};

function Seed({
  clock = {},
  personal = true,
}: {
  clock?: Partial<ClockSnapshot>;
  personal?: boolean;
}) {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot(clock));
    sessionStore.getState().setConnected(true);
    tickStore.setState({
      tickSeq: 137,
      prices: PRICES,
      news: NEWS,
      heatmap: HEATMAP,
      usdKrwRate: "1187.20",
      valuation: personal ? VALUATION : null,
      myRank: personal ? RANKING_ME : null,
    });
  }, [clock, personal]);
  return null;
}

const meta = {
  title: "Screens/Dashboard/HomeDashboard",
  component: HomeDashboard,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="flex min-h-[48rem] bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS, isLoggedIn: true },
  beforeEach: () => mockFetch(ROUTES),
} satisfies Meta<typeof HomeDashboard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 로그인 상태. 시장 시계 · 급등락 · 내 계좌 요약 · 배분 · 뉴스가 한 화면에 있다. */
export const Default: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed />
        <Story />
      </>
    ),
  ],
};

/** 미인증. 개인 카드가 로그인 안내로 바뀌고 공개 카드는 그대로 흐른다 (ADR-0005). */
export const Unauthenticated: Story = {
  args: { isLoggedIn: false },
  decorators: [
    (Story) => (
      <>
        <Seed personal={false} />
        <Story />
      </>
    ),
  ],
};

/** 수수료 무료 구간. 맨 위에 남은 시간을 말하는 띠가 붙는다. */
export const FeeFree: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed
          clock={{
            feeFree: true,
            feeFreeEndsAt: new Date(Date.now() + 7 * 60_000).toISOString(),
          }}
        />
        <Story />
      </>
    ),
  ],
};

export const Dark: Story = {
  ...Default,
  globals: { theme: "dark" },
};
