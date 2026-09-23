import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { clockSnapshot, VALUATION } from "@/components/market/fixtures";
import type {
  Briefing,
  IpoOffering,
  IpoOfferingDetail,
  IpoOfferings,
} from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { IpoSubscriptions } from "./ipo-subscriptions";

const base = {
  currency: "KRW" as const,
  offerPrice: "32000",
  depositAmount: "1600000",
  listingGameDate: "2020-08-20",
  subscriptionClosesAt: "2026-09-16T04:53:00Z",
  listedAt: null,
  expectedWinnerCount: 40,
};

const BRIEFING: Briefing = {
  seasonDay: 1,
  replayStartGameMonth: "2020-08",
  schedule: {
    tradingStartsAt: "2026-09-16T02:05:00Z",
    feeFreeStartsAt: "2026-09-16T03:00:00Z",
    feeFreeEndsAt: "2026-09-16T03:10:00Z",
    blackoutStartsAt: "2026-09-16T07:00:00Z",
    closesAt: "2026-09-16T07:40:00Z",
    settlementAt: "2026-09-16T08:00:00Z",
  },
  ipoWindows: [
    {
      disclosureAt: "2026-09-16T03:17:00Z",
      subscriptionClosesAt: "2026-09-16T03:29:00Z",
    },
  ],
};

/** 네 가지 상태(청약 중 · 추첨 중 · 발표 · 상장)와 내 청약 유무를 한 목록에 섞어 둔다. */
const OFFERINGS: IpoOffering[] = [
  {
    ...base,
    offeringId: 8,
    instrumentId: 31,
    name: "카카오게임즈",
    status: "OPEN",
    subscriberCount: 412,
    competitionRate: 10.3,
    winProbability: 9.7,
    mySubscription: null,
  },
  {
    ...base,
    offeringId: 7,
    instrumentId: 32,
    name: "빅히트엔터테인먼트",
    status: "OPEN",
    subscriberCount: 188,
    competitionRate: 4.7,
    winProbability: 21.3,
    mySubscription: {
      subscribedAt: "2026-09-16T03:20:00Z",
      depositAmount: "1600000",
      result: null,
    },
  },
  {
    ...base,
    offeringId: 6,
    instrumentId: 33,
    name: "SK바이오팜",
    status: "DRAWING",
    subscriptionClosesAt: null,
    subscriberCount: 502,
    competitionRate: 12.6,
    winProbability: null,
    mySubscription: {
      subscribedAt: "2026-09-15T05:10:00Z",
      depositAmount: "1600000",
      result: null,
    },
  },
  {
    ...base,
    offeringId: 5,
    instrumentId: 34,
    name: "하이브",
    status: "ANNOUNCED",
    subscriptionClosesAt: null,
    subscriberCount: 350,
    competitionRate: 8.8,
    winProbability: null,
    mySubscription: {
      subscribedAt: "2026-09-14T05:10:00Z",
      depositAmount: "1600000",
      result: {
        won: true,
        allocatedQuantity: 50,
        allocatedAmount: "1600000",
        refundedAmount: "0",
        drawnAt: "2026-09-14T08:00:00Z",
      },
    },
  },
  {
    ...base,
    offeringId: 4,
    instrumentId: 35,
    name: "빅솔론",
    status: "LISTED",
    subscriptionClosesAt: null,
    listedAt: "2026-09-13T02:05:00Z",
    subscriberCount: 120,
    competitionRate: 3.0,
    winProbability: null,
    mySubscription: {
      subscribedAt: "2026-09-13T01:10:00Z",
      depositAmount: "1600000",
      result: {
        won: false,
        allocatedQuantity: 0,
        allocatedAmount: "0",
        refundedAmount: "1600000",
        drawnAt: "2026-09-13T04:00:00Z",
      },
    },
  },
];

const list = (offerings: IpoOffering[]) => ({
  /* 공시 내용은 접히지 않고 늘 펴지므로 카드마다 제 상세를 부른다.
     **목록보다 먼저 적는다** — `mockFetch` 는 `url.includes(path)` 로 첫 짝을 고르므로,
     목록이 앞에 있으면 `/offerings/8` 까지 목록이 먹어 `summary` 없는 값이 돌아온다. */
  ...Object.fromEntries(
    offerings.map((offering) => [
      `/api/v1/ipo/offerings/${offering.offeringId}`,
      {
        ...offering,
        summary: `${offering.name} 의 공시 요약. 사업 내용과 상장 배경을 한 문단으로 적는다.`,
        allocationRule: "청약자 수의 30%를 무작위 추첨한다.",
      } satisfies IpoOfferingDetail,
    ])
  ),
  "/api/v1/ipo/offerings": {
    asOfTickSeq: 137,
    offerings,
  } satisfies IpoOfferings,
  "/api/v1/seasons/current/briefing": BRIEFING,
});

function Seed() {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
    tickStore.setState({ tickSeq: 137, valuation: VALUATION });
  }, []);
  return null;
}

const meta = {
  title: "Screens/Ipo/IpoSubscriptions",
  component: IpoSubscriptions,
  decorators: [
    (Story) => (
      /* 34rem 이면 아래 두 카드가 자리를 다 먹어 공모 목록에 36px 만 남는다 —
         카드를 보려고 만든 스토리인데 제목 줄만 보였다. 목록이 숨 쉴 만큼 준다. */
      <div className="flex h-[64rem] bg-background p-6">
        <Seed />
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof IpoSubscriptions>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 신청·취소 버튼은 `OPEN` 인 공모에만 나온다 — 마감 뒤에는 계약이 아예 받지 않는다. */
export const Default: Story = {
  beforeEach: () => mockFetch(list(OFFERINGS)),
};

/** 시즌 시작 직후. 아직 공시된 공모가 없다. */
export const Empty: Story = {
  beforeEach: () => mockFetch(list([])),
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ "/api/v1/ipo/offerings": 500 }),
};

export const Dark: Story = {
  beforeEach: () => mockFetch(list(OFFERINGS)),
  globals: { theme: "dark" },
};
