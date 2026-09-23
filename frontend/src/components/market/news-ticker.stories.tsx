import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { tickStore } from "@/stores/tick-store";

import { RANKING_ME } from "./fixtures";
import { NewsTicker } from "./news-ticker";

const meta = {
  title: "Screens/Market/NewsTicker",
  component: NewsTicker,
  tags: ["autodocs"],
  parameters: { layout: "padded" },
} satisfies Meta<typeof NewsTicker>;

export default meta;

type Story = StoryObj<typeof meta>;

export const WithNews: Story = {
  render: () => {
    useEffect(() => {
      tickStore.setState({
        gameDate: "2020-08-14",
        myRank: RANKING_ME,
        news: [
          {
            newsId: 1,
            channel: "US",
            headline: "재택근무 관련 소프트웨어 수요 급증 보도",
            body: "협업·화상회의 소프트웨어 수요가 빠르게 늘고 있다는 조사 결과가 나왔다.",
            publishedGameDate: "2020-08-14",
            publishedTickSeq: 92,
            publishedTime: "11:20:00",
            major: false,
            instrumentIds: [6],
            tags: ["소프트웨어"],
            relevance: "HOLDING",
          },
          {
            newsId: 2,
            channel: "KR",
            headline: "기관 자금 유입 3주 연속 순증",
            body: "주간 집계에서 기관 자금이 이어서 들어온 것으로 나타났다.",
            publishedGameDate: "2020-08-13",
            publishedTickSeq: 91,
            publishedTime: "15:40:00",
            major: false,
            instrumentIds: [],
            tags: [],
            relevance: "MARKET",
          },
          {
            newsId: 3,
            channel: "CRYPTO",
            headline: "이더리움 가스비 사상 최고치",
            body: "디파이 거래가 몰리며 평균 수수료가 최고 수준까지 올랐다.",
            publishedGameDate: "2020-08-12",
            publishedTickSeq: 90,
            publishedTime: "13:47:00",
            major: true,
            instrumentIds: [25],
            tags: ["가상자산"],
            relevance: "WATCHLIST",
          },
        ],
      });
    }, []);
    return <NewsTicker />;
  },
};

export const Empty: Story = {
  render: () => {
    useEffect(() => {
      tickStore.setState({ gameDate: "2020-08-14", news: [], myRank: null });
    }, []);
    return <NewsTicker />;
  },
};
