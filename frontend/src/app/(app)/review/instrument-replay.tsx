"use client";

import { useEffect, useState } from "react";

import { PATTERN_TAG_LABEL } from "@/components/market/labels";
import { PriceChange } from "@/components/market/price-change";
import { Card, CardContent } from "@/components/ui/card";
import { gameDay, localPrice } from "@/lib/format";
import type {
  PatternTag,
  ReportInstrument,
  ReportMarker,
} from "@/lib/market-types";
import { cn } from "@/lib/utils";

/**
 * 종목별 매매 지점 (기획 13.1.2 "당일 가격 흐름 위에 매수·매도 지점을 마커로 표시").
 *
 * **합성 곡선과 다른 그림이다.** `sections.tsx` 의 `TradeReplayCard` 는 거래한 종목을 전부
 * 배수로 정규화해 평균한 흐름 하나를 그리고 그 위에 체결을 찍는데, 그 곡선의 높이는 평균
 * 배수라 **체결가와 아무 관계가 없다** — 5만 원에 산 것과 7만 원에 산 것이 같은 자리에
 * 찍히고, 같은 날 세 종목을 거래하면 세 점이 정확히 겹친다. "고점에 샀다" 는 판정
 * (`PANIC_BUY_HIGH`)이 태그로만 남고 그림에는 나타나지 않는 것도 그래서다.
 *
 * 여기서는 **종목마다 제 가격 축**을 쓴다. 그러면 `marker.price` 가 곡선 위 제자리에 얹혀,
 * 꼭대기에서 샀는지 바닥에서 팔았는지가 그림 자체로 읽힌다. 정규화·평균이 사라지므로 계산도
 * 오히려 줄어든다.
 *
 * **한 줄에 한 종목이다.** 카드를 격자로 깔아 봤더니 3열 본문(`daily-report.tsx`)의 한 열이
 * 감당하는 폭이 아니었다 — 카드 하나가 열 폭의 절반을 먹어 여섯 종목이면 세로로 세 칸이
 * 된다. 줄로 눕히면 스파크라인이 열 폭을 거의 다 쓰면서 한 줄이 44px 이라, 같은 자리에
 * 여덟 종목이 들어간다. 종목이 늘어도 세로로만 자라고 그만큼은 스크롤이 받는다.
 */

/** 선이 그려지는 시간. 마커는 선이 제 날짜에 닿는 순간에 맞춰 뜬다. */
const DRAW_MS = 900;

/**
 * 그림이 상자 안쪽으로 물러나는 여백(%). **점이 잘리지 않게 하는 값이다** — 곡선이 끝에
 * 닿으면 그 자리의 체결 점이 `-translate-*` 만큼 상자 밖으로 나가 반달로 그려진다.
 */
const INSET = 12;

/** 게임 거래일을 x 축 좌표로 쓰기 위한 수치. 문자열 비교로는 간격을 못 잰다. */
const dayValue = (gameDate: string) => Date.parse(`${gameDate}T00:00:00Z`);

