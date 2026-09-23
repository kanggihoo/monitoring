"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";
import {
  AreaSeries,
  type AutoscaleInfo,
  ColorType,
  createChart,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  LineStyle,
} from "lightweight-charts";

import type { Series } from "@/stores/tick-store";

/** 차트가 읽는 토큰. 색은 DESIGN.md 가 정하고 여기서는 이름만 안다. */
const TOKENS = {
  line: "--chart-line",
  up: "--price-up",
  down: "--price-down",
  grid: "--chart-grid",
  text: "--muted-foreground",
  border: "--border",
  card: "--card",
} as const;

function readTokens(element: HTMLElement) {
  const style = getComputedStyle(element);
  return Object.fromEntries(
    Object.entries(TOKENS).map(([key, name]) => [
      key,
      style.getPropertyValue(name).trim(),
    ])
  ) as Record<keyof typeof TOKENS, string>;
}

/** `#rrggbb` 에 투명도를 얹는다. 캔버스는 `color-mix` 를 모른다. */
function withAlpha(hex: string, alpha: number): string {
  const value = hex.replace("#", "");
  if (value.length !== 6) return hex;
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16));
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/** 선 색이 나르는 방향. 보합은 색을 쓰지 않고 잉크로 남는다. */
type Direction = "up" | "down" | "flat";

/**
 * **직전 틱 종가 대비 방향.** 헤더의 등락률(`PriceEntry.changeRate`)이 보는 것과 같은
 * 기준선이다 — 한 카드 안에서 숫자와 선이 서로 다른 것을 비교하면 반대를 말하게 된다.
 * **휴장은 보합이 아니다** — `null` 이면 비교할 것이 없으므로 색을 주장하지 않는다.
 */
function directionOf(changeRate: number | null | undefined): Direction {
  if (changeRate === null || changeRate === undefined) return "flat";
  if (!Number.isFinite(changeRate)) return "flat";

  if (changeRate > 0) return "up";
  if (changeRate < 0) return "down";
  return "flat";
}

/** 평단가는 계약상 문자열이다 (`PositionItem.avgPrice`). 미보유면 아예 오지 않는다. */
function toAvg(avgPrice: string | number | null | undefined): number | null {
  if (avgPrice === null || avgPrice === undefined || avgPrice === "") {
    return null;
  }
  const value = Number(avgPrice);
  return Number.isFinite(value) && value > 0 ? value : null;
}

/** 창의 너비. 값은 개월 수이고, `ALL` 만 전체를 뜻한다. */
export type ChartRange = "1M" | "3M" | "1Y" | "ALL";

export const CHART_RANGES: { value: ChartRange; label: string }[] = [
  { value: "1M", label: "1개월" },
  { value: "3M", label: "3개월" },
  { value: "1Y", label: "1년" },
  { value: "ALL", label: "전체" },
];

const RANGE_MONTHS: Record<ChartRange, number | null> = {
  "1M": 1,
  "3M": 3,
  "1Y": 12,
  ALL: null,
};

/** `yyyy-mm-dd` 에서 개월을 뺀다. 거래일 개수로 환산하지 않는 이유는 아래에 있다. */
function monthsBefore(gameDate: string, months: number): string {
  const date = new Date(`${gameDate}T00:00:00Z`);
  date.setUTCMonth(date.getUTCMonth() - months);
  return date.toISOString().slice(0, 10);
}

/**
 * 봉 주기. **`ChartRange`(보이는 창)와는 다른 축의 손잡이다** — 증권사 HTS 가 `일 주 월`
 * 과 조회 기간을 따로 두는 것과 같다. MTS·웹은 둘을 버튼 한 줄로 합쳐 두는데, 그때
 * 주기는 기간을 따라 자동으로 정해진다. 그 자동이 `"auto"` 다.
 */
export type ChartInterval = "D" | "W" | "M" | "auto";

/** 사용자에게 내놓는 주기. `auto` 는 여기 없다 — 고르는 것이 아니라 기본값이다. */
export const CHART_INTERVALS: {
  value: Exclude<ChartInterval, "auto">;
  label: string;
}[] = [
  { value: "D", label: "일봉" },
  { value: "W", label: "주봉" },
  { value: "M", label: "월봉" },
];

/**
 * 창이 넓어지면 주기도 굵어진다. 6년치 일봉을 750px 에 밀어 넣으면 점 하나가 0.4px 라
 * 하루치 움직임은 화면에 자리를 못 갖고, 선은 그만큼 두꺼운 띠처럼 뭉친다.
 *
 * **추세를 못 읽게 되는 것은 아니다** — 버는 것은 그리는 점의 수와 걷힌 잔움직임이다.
 * 그래서 이 표는 규칙이 아니라 기본값이고, `interval` 을 주면 언제든 뒤집힌다.
 */
