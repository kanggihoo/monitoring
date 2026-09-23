import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { NewsItem, NewsPage } from "@/lib/market-types";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { InstrumentNews } from "./instrument-news";

const ITEMS: NewsItem[] = [
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
    newsId: 38,
    channel: "KR",
    headline:
      "파운드리 신규 수주 소식이 전해지며 장 초반 매수세가 몰렸다고 증권가가 전했다.",
    body: "수주 규모는 공개되지 않았다.",
    publishedGameDate: "2020-08-13",
    publishedTickSeq: 88,
    publishedTime: "10:22:00",
    major: false,
    instrumentIds: [1],
    tags: ["반도체"],
    relevance: "MARKET",
  },
  {
    newsId: 30,
    channel: "US",
    headline: "미국 반도체 지수가 하루 만에 3% 넘게 빠졌다.",
    body: "기술주 전반이 약했다.",
    publishedGameDate: "2020-08-12",
    publishedTickSeq: 40,
    publishedTime: "23:10:00",
    major: false,
    instrumentIds: [1],
    tags: ["반도체", "지수"],
    relevance: "MARKET",
  },
];

/**
 * 컴포넌트가 직접 조회하므로 스토리가 `fetch` 를 가로챈다. 시계(`gameDate`)가 서야
 * 조회를 시작하는 것도 여기서 맞춰 준다 — 절단 기준이 필수다 (`nws.md`).
 */
function stubNews(items: NewsItem[] | 500) {
  const page: NewsPage = {
    asOfTickSeq: 137,
    items: items === 500 ? [] : items,
    nextCursor: null,
    hasNext: false,
  };
  return mockFetch({ "/api/v1/news": items === 500 ? 500 : page });
}

function Clock() {
  useEffect(() => {
    tickStore.setState({ gameDate: "2020-08-14" });
  }, []);
  return null;
}

const meta = {
  title: "Screens/Market/InstrumentNews",
  component: InstrumentNews,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="w-80 max-w-full bg-background p-6">
        <Clock />
        <Story />
      </div>
    ),
  ],
  args: { instrumentId: 1 },
} satisfies Meta<typeof InstrumentNews>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 날짜 + 헤드라인 한 줄씩. 넘치는 헤드라인은 잘린다 — 본문은 뉴스 탭에서 편다. */
export const Default: Story = {
  beforeEach: () => stubNews(ITEMS),
};

/** 소식이 없는 종목. 빈 칸으로 두지 않고 그렇게 말한다. */
export const Empty: Story = {
  beforeEach: () => stubNews([]),
};

/**
 * 조회가 깨졌을 때도 같은 빈 문구를 쓴다 (`instrument-news.tsx`). 로딩 중에는 아무것도
 * 그리지 않으므로 이 스토리도 잠깐 빈 화면에서 시작한다.
 */
export const Failed: Story = {
  beforeEach: () => stubNews(500),
};

export const Dark: Story = {
  beforeEach: () => stubNews(ITEMS),
  globals: { theme: "dark" },
};