export function InstrumentReplayList({
  instruments,
  patternTags,
  nameOf,
  emptyNote = "이 날 체결한 거래가 없다.",
  className,
}: {
  instruments: ReportInstrument[];
  /** 체결에 붙은 판정. 종목 이름 아래에 단다. */
  patternTags: PatternTag[];
  nameOf: (instrumentId: number) => string;
  emptyNote?: string;
  className?: string;
}) {
  /**
   * **거래수 순이다.** 그날 제일 많이 만진 종목이 그날의 이야기이므로 위에서부터 그것이
   * 온다. 같은 건수면 `instrumentId` 로 갈라 순서가 렌더마다 흔들리지 않게 한다.
   */
  const ordered = [...instruments].sort(
    (a, b) =>
      b.markers.length - a.markers.length || a.instrumentId - b.instrumentId
  );

  /**
   * **x 축은 모든 줄이 공유한다.** 줄마다 제 데이터 폭에 맞추면, 그 일차 중간에 상장한
   * IPO 종목의 왼쪽 끝이 다른 줄의 왼쪽 끝과 다른 날짜가 되어 위아래로 비교할 수 없다.
   * 구간 전체를 축으로 두면 늦게 시작한 종목은 앞이 비어, 중간 상장이 그림으로 보인다.
   */
  const dates = instruments.flatMap((item) =>
    item.points.map((point) => dayValue(point.gameDate))
  );
  const from = dates.length > 0 ? Math.min(...dates) : 0;
  const to = dates.length > 0 ? Math.max(...dates) : 0;

  const tagOf = (executionId: number) =>
    patternTags.find((tag) => tag.executionId === executionId)?.kind ?? null;

  return (
    <Card size="sm" className={cn("min-h-0", className)}>
      <CardContent className="flex items-center justify-between gap-3">
        <span className="text-2xs tracking-wider text-muted-foreground">
          종목별 매매 지점 · 거래수 순
        </span>
        {/* 색만으로 매수·매도를 가르지 않는다 (DESIGN.md) — 채운 점과 빈 점으로도 갈린다. */}
        <span className="flex shrink-0 items-center gap-2 text-2xs text-muted-foreground">
          <span className="flex items-center gap-1">
            <span className="size-1.5 rounded-full bg-order-buy" />
            매수
          </span>
          <span className="flex items-center gap-1">
            <span className="size-1.5 rounded-full border border-order-sell bg-background" />
            매도
          </span>
        </span>
      </CardContent>

      {ordered.length === 0 ? (
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          {emptyNote}
        </CardContent>
      ) : (
        // 넘치면 스크롤이 받는다. 거래수 순이라 위에 보이는 것이 그날의 이야기다.
        <CardContent className="flex max-h-80 flex-col divide-y divide-border overflow-y-auto">
          {ordered.map((instrument) => (
            <InstrumentRow
              key={instrument.instrumentId}
              instrument={instrument}
              name={nameOf(instrument.instrumentId)}
              from={from}
              to={to}
              tagOf={tagOf}
            />
          ))}
        </CardContent>
      )}
    </Card>
  );
}