const AUTO_INTERVAL: Record<ChartRange, Exclude<ChartInterval, "auto">> = {
  "1M": "D",
  "3M": "D",
  "1Y": "W",
  ALL: "M",
};

/** 같은 봉에 들어가는 날들이 공유하는 열쇠. 주는 그 주의 월요일, 달은 `yyyy-mm`. */
function bucketOf(gameDate: string, interval: "W" | "M"): string {
  if (interval === "M") return gameDate.slice(0, 7);

  const date = new Date(`${gameDate}T00:00:00Z`);
  // `getUTCDay()` 는 일요일이 0 이다. 주를 월요일에서 끊으려고 한 칸 돌린다.
  date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7));
  return date.toISOString().slice(0, 10);
}

/**
 * 여러 거래일을 한 봉으로 묶는다 — **각 구간의 마지막 종가만 남긴다.**
 *
 * **이것은 캔들이 아니다.** 캔들을 만들려면 시·고·저가 있어야 하는데 이 게임의 가격
 * 모델에는 종가밖에 없다 (`Series.points`). 그래서 `월봉` 이라 해도 그 달의 고점·저점은
 * 애초에 없던 정보이지 여기서 버리는 정보가 아니다. 선 그래프가 묻는 것은 그 구간이
 * 어디서 끝났는가이고, 그 답은 마지막 종가가 온전히 갖고 있다.
 *
 * 봉의 날짜는 **묶인 마지막 거래일 그대로** 쓴다. 달의 1일 같은 대표값으로 바꾸면 축이
 * 거래가 없던 날을 가리키게 된다.
 */
function aggregate(points: Series["points"], interval: "W" | "M") {
  const out: Series["points"] = [];
  let key = "";
  for (const point of points) {
    const next = bucketOf(point.gameDate, interval);
    if (next === key) out[out.length - 1] = point;
    else {
      out.push(point);
      key = next;
    }
  }
  return out;
}

/**
 * 종가 선 그래프 — 만질 수 있는 판.
 *
 * **시리즈 전체를 받아 두고 보이는 창만 옮긴다.** 2020년부터 쌓인 일봉을 한 화면에
 * 밀어 넣으면 점 하나가 1픽셀도 못 가져 선이 뭉개지고, 무엇보다 **드래그로 갈 곳이
 * 없어진다** — 이미 다 보이는 그림은 끌 이유가 없다. 그래서 처음부터 최근 구간만
 * 띄우고, 나머지는 왼쪽으로 끌어서 만난다.
 *
 * **휠은 시간축 확대·축소, 드래그는 과거로 가는 이동이다.** 증권 앱의 손버릇이 그렇다.
 * 휠에 가로 스크롤까지 겸하게 하면 한 입력을 두 동작이 다투므로 확대만 준다.
 *
 * 양 끝은 `fixLeftEdge`·`fixRightEdge` 로 막는다 — 데이터 밖 빈 공간까지 끌려가면
 * 시세가 끊긴 것으로 읽힌다.
 *
 * **평단가 선은 보유가 있을 때만 그린다.** 손익의 기준선이라 가격축 라벨로도 남기고,
 * 축의 자동 범위에도 끼워 넣는다 — 화면 밖으로 밀려난 기준선은 없는 것과 같다.
 */
