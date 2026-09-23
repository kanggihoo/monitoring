import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Card, CardContent } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { Series } from "@/stores/tick-store";

import { SERIES } from "./fixtures";
import {
  CHART_INTERVALS,
  CHART_RANGES,
  type ChartInterval,
  type ChartRange,
  PriceChart,
} from "./price-chart";

/**
 * 종가 선 그래프. **휠은 확대·축소, 드래그는 과거로 가는 이동**이고, 기간 탭은 데이터를
 * 자르는 대신 보이는 창만 옮긴다. 보유가 있으면 평단가가 점선 기준선으로 남는다.
 */
const meta = {
  title: "Screens/Market/PriceChart",
  component: PriceChart,
  tags: ["autodocs"],
  // `MarketScreen` 의 차트 칸과 같은 크기다 — 높이 `h-56` 은 화면이 못박아 둔 값이고,
  // 너비는 가운데 칸이 1440px 화면에서 갖는 폭이다. 여기서 넉넉하게 보면 실제에서 답답해진다.
  decorators: [
    (Story) => (
      <div className="w-[46.5rem] rounded-lg bg-card p-4">
        <div className="h-56">
          <Story />
        </div>
      </div>
    ),
  ],
  args: { series: SERIES, changeRate: 0, range: "3M", className: "h-full" },
} satisfies Meta<typeof PriceChart>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * **2020-01 ~ 2026-08 일봉.** 공용 `SERIES` 는 120봉이라 확대할 여지도 `전체` 의 부담도
 * 드러나지 않는다. 실제로 쌓일 양으로 봐야 이 컴포넌트가 푸는 문제가 보인다.
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
  return { instrumentId: 3, currency: "USD", priceScale: 2, points };
})();

/**
 * **만져 보는 판 — `MarketScreen` 의 차트 카드를 그대로 복제했다.**
 *
 * 크기와 마크업이 실제와 같아야 판단이 옮겨 간다. 그래서 `Card`·`CardContent`·`Tabs` 를
 * 화면과 같은 것으로 쓰고, 높이도 화면이 못박아 둔 `h-56` 을 그대로 쓴다.
 *
 * 손잡이는 둘이다 — **기간**(보이는 창)과 **봉**(며칠을 한 점으로). 탭을 바꿔도 데이터는
 * 그대로라, `1개월` 에서 왼쪽으로 끝까지 끌면 2020년까지 끊김 없이 이어진다.
 */
