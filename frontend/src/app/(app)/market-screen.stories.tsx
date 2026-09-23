import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  clockSnapshot,
  INSTRUMENTS,
  LEADERBOARD,
  POSITIONS,
  PRICES,
  RANKING_ME,
  VALUATION,
} from "@/components/market/fixtures";
import type { ClockSnapshot } from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";
import type { Series } from "@/stores/tick-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { MarketScreen } from "./market-screen";

/**
 * **첫 종목(1번)의 일봉, 2020-01 ~ 2026-08.**
 *
 * 공용 `SERIES` 는 6번 종목 것이라, 화면이 1번을 고른 채 열리면 차트가
 * `series.instrumentId !== selectedId` 에 걸려 아예 그려지지 않았다 — 이 스토리에
 * 차트가 없던 이유다. 종목 번호를 맞추고, 김에 길이도 실제로 쌓일 만큼 준다:
 * 120봉으로는 기간 탭도 봉 주기도 드러나지 않는다.
 *
 * 고정 시드 난수를 쓴다 — `Math.sin(i * k)` 같은 결정적 파동은 몇 봉마다 같은 폭으로
 * 진동해서, 사람 눈에 차트의 성질처럼 보이는 가짜 톱니를 만든다.
 */
const DAILY: Series = (() => {
  let seed = 20200102;
  const random = () => {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  };

  const points: Series["points"] = [];
  const cursor = new Date(Date.UTC(2020, 0, 1));
  const end = Date.UTC(2026, 7, 31);
  let price = 48;
  let i = 0;
  while (cursor.getTime() <= end) {
    const day = cursor.getUTCDay();
    if (day !== 0 && day !== 6) {
      const drift = 0.035 + 0.02 * Math.sin(i / 260);
      price = Math.max(8, price + drift + (random() - 0.5) * price * 0.028);
      points.push({
        gameDate: cursor.toISOString().slice(0, 10),
        close: price.toFixed(2),
      });
      i += 1;
    }
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return { instrumentId: 1, currency: "USD", priceScale: 2, points };
})();

const LAST = DAILY.points.at(-1)!;

/**
 * 1번 종목의 보유. 마지막 종가보다 18% 싸게 산 것으로 둔다 — 그래야 평단가 기준선이
 * 시세 아래에 선다.
 *
 * **파생값을 손으로 적지 않는다.** 평단가만 바꾸고 수익률을 픽스처의 원래 값으로 두면
 * 화면이 "+22% 인 보유" 를 "-3.52%" 라고 말하게 된다. 화면이 계산하지 않고 받아 쓰는
 * 값이라 픽스처가 틀리면 화면도 같이 틀린다.
 */
const HOLDING = (() => {
  const quantity = 12;
  const avg = Number(LAST.close) * 0.82;
  const current = Number(LAST.close);
  const cost = avg * quantity;
  const value = current * quantity;
  const rate = 1187.2;
  return {
    ...POSITIONS[0]!,
    instrumentId: 1,
    currency: "USD" as const,
    quantity,
    lockedQuantity: 0,
    avgPrice: avg.toFixed(2),
    currentPrice: current.toFixed(2),
    totalCost: cost.toFixed(2),
    valuation: value.toFixed(2),
    valuationKrw: Math.round(value * rate).toString(),
    unrealizedPnl: (value - cost).toFixed(2),
    unrealizedPnlKrw: Math.round((value - cost) * rate).toString(),
    returnRate: Number((((current - avg) / avg) * 100).toFixed(2)),
  };
})();

/** 경로가 서로를 삼키지 않게 **좁은 것부터** 둔다 — `mockFetch` 는 먼저 맞는 것을 쓴다. */
const ROUTES = {
  // 종목 정보 카드(`/info`)와 신호등 기준은 일부러 비워 둔다(503). 그 둘이 실패해도
  // 차트·주문·뉴스는 그대로 동작해야 한다는 것이 화면의 규칙이고, 각자의 모습은
  // `Screens/Market/*` 스토리에 있다.
  "/info": 503,
  "/api/v1/market/signal-criteria": 503,
  "/closes": {
    instrumentId: DAILY.instrumentId,
    currency: DAILY.currency,
    priceScale: DAILY.priceScale,
    asOfGameDate: LAST.gameDate,
    asOfTickSeq: 137,
    points: DAILY.points,
  },
  "/api/v1/market/instruments": { instruments: INSTRUMENTS },
  "/api/v1/rankings": LEADERBOARD,
  "/api/v1/ipo/offerings": { asOfTickSeq: 137, offerings: [] },
  "/api/v1/news": {
    asOfTickSeq: 137,
    items: [],
    nextCursor: null,
    hasNext: false,
  },
  "/api/v1/orders/preview": 503,
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
      baseTickSeq: 137,
      gameDate: LAST.gameDate,
      prices: {
        ...PRICES,
        1: { ...PRICES[1]!, close: LAST.close, changeRate: 1.24 },
      },
      usdKrwRate: "1187.20",
      valuation: personal ? VALUATION : null,
      myRank: personal ? RANKING_ME : null,
      positions: personal
        ? {
            asOfTickSeq: 137,
            asOfGameDate: LAST.gameDate,
            usdKrwRate: "1187.20",
            // 1번 종목의 보유를 끼워 넣어 평단가 기준선이 실제로 그려지게 한다.
            positions: [
              HOLDING,
              ...POSITIONS.filter((p) => p.instrumentId !== 1),
            ],
          }
        : null,
    });
  }, [clock, personal]);
  return null;
}

const meta = {
  title: "Screens/Market/MarketScreen",
  component: MarketScreen,
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/" } },
  },
  decorators: [
    (Story) => (
      <div className="flex min-h-[48rem] bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS, isLoggedIn: true },
  beforeEach: () => mockFetch(ROUTES),
} satisfies Meta<typeof MarketScreen>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 거래 구간. 종목 목록 · 차트 · 주문 · 리더보드 · 뉴스 티커가 한 화면이다. */
export const Trading: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed />
        <Story />
      </>
    ),
  ],
};

/** 미인증. 주문 패널이 로그인 안내로 바뀌고 공개 조회는 그대로 흐른다 (ADR-0005). */
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

/** 폐장 뒤. 주문이 닫히고 그 이유를 화면이 말한다. */
export const Closed: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed clock={{ phase: "REVIEW_ONLY" }} />
        <Story />
      </>
    ),
  ],
};

/** 수수료 무료 구간. 매도 요율이 0 이 되고 그 사실이 주문 패널에 뜬다. */
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
  ...Trading,
  globals: { theme: "dark" },
};