export function PriceChart({
  series,
  changeRate,
  avgPrice,
  range = "3M",
  interval = "auto",
  className,
}: {
  /**
   * **자르지 않은 전체 시리즈.** 범위는 `range` 가 창으로 정한다.
   */
  series: Series | null;
  /** 직전 틱 종가 대비 등락률. 선 색이 이것을 따른다. 휴장이면 `null`. */
  changeRate: number | null | undefined;
  /** 보유 평단가 (`PositionItem.avgPrice`). 미보유면 `null`·`undefined` 라 선이 없다. */
  avgPrice?: string | number | null;
  /**
   * 보이는 창의 너비.
   *
   * **본판과 다른 점이 여기다.** 본판은 탭에 맞춰 시리즈를 잘라 넘기므로, 탭을 바꿀
   * 때마다 차트가 데이터를 통째로 다시 받고 그 앞은 존재하지 않는 것이 된다. 여기서는
   * 데이터를 그대로 둔 채 **바 간격만 바꿔** 창을 넓히고 좁힌다. 그래서 `1개월` 에서도
   * 왼쪽으로 끌면 2020년까지 끊김 없이 이어지고, 탭 전환이 데이터 재적재가 아니다.
   */
  range?: ChartRange;
  /**
   * 봉 주기. 기본값 `"auto"` 는 창에 맞춰 일·주·월을 고른다 (`AUTO_INTERVAL`).
   *
   * **창과 주기는 다른 축이다.** 창은 얼마만큼을 보는가이고, 주기는 며칠을 한 점으로
   * 묶는가다. 6년치를 일 단위로 보면 톱니가 되고, 1개월을 월 단위로 보면 점이 한둘만
   * 남는다 — 그래서 기본은 둘을 따로 두되 서로 맞춰 움직이게 했다.
   */
  interval?: ChartInterval;
  className?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const areaRef = useRef<ISeriesApi<"Area"> | null>(null);

  /** 지금 선이 나르는 방향. 색칠하는 쪽이 마운트 효과라 ref 로 건넨다. */
  const directionRef = useRef<Direction>("flat");
  /** 마운트 효과가 만든 색칠 함수. 다른 효과들이 같은 함수를 다시 부른다. */
  const applyColorsRef = useRef<() => void>(() => {});
  /** 지금 걸려 있는 평단가 선. 값이 바뀌면 지우고 다시 만든다. */
  const avgLineRef = useRef<IPriceLine | null>(null);
  /** 가격축 자동 범위 콜백이 읽는 평단가. 매번 최신값을 봐야 해서 ref 다. */
  const avgRef = useRef<number | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const chart = createChart(container, {
      autoSize: true,
      // 휠은 확대·축소 한 가지로만 쓴다. 이동은 드래그가 맡는다.
      handleScroll: {
        mouseWheel: false,
        pressedMouseMove: true,
        horzTouchDrag: true,
        vertTouchDrag: false,
      },
      handleScale: {
        mouseWheel: true,
        pinch: true,
        axisPressedMouseMove: { time: true, price: false },
        axisDoubleClickReset: { time: true, price: true },
      },
      kineticScroll: { mouse: false, touch: true },
      // `entireTextOnly` — 맨 위·아래 눈금은 글자가 온전히 들어갈 때만 그린다.
      rightPriceScale: { borderVisible: false, entireTextOnly: true },
      timeScale: {
        borderVisible: false,
        fixLeftEdge: true,
        fixRightEdge: true,
        /**
         * **`전체` 가 실제로 전체가 되려면 이것을 내려야 한다.** 기본 하한은 바당 0.5px
         * 이라, 6년치 일봉(1,700여 점)은 800px 남짓한 카드에 끝까지 축소되지 않는다.
         * 창을 아무리 넓혀도 앞부분이 왼쪽 밖에 남아 `전체` 가 전체를 못 보여 준다.
         */
        minBarSpacing: 0.02,
        // 창 너비를 바꾸는 것이 범위 탭의 일이다. 리사이즈가 그것을 흔들지 않게 한다.
        lockVisibleTimeRangeOnResize: true,
      },
      crosshair: { horzLine: { visible: false } },
    });
    const area = chart.addSeries(AreaSeries, {
      lineWidth: 2,
      priceLineVisible: true,
      lastValueVisible: true,
      crosshairMarkerRadius: 3,
      /**
       * 평단가를 가격축 자동 범위에 끼워 넣는다. 라이브러리는 가격선을 범위 계산에서
       * 빼므로, 평단가가 표시 구간의 고·저 밖이면 기준선이 화면 밖으로 밀려 사라진다.
       */
      autoscaleInfoProvider: (
        original: () => AutoscaleInfo | null
      ): AutoscaleInfo | null => {
        const info = original();
        const avg = avgRef.current;
        if (!info?.priceRange || avg === null) return info;
        return {
          ...info,
          priceRange: {
            minValue: Math.min(info.priceRange.minValue, avg),
            maxValue: Math.max(info.priceRange.maxValue, avg),
          },
        };
      },
    });
    chartRef.current = chart;
    areaRef.current = area;

    const applyColors = () => {
      const token = readTokens(container);
      const stroke =
        directionRef.current === "up"
          ? token.up
          : directionRef.current === "down"
            ? token.down
            : token.line;

      chart.applyOptions({
        layout: {
          background: { type: ColorType.Solid, color: token.card },
          textColor: token.text,
          attributionLogo: false,
        },
        grid: {
          vertLines: { color: token.grid },
          horzLines: { color: token.grid },
        },
        crosshair: {
          vertLine: { color: token.border, labelBackgroundColor: stroke },
        },
      });
      area.applyOptions({
        lineColor: stroke,
        priceLineColor: stroke,
        topColor: withAlpha(stroke, 0.16),
        bottomColor: withAlpha(stroke, 0),
      });
      // 평단가는 등락색을 쓰지 않는다 — 오늘의 방향이 아니라 내 원가라는 다른 사실이다.
      avgLineRef.current?.applyOptions({
        color: token.text,
        axisLabelColor: token.text,
        axisLabelTextColor: token.card,
      });
    };
    applyColorsRef.current = applyColors;
    applyColors();

    // 테마 전환은 <html> 의 class 로 온다. OS 다크 전환은 미디어 쿼리로 온다.
    const observer = new MutationObserver(applyColors);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["class"],
    });
    const media = matchMedia("(prefers-color-scheme: dark)");
    media.addEventListener("change", applyColors);

    return () => {
      observer.disconnect();
      media.removeEventListener("change", applyColors);
      chart.remove();
      chartRef.current = null;
      areaRef.current = null;
      avgLineRef.current = null;
      applyColorsRef.current = () => {};
    };
  }, []);

  /**
   * 차트가 실제로 그리는 점들. 주기가 `일` 이면 원본 그대로이고, 아니면 묶인 것이다.
   * 창을 맞추는 쪽도 같은 배열을 봐야 한다 — 서로 다른 배열을 보면 창의 끝과 화면의
   * 끝이 어긋난다.
   */
  const points = useMemo(() => {
    const raw = series?.points ?? [];
    const unit = interval === "auto" ? AUTO_INTERVAL[range] : interval;
    return unit === "D" ? raw : aggregate(raw, unit);
  }, [series, interval, range]);

  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;

    area.applyOptions({
      priceFormat: {
        type: "price",
        precision: series?.priceScale ?? 2,
        minMove: 10 ** -(series?.priceScale ?? 2),
      },
    });
    area.setData(
      points.map((point) => ({
        time: point.gameDate,
        value: Number(point.close),
      }))
    );
  }, [series?.priceScale, points]);

  /**
   * 창을 범위에 맞춘다. **시간으로 자르지 거래일 개수로 세지 않는다** — `1개월` 을
   * 22봉 같은 수로 환산하면 휴장이 잦은 구간에서 창이 실제보다 길어진다. 달력이 묻는
   * 것은 며칠 전이지 몇 번째 봉이 아니다.
   *
   * **최신으로 돌아오는 길은 따로 두지 않는다.** 오른쪽으로 끌면 그만이고, `fixRightEdge`
   * 가 마지막 봉 너머로는 안 보내므로 지나칠 수도 없다. 증권 차트가 되돌리기 버튼을
   * 두지 않는 이유와 같다 — 끌어서 간 것은 끌어서 돌아온다.
   */
  const applyWindow = useCallback(() => {
    const chart = chartRef.current;
    if (!chart || points.length === 0) return;

    const months = RANGE_MONTHS[range];
    const first = points.at(0);
    const last = points.at(-1);
    if (months === null || !first || !last) {
      chart.timeScale().fitContent();
      return;
    }
    const from = monthsBefore(last.gameDate, months);
    // 창이 데이터보다 넓으면 (상장 직후 종목) 있는 만큼만 보여 준다.
    if (from <= first.gameDate) {
      chart.timeScale().fitContent();
      return;
    }
    chart.timeScale().setVisibleRange({ from, to: last.gameDate });
  }, [points, range]);

  /** 시리즈가 갈리거나 탭이 바뀌면 창을 다시 맞춘다. */
  useEffect(() => {
    applyWindow();
  }, [applyWindow]);

  /** 색은 **시리즈가 아니라 등락률을 따라** 다시 칠한다. 점이 안 늘어도 방향은 바뀐다. */
  useEffect(() => {
    directionRef.current = directionOf(changeRate);
    applyColorsRef.current();
  }, [changeRate]);

  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;

    if (avgLineRef.current) {
      area.removePriceLine(avgLineRef.current);
      avgLineRef.current = null;
    }
    const avg = toAvg(avgPrice);
    avgRef.current = avg;

    if (avg !== null) {
      avgLineRef.current = area.createPriceLine({
        price: avg,
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: "평단가",
        color: "",
      });
      applyColorsRef.current();
    }
    // 평단가가 범위 밖이었다면 자동 범위를 다시 돌려야 선이 화면 안으로 들어온다.
    area.applyOptions({});
  }, [avgPrice]);

  return (
    <div className={`relative ${className ?? ""}`}>
      <div ref={containerRef} className="h-full w-full" />
      {series && series.points.length === 0 && (
        <p className="pointer-events-none absolute inset-x-0 top-1/2 text-center text-xs text-muted-foreground">
          아직 확정된 종가가 없다.
        </p>
      )}
    </div>
  );
}