export const InMarketScreen: Story = {
  decorators: [],
  render: function Render(args) {
    const [range, setRange] = useState<ChartRange>("3M");
    const [interval, setInterval] = useState<ChartInterval>("D");
    return (
      <div className="w-[46.5rem]">
        <Card>
          <CardContent className="flex items-baseline gap-2">
            <span className="font-heading text-base font-medium">엔비디아</span>
            <span className="text-3xl font-semibold tabular-nums">
              {DAILY.points.at(-1)?.close}
            </span>
            <span className="text-xs text-muted-foreground">
              일봉 {DAILY.points.length.toLocaleString()}개 · 2020-01 ~ 2026-08
            </span>
          </CardContent>

          <CardContent className="flex flex-wrap items-center gap-2">
            <Tabs
              value={interval}
              onValueChange={(value) => setInterval(value as ChartInterval)}
              aria-label="봉 주기"
            >
              <TabsList variant="line">
                {CHART_INTERVALS.map((item) => (
                  <TabsTrigger key={item.value} value={item.value}>
                    {item.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
            <Tabs
              value={range}
              onValueChange={(value) => setRange(value as ChartRange)}
              aria-label="차트 기간"
              className="ml-auto"
            >
              <TabsList variant="line">
                {CHART_RANGES.map((item) => (
                  <TabsTrigger key={item.value} value={item.value}>
                    {item.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          </CardContent>

          {/* 화면과 같은 높이. 스크롤 영역 안이라 `flex-1` 로는 높이를 못 받는다. */}
          <CardContent className="h-56">
            <PriceChart
              {...args}
              series={DAILY}
              range={range}
              interval={interval}
              className="h-full"
            />
          </CardContent>
        </Card>
      </div>
    );
  },
  args: { avgPrice: "78.00", changeRate: 1.24 },
};

/** 보합. 직전 틱과 같으므로 선은 잉크다. */
export const Default: Story = {};

/** 직전 틱보다 올랐을 때. 선과 채움이 `price-up` 을 따른다. */
export const Up: Story = { args: { changeRate: 1.24 } };

/** 직전 틱보다 내렸을 때. */
export const Down: Story = { args: { changeRate: -0.87 } };

/** 휴장 — 그날 새 종가가 없다. 보합(0)과 다른 사실이라 색을 주장하지 않는다. */
export const MarketClosed: Story = { args: { changeRate: null } };

/** 6년치를 월봉으로. 점이 80개로 줄어 추세만 남는다. */
export const AllMonthly: Story = {
  args: { series: DAILY, range: "ALL", avgPrice: "78.00", changeRate: 1.24 },
};

/**
 * **묶지 않았을 때.** 같은 창에 일봉 1,700여 개를 그대로 밀어 넣은 모습이다. 점 하나가
 * 0.4px 라 하루치 움직임은 화면에 자리를 못 갖고, 선은 그만큼 두꺼운 띠처럼 뭉친다.
 *
 * 다만 `AllMonthly` 와 나란히 두고 보면 **차이는 읽힘이 아니라 밀도다** — 6년 추세는
 * 양쪽 다 보인다. 집계가 버는 것은 그리는 점의 수(1,739 → 80)와 잔움직임이 걷힌 선이지,
 * 못 읽던 것을 읽게 해 주는 것이 아니다.
 */
export const AllDailyUnaggregated: Story = {
  args: {
    series: DAILY,
    range: "ALL",
    interval: "D",
    avgPrice: "78.00",
    changeRate: 1.24,
  },
};

/** 1년 창은 `자동` 이면 주봉이 된다. */
export const OneYearWeekly: Story = {
  args: { series: DAILY, range: "1Y", avgPrice: "78.00", changeRate: 1.24 },
};

/** 가장 좁은 창. 일봉 그대로이고, 여기서도 왼쪽으로 끌면 과거가 이어진다. */
export const DailyOneMonth: Story = {
  args: { series: DAILY, range: "1M", avgPrice: "78.00", changeRate: 1.24 },
};

/** 보유 중. 평단가가 점선으로 남고 가격축에도 라벨이 붙는다. */
export const WithAvgPrice: Story = {
  args: { avgPrice: "72.40", changeRate: 1.24 },
};

/**
 * **평단가가 표시 구간 밖일 때.** 크게 물린 경우다 — 가격선은 원래 축의 자동 범위에
 * 안 잡히므로 화면 밖으로 사라진다. 여기서는 범위에 끼워 넣어 기준선이 살아 있다.
 */
export const AvgPriceOutsideRange: Story = {
  args: { avgPrice: "140.00", changeRate: -2.1 },
};

/**
 * **평단가가 현재가와 붙었을 때.** 본전 근처라는, 실제로 가장 자주 보게 될 상태다.
 * 라이브러리가 두 축 라벨을 위아래로 밀어내 둘 다 읽힌다.
 */
export const AvgPriceNearPrice: Story = {
  args: { avgPrice: "83.60", changeRate: 0.12 },
};

/** 정확히 본전. 두 라벨이 완전히 포개질 자리인데, 역시 밀려나 쌓인다. */
export const AvgPriceAtPrice: Story = {
  args: { avgPrice: "83.88", changeRate: 0 },
};

/** 손실 구간. 선은 내림색인데 평단가는 잉크다 — 오늘의 방향과 내 원가는 다른 사실이다. */
export const Underwater: Story = {
  args: { avgPrice: "95.00", changeRate: -0.87 },
};

/** 미보유. 평단가가 없으니 기준선도 없다. */
export const NoPosition: Story = { args: { avgPrice: null } };

/** 원화 종목은 자릿수 0. 평단가 라벨도 같은 자릿수를 따른다. */
export const Krw: Story = {
  args: {
    avgPrice: "68000",
    series: {
      ...SERIES,
      instrumentId: 15,
      currency: "KRW",
      priceScale: 0,
      points: SERIES.points.map((p) => ({
        ...p,
        close: String(Math.round(Number(p.close) * 900)),
      })),
    },
  },
};

/**
 * 상장 직후라 데이터가 창보다 짧다. `3개월` 을 골라도 있는 만큼만 보여 준다 —
 * 없는 과거를 빈 공간으로 채우면 시세가 끊긴 것처럼 읽힌다.
 */
export const ShorterThanRange: Story = {
  args: {
    avgPrice: "62.00",
    series: { ...SERIES, points: SERIES.points.slice(0, 6) },
  },
};

export const Empty: Story = { args: { series: { ...SERIES, points: [] } } };

export const Dark: Story = {
  args: { series: DAILY, avgPrice: "78.00", changeRate: 1.24 },
  globals: { theme: "dark" },
};
