import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";
import type { PatternTag, ReportInstrument } from "@/lib/market-types";

import { REPORT_INSTRUMENTS } from "./fixtures";
import { InstrumentReplayList } from "./instrument-replay";

const nameOf = (instrumentId: number) =>
  INSTRUMENTS.find((item) => item.instrumentId === instrumentId)?.name ??
  `#${instrumentId}`;

/** 한 일차 구간(2020-01 ~ 2020-08)의 거래일. 카드마다 같은 x 축을 쓴다. */
const DATES = Array.from(
  { length: 24 },
  (_, index) =>
    `2020-${String(1 + Math.floor(index / 3)).padStart(2, "0")}-${String(1 + (index % 3) * 10).padStart(2, "0")}`
);

/**
 * 흔들리는 흐름 하나. 같은 씨앗이면 같은 그림이라 스토리가 매번 같은 모양으로 선다 —
 * 난수를 쓰면 시각 회귀에서 매번 다른 그림이 나온다.
 */
const wave = (base: number, swing: number, seed: number, fromIndex = 0) =>
  DATES.slice(fromIndex).map((gameDate, index) => ({
    gameDate,
    close: String(
      base *
        (1 +
          swing *
            (Math.sin((index + seed) / 2.3) * 0.6 +
              Math.sin((index + seed) / 5.7) * 0.4))
    ),
  }));

let nextExecutionId = 400;
const trade = (
  gameDate: string,
  side: "BUY" | "SELL",
  price: string,
  quantity = 10
) => ({
  executionId: (nextExecutionId += 1),
  gameDate,
  side,
  quantity,
  price,
});

/** 한 종목의 카드 한 장. 체결은 그 종목 종가 위에서 고른다. */
const made = (
  instrumentId: number,
  currency: "KRW" | "USD",
  priceScale: number,
  base: number,
  seed: number,
  tradeAt: number[],
  fromIndex = 0
): ReportInstrument => {
  const points = wave(base, 0.22, seed, fromIndex);
  return {
    instrumentId,
    currency,
    priceScale,
    points,
    // 표본이라 `tradeAt` 은 항상 범위 안이지만, 아니면 첫 점으로 떨어진다.
    markers: tradeAt.flatMap((index, order) => {
      const at = points[index] ?? points[0];
      return at
        ? [
            trade(
              at.gameDate,
              order % 2 === 0 ? "BUY" : "SELL",
              at.close,
              10 * (order + 1)
            ),
          ]
        : [];
    }),
  };
};

/** 거래수가 제각각인 여덟 종목. 정렬과 '더 보기' 를 함께 본다. */
const MANY: ReportInstrument[] = [
  made(15, "KRW", 0, 58000, 0, [2, 5, 9, 12, 16, 20, 22]),
  made(3, "USD", 2, 124.5, 3, [1, 6, 11, 15, 19]),
  made(25, "KRW", 0, 2_140_000, 7, [4, 8, 13, 18]),
  made(19, "KRW", 0, 41500, 1, [3, 10, 17]),
  made(1, "USD", 2, 212.8, 5, [7, 14]),
  made(9, "USD", 2, 68.4, 9, [5, 21]),
  // 구간 중간에 상장한 종목. 앞이 비어 중간 상장이 그림으로 보인다.
  made(27, "USD", 2, 1.82, 2, [3, 8], 13),
  made(13, "KRW", 0, 33800, 4, [11]),
];

/** 같은 표본을 id 만 바꿔 한 벌 더. 종목이 많은 날을 만든다. */
const shifted = (item: ReportInstrument): ReportInstrument => ({
  ...item,
  instrumentId: item.instrumentId + 100,
  markers: item.markers.slice(0, 1),
});

/** 2020-01-01 ~ 2022-03-31 의 평일. 그 일차 구간의 실제 거래일 수에 가깝다. */
const DENSE_DATES = (() => {
  const out: string[] = [];
  for (
    let t = Date.UTC(2020, 0, 1);
    t <= Date.UTC(2022, 2, 31);
    t += 86_400_000
  ) {
    const day = new Date(t);
    if (day.getUTCDay() % 6 !== 0) out.push(day.toISOString().slice(0, 10));
  }
  return out;
})();

