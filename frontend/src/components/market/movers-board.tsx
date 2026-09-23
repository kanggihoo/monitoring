import Link from "next/link";
import { TrendingUp } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { localPrice } from "@/lib/format";
import type { InstrumentMeta, PriceEntry } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { PriceChange } from "./price-change";

const TOP = 5;

/**
 * 등락 보드 (기획 5.3). 휴장 종목은 정렬할 값이 없어 제외한다 (`mkt.md`).
 * 막대 길이는 그 판의 최대 등락률 대비 비율이다 — 절대 눈금이 아니다.
 */
export function MoversBoard({
  instruments,
  prices,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  instruments: InstrumentMeta[];
  prices: Record<number, PriceEntry>;
}) {
  const rows = instruments
    .map((instrument) => ({
      instrument,
      price: prices[instrument.instrumentId],
    }))
    .filter(
      (
        row
      ): row is {
        instrument: InstrumentMeta;
        price: PriceEntry & { changeRate: number };
      } => row.price !== undefined && row.price.changeRate !== null
    )
    .sort((a, b) => b.price.changeRate - a.price.changeRate);

  // **두 열이 같은 규칙을 쓴다.** 부호로 먼저 거르고 나서 자른다 — 거르지 않고 위에서
  // `TOP` 개를 집으면 오른 종목이 모자랄 때 보합과 하락 종목이 `상승` 칸을 채운다.
  // 보합(0%)은 어느 쪽도 아니다: 카드가 말하는 것은 등락이지 순위가 아니다.
  const gainers = rows.filter((row) => row.price.changeRate > 0).slice(0, TOP);
  const losers = rows
    .filter((row) => row.price.changeRate < 0)
    .slice(-TOP)
    .reverse();
  const scale = Math.max(
    ...rows.map((row) => Math.abs(row.price.changeRate)),
    0.01
  );
  const slots = Math.max(gainers.length, losers.length);

  return (
    <Card size="sm" className={cn("w-full", className)} {...props}>
      <CardHeader>
        <CardTitle>오늘의 등락</CardTitle>
      </CardHeader>
      {rows.length === 0 ? (
        <CardContent className="flex flex-col items-center justify-center gap-2 py-10 text-center">
          <TrendingUp className="size-8 text-muted-foreground/40" />
          <p className="text-sm font-medium text-muted-foreground">
            다음 종가 확정 후 오늘의 등락 순위가 집계됩니다
          </p>
        </CardContent>
      ) : (
        <CardContent className="grid gap-4 md:grid-cols-2">
          {/* 두 열의 줄 수를 맞춘다. 한쪽이 비었다고 `없음` 한 줄로 줄이면 자리가 무너져
              옆 열의 순위와 눈이 어긋나고, 몇 종이 빠진 것인지도 보이지 않는다. */}
          <Column
            title="상승"
            rows={gainers}
            slots={slots}
            scale={scale}
            emptyNote="오른 종목이 없습니다."
          />
          <Column
            title="하락"
            rows={losers}
            slots={slots}
            scale={scale}
            emptyNote="내린 종목이 없습니다."
          />
        </CardContent>
      )}
    </Card>
  );
}

/** 줄이 비었을 때 값 자리에 놓는 글자. 숫자 `0` 과 구별되어야 한다 — 0% 는 보합이지 빈칸이 아니다. */
const EMPTY = "—";

function Column({
  title,
  rows,
  slots,
  scale,
  emptyNote,
}: {
  title: string;
  rows: {
    instrument: InstrumentMeta;
    price: PriceEntry & { changeRate: number };
  }[];
  /** 이 열이 그릴 줄 수. 모자란 만큼 빈 줄로 채운다. 두 열이 같은 값을 받는다. */
  slots: number;
  scale: number;
  /** 이 열에 한 종목도 없을 때 대신 낼 문장. */
  emptyNote: string;
}) {
  // **한 줄도 없으면 빈 줄로 채우지 않는다.** 빈 줄 채우기는 옆 열과 순위를 맞추기 위한
  // 것인데(아래), 맞출 줄 자체가 없으면 제목만 남은 빈 칸이 된다 — 등락이 전부 보합인
  // 구간이 그렇다.
  if (rows.length === 0) {
    return (
      <div className="flex flex-col">
        <span className="mb-1 text-xs text-muted-foreground">{title}</span>
        <p className="py-4 text-xs text-muted-foreground">{emptyNote}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col">
      <span className="mb-1 text-xs text-muted-foreground">{title}</span>
      {rows.map(({ instrument, price }, index) => (
        // 줄 전체가 링크다. `/` (마켓)은 `instrumentId` 질의를 읽어 그 종목을 펴고
        // (`market-screen.tsx`), Next 가 hover 에서 미리 받아 둔다.
        <Link
          key={instrument.instrumentId}
          href={`/?instrumentId=${instrument.instrumentId}`}
          className={cn(
            "-mx-2 grid grid-cols-[1.25rem_minmax(0,1fr)_5rem_4.5rem] items-center gap-2 border-b border-border px-2 py-1.5 text-sm last:border-b-0",
            "rounded-md transition-colors hover:bg-accent focus-visible:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          )}
        >
          {/* 숫자를 제 칸 가운데에 둔다 — 왼쪽에 붙이면 열 제목("상승"·"하락")의 첫 글자
              보다 왼쪽으로 빠져 제목과 줄이 어긋나 보인다. */}
          <span className="text-center text-muted-foreground tabular-nums">
            {index + 1}
          </span>
          <div className="flex min-w-0 flex-col">
            <span className="truncate font-medium">{instrument.name}</span>
            <span
              aria-hidden
              className={cn(
                "mt-1 h-1 rounded-full",
                price.changeRate > 0 ? "bg-price-up" : "bg-price-down"
              )}
              style={{
                width: `${Math.max((Math.abs(price.changeRate) / scale) * 100, 4)}%`,
              }}
            />
          </div>
          <span className="text-right tabular-nums">
            {localPrice(
              price.close,
              instrument.currency,
              instrument.priceScale
            )}
          </span>
          <PriceChange value={price.changeRate} className="justify-end" />
        </Link>
      ))}
      {Array.from({ length: slots - rows.length }, (_, index) => (
        <div
          key={`empty-${rows.length + index}`}
          className="-mx-2 grid grid-cols-[1.25rem_minmax(0,1fr)_5rem_4.5rem] items-center gap-2 border-b border-border px-2 py-1.5 text-sm text-muted-foreground last:border-b-0"
        >
          {/* 순위는 그대로 둔다 — 몇 번째 자리가 비어 있는지가 이 줄이 말하는 것이다. */}
          <span className="text-center tabular-nums">
            {rows.length + index + 1}
          </span>
          {/* 채워진 줄에서 이름 아래 막대가 차지하던 8px 을 위아래로 나눠 갖는다 (`my-1`).
              줄 높이는 그대로 맞으면서 글자가 줄 한가운데로 내려온다 — 막대 자리를 아래에만
              두면 같은 높이여도 `—` 만 위로 붙어 옆 열의 이름과 눈이 어긋난다. */}
          <span className="my-1 block">{EMPTY}</span>
          <span className="text-right">{EMPTY}</span>
          <span className="text-right">{EMPTY}</span>
        </div>
      ))}
    </div>
  );
}
