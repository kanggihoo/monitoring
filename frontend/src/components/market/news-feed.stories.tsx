import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { NewsItem } from "@/lib/market-types";

import { NewsFeed } from "./news-feed";

const ITEMS: NewsItem[] = [
  {
    newsId: 1,
    channel: "US",
    headline:
      "연준, 기준금리를 0~0.25%로 긴급 인하하고 자산매입 재개를 발표했다.",
    body: "임시 회의에서 금리를 0~0.25%로 내렸다. 국채·MBS 매입 확대도 함께 발표했다.",
    publishedGameDate: "2020-03-16",
    publishedTickSeq: 152,
    publishedTime: "17:00:00",
    major: true,
    instrumentIds: [],
    tags: ["금리·채권"],
    relevance: "MARKET",
  },
  {
    newsId: 2,
    channel: "KR",
    headline:
      "카카오, 1분기 메신저 광고 매출이 전년 대비 두 자릿수 늘었다고 공시했다.",
    body: "광고 단가와 노출 지면이 함께 늘었다고 밝혔다.",
    publishedGameDate: "2020-03-16",
    publishedTickSeq: 152,
    publishedTime: "09:12:00",
    major: false,
    instrumentIds: [19],
    tags: ["플랫폼"],
    relevance: "HOLDING",
  },
  {
    newsId: 3,
    channel: "CRYPTO",
    headline: "이더리움 네트워크 수수료가 하루 만에 세 배로 뛰었다.",
    body: "디파이 거래가 몰리며 네트워크 혼잡이 이어졌다.",
    publishedGameDate: "2020-03-16",
    publishedTickSeq: 152,
    publishedTime: "13:47:00",
    major: false,
    instrumentIds: [25],
    tags: ["가상자산"],
    relevance: "WATCHLIST",
  },
  {
    newsId: 4,
    channel: "US",
    headline: "한 소매 체인이 매장 진열 방식을 바꾼다고 밝혔다.",
    body: "일부 점포에서 시범 운영한 뒤 확대 여부를 정한다.",
    publishedGameDate: "2020-03-16",
    publishedTickSeq: 152,
    publishedTime: "11:05:00",
    major: false,
    instrumentIds: [],
    tags: [],
    relevance: "MARKET",
  },
];

const meta = {
  title: "Screens/Dashboard/NewsFeed",
  component: NewsFeed,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { items: ITEMS },
} satisfies Meta<typeof NewsFeed>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 보유 > 관심 > 일반 순으로 정렬된다. 무의미했던 뉴스도 섞어 노이즈를 만든다 (기획 12장). */
export const Default: Story = {};

export const Empty: Story = { args: { items: [] } };

export const Dark: Story = { globals: { theme: "dark" } };