/** 560점짜리 흐름. 잔파동을 섞어야 촘촘할 때 어떻게 뭉개지는지가 보인다. */
const DENSE: ReportInstrument[] = [
  {
    instrumentId: 15,
    currency: "KRW",
    priceScale: 0,
    points: DENSE_DATES.map((gameDate, index) => ({
      gameDate,
      close: String(
        58000 *
          (1 +
            0.28 * Math.sin(index / 47) +
            0.09 * Math.sin(index / 11) +
            0.03 * Math.sin(index / 3))
      ),
    })),
    // 같은 주에 몰아친 셋(120·122·124)과 흩어진 넷.
    markers: [40, 120, 122, 124, 300, 410, 520].map((index, order) => ({
      executionId: 900 + index,
      gameDate: DENSE_DATES[index] ?? "",
      side: (order % 2 === 0 ? "BUY" : "SELL") as "BUY" | "SELL",
      quantity: 10,
      price: String(
        58000 *
          (1 +
            0.28 * Math.sin(index / 47) +
            0.09 * Math.sin(index / 11) +
            0.03 * Math.sin(index / 3))
      ),
    })),
  },
];

const TAGS: PatternTag[] = [
  {
    kind: "PANIC_SELL_LOW",
    executionId: 402,
    instrumentId: 15,
    changeRate: -8.4,
  },
  {
    kind: "PANIC_BUY_HIGH",
    executionId: 408,
    instrumentId: 3,
    changeRate: 11.2,
  },
];

/**
 * 종목별 매매 지점. 합성 곡선(`Sections/TradeReplayCard`)과 달리 **줄마다 제 가격 축**을
 * 써서 체결가가 곡선 위 제자리에 얹힌다.
 *
 * 기본 폭은 **3열 본문의 한 열**이다 (`daily-report.tsx` 의 `minmax(0,1fr)` — 1440px 에서
 * 약 33rem). 실제로 들어갈 자리보다 넓은 캔버스에서 보면 줄 수가 몇 개까지 감당되는지를
 * 잘못 판단하게 된다.
 */
const meta = {
  title: "Screens/Review/InstrumentReplay",
  component: InstrumentReplayList,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="w-[33rem] max-w-full bg-background p-4">
        <Story />
      </div>
    ),
  ],
  args: { instruments: MANY, patternTags: TAGS, nameOf },
} satisfies Meta<typeof InstrumentReplayList>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 실제로 들어갈 자리의 폭. 여덟 종목이 한 화면에 선다. */
export const InColumn: Story = {};

/** 종목이 많은 날. 넘치는 만큼은 스크롤이 받고 카드 높이는 그대로다. */
export const Many: Story = {
  args: { instruments: [...MANY, ...MANY.map(shifted)] },
};

/**
 * **진짜 밀도.** 1일차 구간이 2020-01 ~ 2022-03 이면 거래일이 약 560개다 (`rpt.md` 의 예시
 * 구간). 한 열에서 스파크라인 폭이 280px 남짓이니 **점 하나가 0.5px** 이고, 같은 주에 두 번
 * 체결하면 두 점이 1px 안에서 겹친다. 표본이 12점짜리면 절대 드러나지 않는 문제다.
 */
export const RealDensity: Story = {
  args: { instruments: DENSE, patternTags: [] },
};

/** 폰 폭. 이름·수익률 칸은 고정이고 줄어드는 것은 스파크라인이다. */
export const Narrow: Story = {
  decorators: [
    (Story) => (
      <div className="w-[22.5rem] bg-background p-3">
        <Story />
      </div>
    ),
  ],
};

/** 두 종목뿐인 날. 리포트 목 그대로다. */
export const FromFixture: Story = {
  args: { instruments: REPORT_INSTRUMENTS },
};

/** 체결이 하나도 없는 날. */
export const NoTrades: Story = {
  args: { instruments: [] },
};
