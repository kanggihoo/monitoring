import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { localPrice } from "@/lib/format";
import type { InstrumentMeta, PriceEntry } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { PriceChange } from "./price-change";

const TOP = 5;

type Row = {
  instrument: InstrumentMeta;
  price: PriceEntry & { changeRate: number };
};

/**
 * 등락 보드 시안 (v2). 계산은 v1 과 같고 표현만 다르다 — 막대 대신 종가 흐름을 그리고,
 * 줄 전체를 `/market` 의 그 종목으로 가는 링크로 둔다.
 *
 * **`series` 는 옵션이다.** 대시보드는 아직 종목별 종가를 들고 있지 않다 (`PriceEntry` 에
 * 시리즈가 없고 `closes` 는 종목당 한 번이다). 없으면 흐름 칸만 빠지고 나머지는 그대로다.
 *
 * @param series 종목별 종가. 오래된 순이며 2개 미만이면 그리지 않는다
 */
export function MoversBoardV2({
  instruments,
  prices,
  series,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  instruments: InstrumentMeta[];
  prices: Record<number, PriceEntry>;
  series?: Record<number, string[]>;
}) {
  const rows = instruments
    .map((instrument) => ({
      instrument,
      price: prices[instrument.instrumentId],
    }))
    .filter(
      (row): row is Row =>
        row.price !== undefined && row.price.changeRate !== null
    )
    .sort((a, b) => b.price.changeRate - a.price.changeRate);

  const gainers = rows.slice(0, TOP).filter((row) => row.price.changeRate > 0);
  const losers = rows
    .slice(-TOP)
    .reverse()
    .filter((row) => row.price.changeRate < 0);

  return (
    <Card size="sm" className={cn("w-full", className)} {...props}>
      <CardHeader className="items-center">
        <CardTitle className="text-lg">오늘의 등락 TOP {TOP}</CardTitle>
        {/* v1 에서는 이 버튼이 카드 밖 대시보드에 따로 있었다. 표가 둘로 갈리면서
            머리를 공유하게 돼 카드 안으로 들어왔다. */}
        <CardAction>
          <Link
            href="/"
            className={buttonVariants({ variant: "outline", size: "sm" })}
          >
            더보기
            <span aria-hidden>→</span>
          </Link>
        </CardAction>
      </CardHeader>

      {rows.length === 0 ? (
        <CardContent className="py-6 text-center text-xs text-muted-foreground">
          다음 종가를 기다리는 중.
        </CardContent>
      ) : (
        <CardContent className="grid gap-4 border-t border-border pt-4 md:grid-cols-2">
          <Column title="상승 종목 (+)" rows={gainers} series={series} up />
          <Column title="하락 종목 (−)" rows={losers} series={series} />
        </CardContent>
      )}
    </Card>
  );
}

const GRID =
  "grid grid-cols-[1.25rem_minmax(0,1fr)_3rem_4.75rem_4.25rem] gap-1.5";

function Column({
  title,
  rows,
  series,
  up = false,
}: {
  title: string;
  rows: Row[];
  series?: Record<number, string[]>;
  up?: boolean;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-2">
      {/* 갈래 이름은 색을 띤 알약이다. 파랑·빨강을 등락 밖에 쓰지 않는다는 규칙은
          지킨다 — 이 알약이 가리키는 것이 바로 등락이다 (DESIGN.md). */}
      <span
        className={cn(
          "w-fit rounded-full px-3 py-1 text-sm font-semibold",
          up
            ? "bg-price-up-surface text-price-up"
            : "bg-price-down-surface text-price-down"
        )}
      >
        {title}
      </span>

      <div className="overflow-hidden rounded-lg border border-border">
        <div
          className={cn(
            GRID,
            "items-center bg-muted/50 px-3 py-2 text-2xs text-muted-foreground"
          )}
        >
          <span>#</span>
          <span className="col-span-2">종목명</span>
          <span className="text-right">현재가</span>
          <span className="text-right">등락률</span>
        </div>

        {rows.length === 0 ? (
          <p className="px-3 py-6 text-center text-xs text-muted-foreground">
            없음
          </p>
        ) : (
          rows.map(({ instrument, price }, index) => {
            const points = series?.[instrument.instrumentId] ?? [];

            return (
              // 줄 전체가 링크다. `/market` 은 `instrumentId` 질의를 읽어 그 종목을 펴고
              // (`market-screen.tsx`), Next 가 hover 에서 미리 받아 둔다.
              <Link
                key={instrument.instrumentId}
                href={`/?instrumentId=${instrument.instrumentId}`}
                className={cn(
                  GRID,
                  "items-center border-t border-border px-3 py-2 text-sm",
                  "transition-colors hover:bg-accent focus-visible:bg-accent",
                  "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                )}
              >
                <span className="text-muted-foreground tabular-nums">
                  {index + 1}
                </span>
                <span className="truncate font-medium" title={instrument.name}>
                  {instrument.name}
                </span>
                <Sparkline points={points} up={price.changeRate > 0} />
                <span className="text-right tabular-nums">
                  {localPrice(
                    price.close,
                    instrument.currency,
                    instrument.priceScale
                  )}
                </span>
                <PriceChange value={price.changeRate} className="justify-end" />
              </Link>
            );
          })
        )}
      </div>
    </div>
  );
}

/**
 * 종가 흐름. 눈금도 값도 없는 형태만의 그림이라 `aria-hidden` 이다 — 읽어야 할 숫자는
 * 옆의 현재가와 등락률이 이미 말한다.
 */
function Sparkline({ points, up }: { points: string[]; up: boolean }) {
  if (points.length < 2) return <span aria-hidden />;

  const values = points.map(Number);
  const min = Math.min(...values);
  const max = Math.max(...values);
  // 보합이면 폭이 0 이라 나눌 수 없다. 그때는 가운데 한 줄로 눕힌다.
  const span = max - min || 1;
  const step = 100 / (values.length - 1);
  const coords = values.map((value, index) => ({
    x: index * step,
    y: 28 - ((value - min) / span) * 24 - 2,
  }));
  const line = coords.map(({ x, y }) => `${x},${y}`).join(" ");
  const last = coords[coords.length - 1];

  return (
    <svg
      viewBox="0 0 100 32"
      preserveAspectRatio="none"
      aria-hidden
      className={cn("h-7 w-full", up ? "text-price-up" : "text-price-down")}
    >
      <polygon
        points={`0,32 ${line} 100,32`}
        fill="currentColor"
        opacity={0.12}
      />
      <polyline
        points={line}
        fill="none"
        stroke="currentColor"
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
      />
      {last && <circle cx={last.x} cy={last.y} r={2.5} fill="currentColor" />}
    </svg>
  );
}