/** 한 종목의 구간 흐름과 그 위의 체결. 한 줄이다. */
function InstrumentRow({
  instrument,
  name,
  from,
  to,
  tagOf,
}: {
  instrument: ReportInstrument;
  name: string;
  from: number;
  to: number;
  tagOf: (executionId: number) => PatternTag["kind"] | null;
}) {
  /**
   * 그려진 상태인가. **선을 `clip-path` 트랜지션으로 긋는다** — keyframes 를 새로 두지
   * 않으려는 것이다(`globals.css` 는 DESIGN.md 에서 생성되는 산출물이라 손대지 않는다,
   * ADR-0002). 마운트 뒤 한 프레임 지나 참이 되고, 모션을 줄인 사용자에게는 트랜지션이
   * 꺼져 **완성된 그림이 즉시** 선다.
   *
   * **`stroke-dasharray` 로 긋지 않는다.** 그 방식은 `pathLength={1}` 로 길이를 정규화해야
   * 하는데, 아래 `vector-effect="non-scaling-stroke"` 가 대시를 화면 좌표에서 재게 만들어
   * 둘이 어긋난다 — 선 끝이 잘려 마지막 체결 점이 곡선에서 떨어져 나온 것처럼 보였다.
   * 길이를 재서 맞출 수도 있지만(ref + `getTotalLength()`), 가릴 자리를 옮기는 쪽이 잴
   * 것이 없다.
   */
  const [drawn, setDrawn] = useState(false);
  useEffect(() => {
    // 두 프레임을 기다린다. 한 프레임이면 첫 페인트 전에 값이 바뀌어 트랜지션이 생략된다.
    const frame = requestAnimationFrame(() =>
      requestAnimationFrame(() => setDrawn(true))
    );
    return () => cancelAnimationFrame(frame);
  }, []);

  const { points, markers, currency, priceScale } = instrument;
  const first = Number(points[0]?.close ?? 0);
  const last = Number(points.at(-1)?.close ?? 0);
  const rate = first > 0 ? ((last - first) / first) * 100 : 0;

  /**
   * y 축은 **이 종목의 가격**이다. 체결가까지 넣어 범위를 잡는다 — 종가 시계열 밖의 값은
   * 나오지 않아야 정상이지만, 벗어난 값이 오면 점이 줄 밖에 찍히는 대신 축이 늘어난다.
   */
  const values = [
    ...points.map((point) => Number(point.close)),
    ...markers.map((marker) => Number(marker.price)),
  ];
  const low = Math.min(...values);
  const high = Math.max(...values);
  const span = high - low || 1;
  const x = (gameDate: string) =>
    to === from
      ? 50
      : INSET + ((dayValue(gameDate) - from) / (to - from)) * (100 - INSET * 2);
  const y = (value: number) =>
    100 - INSET - ((value - low) / span) * (100 - INSET * 2);

  const path = points
    .map(
      (point, index) =>
        `${index === 0 ? "M" : "L"}${x(point.gameDate).toFixed(2)} ${y(Number(point.close)).toFixed(2)}`
    )
    .join(" ");

  const label = (marker: ReportMarker) =>
    `${gameDay(marker.gameDate)} ${marker.side === "BUY" ? "매수" : "매도"} ${marker.quantity}주 @ ${localPrice(marker.price, currency, priceScale)}`;

  /** 이 종목 체결에 붙은 판정. 줄에는 첫 하나만 적는다 — 자리가 한 줄뿐이다. */
  const tag = markers
    .map((marker) => tagOf(marker.executionId))
    .find((kind) => kind !== null);

  return (
    <div className="flex items-center gap-2 py-1">
      <span className="flex w-24 shrink-0 flex-col">
        <span className="truncate text-xs font-medium">{name}</span>
        {/* 건수는 **항상** 둔다. 판정이 붙은 줄에서만 사라지면 그 줄의 무게를 읽을 수 없다. */}
        <span className="truncate text-2xs text-muted-foreground">
          <span className="tabular-nums">{markers.length}건</span>
          {tag && ` · ${PATTERN_TAG_LABEL[tag]}`}
        </span>
      </span>

      <div
        className="relative h-9 min-w-0 flex-1 text-muted-foreground"
        role="img"
        aria-label={`${name} 구간 흐름과 체결 ${markers.length}건: ${markers.map(label).join(", ")}`}
      >
        {/* `preserveAspectRatio="none"` 이라 선 굵기도 늘어난다 —
            `vector-effect` 가 그것만 막는다. */}
        <svg
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          className="size-full transition-[clip-path] ease-out motion-reduce:transition-none"
          style={{
            clipPath: drawn ? "inset(0 0 0 0)" : "inset(0 100% 0 0)",
            transitionDuration: `${DRAW_MS}ms`,
          }}
          aria-hidden
        >
          <path
            d={path}
            fill="none"
            stroke="currentColor"
            strokeWidth={1.25}
            strokeLinejoin="round"
            vectorEffect="non-scaling-stroke"
          />
        </svg>

        {markers.map((marker) => (
          <span
            key={marker.executionId}
            title={label(marker)}
            style={{
              left: `${x(marker.gameDate)}%`,
              top: `${y(Number(marker.price))}%`,
              // 선이 그 날짜를 지나는 순간에 맞춰 뜬다.
              transitionDelay: `${Math.round((x(marker.gameDate) / 100) * DRAW_MS)}ms`,
            }}
            className={cn(
              // **배경색 테두리를 두른다.** 1일차 구간이 26개월이면 점 하나가 0.5px 이라,
              // 같은 주에 두세 번 체결하면 점이 겹쳐 하나가 통째로 가려진다. 테두리가
              // 있으면 겹친 동전처럼 보여 몇 개가 뭉쳤는지 읽힌다 — 자리를 옮겨 떼어
              // 놓으면 "언제 샀나" 가 거짓이 되므로 위치는 그대로 둔다.
              "absolute size-2 -translate-x-1/2 -translate-y-1/2 rounded-full border ring-1 ring-background transition-opacity duration-200 motion-reduce:transition-none motion-reduce:delay-0",
              // 색은 가격 방향이 아니라 **주문 의도**다 (`docs/CONTEXT.md`).
              marker.side === "BUY"
                ? "border-order-buy bg-order-buy"
                : "border-order-sell bg-background",
              drawn ? "opacity-100" : "opacity-0"
            )}
          />
        ))}
      </div>

      <PriceChange
        value={rate}
        className="w-[4.5rem] shrink-0 justify-end text-xs"
      />
    </div>
  );
}
