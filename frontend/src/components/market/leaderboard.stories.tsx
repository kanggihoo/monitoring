import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type {
  Leaderboard as LeaderboardData,
  RankingMe,
} from "@/lib/market-types";

import { EXTREME_LEADERBOARD, EXTREME_RANKING_ME } from "./fixtures";
import { Leaderboard } from "./leaderboard";

const board: LeaderboardData = {
  track: "DAILY",
  asOfTickSeq: 137,
  asOfGameDate: "2022-04-15",
  blackout: false,
  finalized: false,
  totalParticipants: 542,
  content: [
    {
      rank: 1,
      rankDelta: 0,
      nickname: "느린손",
      avatarId: 1,
      returnRate: 42.18,
      totalValuationKrw: "142180000",
      holdingCount: 5,
      tradeCount: 37,
      points: null,
      wins: null,
      isMe: false,
    },
    {
      rank: 2,
      rankDelta: 1,
      nickname: "리플레이어",
      avatarId: 2,
      returnRate: 31.4,
      totalValuationKrw: "131400000",
      holdingCount: 3,
      tradeCount: 12,
      points: null,
      wins: null,
      isMe: false,
    },
    {
      rank: 3,
      rankDelta: -1,
      nickname: "2011생존자",
      avatarId: 3,
      returnRate: 28.05,
      totalValuationKrw: "128050000",
      holdingCount: 7,
      tradeCount: 51,
      points: null,
      wins: null,
      isMe: false,
    },
  ],
  page: 0,
  size: 3,
  totalElements: 542,
  totalPages: 181,
  hasNext: true,
};

const me: RankingMe = {
  asOfTickSeq: 137,
  seasonRank: 328,
  seasonRankDelta: -4,
  seasonReturnRate: -8.6,
  dailyRank: 88,
  dailyRankDelta: 21,
  dailyReturnRate: 0.12,
  valuationRank: 331,
  valuationRankDelta: -3,
  totalValuationKrw: "91400000",
  totalParticipants: 542,
  blackout: false,
};

const meta = {
  title: "Screens/Market/Leaderboard",
  component: Leaderboard,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="max-w-sm bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { board, me, myNickname: "홍길동" },
} satisfies Meta<typeof Leaderboard>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Live: Story = {};

/** 16:00~16:40 암전. 순위를 가리고 내 줄도 숨긴다 (`rank.md`). */
export const Blackout: Story = {
  args: {
    board: { ...board, blackout: true, content: [] },
    me: {
      ...me,
      seasonRank: null,
      seasonRankDelta: null,
      dailyRank: null,
      dailyRankDelta: null,
      blackout: true,
    },
  },
};

export const Loading: Story = { args: { board: null, me: null } };

export const Dark: Story = { globals: { theme: "dark" } };

/**
 * **자릿수가 터졌을 때.** 마켓 화면 오른쪽 기둥의 좁은 카드라 수익률이 닉네임 아래로
 * 들어가 있다 — 길어져도 옆 칸을 밀지 않고 제 줄에서 늘어난다. 여기서 볼 것은 순위 등락
 * (`RankDelta`)과 부딪히기 시작하는 지점이다.
 *
 * 위에서 아래로 갈수록 자릿수가 줄어드니, 몇 자리부터 줄이 버티는지 눈으로 집을 수 있다.
 */
export const Extreme: Story = {
  args: { board: EXTREME_LEADERBOARD, me: EXTREME_RANKING_ME },
};

export const ExtremeDark: Story = {
  ...Extreme,
  name: "Extreme (Dark)",
  globals: { theme: "dark" },
};
