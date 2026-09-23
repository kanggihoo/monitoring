import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { clockSnapshot, INSTRUMENTS } from "@/components/market/fixtures";
import type { NewsItem } from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { NewsScreen } from "./news-screen";

const item = (over: Partial<NewsItem> & { newsId: number }): NewsItem => ({
  channel: "KR",
  headline: "헤드라인",
  body: "본문 두세 문장이 목록에 함께 온다 — 상세 경로가 없다.",
  publishedGameDate: "2020-08-14",
  publishedTickSeq: 130,
  publishedTime: "09:05:00",
  major: false,
  instrumentIds: [],
  tags: [],
  relevance: "MARKET",
  ...over,
});

/** 채널·중요도·관련도를 섞어 둔다 — 강조가 갈리는 지점이 전부 이 셋에 있다. */
const ITEMS: NewsItem[] = [
  item({
    newsId: 51,
    channel: "US",
    headline: "연준이 기준금리를 0~0.25%로 긴급 인하했다.",
    major: true,
    tags: ["금리·채권"],
  }),
  item({
    newsId: 50,
    headline: "삼성전자, 2분기 반도체 영업이익이 전분기 대비 늘었다.",
    instrumentIds: [1],
    tags: ["반도체"],
    relevance: "HOLDING",
  }),
  item({
    newsId: 49,
    channel: "CRYPTO",
    headline: "이더리움 네트워크 수수료가 하루 만에 세 배로 뛰었다.",
    instrumentIds: [27],
    tags: ["디파이"],
  }),
  item({
    newsId: 48,
    headline: "카카오, 메신저 광고 매출이 두 자릿수 늘었다고 공시했다.",
    publishedGameDate: "2020-08-13",
    publishedTickSeq: 88,
    instrumentIds: [19],
    relevance: "WATCHLIST",
  }),
];

const page = (items: NewsItem[]) => ({
  asOfTickSeq: 137,
  items,
  nextCursor: null,
  hasNext: false,
});

/** 종목 목록이 먼저다 — `/news` 가 `/market/instruments` 를 삼키지 않게 순서를 둔다. */
const ROUTES = {
  "/api/v1/market/instruments": { instruments: INSTRUMENTS },
  "/api/v1/news": page(ITEMS),
};

function Seed() {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
    tickStore.setState({ tickSeq: 137, gameDate: "2020-08-14", news: ITEMS });
  }, []);
  return null;
}

const meta = {
  title: "Screens/News/NewsScreen",
  component: NewsScreen,
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/news" } },
  },
  decorators: [
    (Story) => (
      <div className="flex min-h-[44rem] bg-background p-3">
        <Seed />
        <Story />
      </div>
    ),
  ],
  args: { isLoggedIn: true },
  beforeEach: () => mockFetch(ROUTES),
} satisfies Meta<typeof NewsScreen>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 정렬과 미래 차단은 **서버가 한다**. 화면은 같은 `relevance` 로 강조만 한다 — 여기서 다시
 * 정렬하면 커서가 가리키는 자리와 화면의 자리가 달라진다 (`nws.md`).
 */
export const Default: Story = {};

/** 미인증. 내 보유 기준 강조가 없어지고 개인 목록도 비어 있다. */
export const Unauthenticated: Story = { args: { isLoggedIn: false } };

/** 시즌 첫 틱 직후. 아직 발행된 기사가 없다. */
export const Empty: Story = {
  beforeEach: () => mockFetch({ ...ROUTES, "/api/v1/news": page([]) }),
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ ...ROUTES, "/api/v1/news": 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
