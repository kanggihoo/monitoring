import { PieChart } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { Heatmap, HeatmapBucket } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { PriceChange } from "./price-change";

/**
 * 전체 포지션 히트맵 (기획 12.4). **카테고리 단위 집계**다 — 종목 단위는 "다들 엔비디아를
 * 던지고 있다" 를 보여 집단행동을 유발하므로 계약이 주지 않는다 (`soc.md`).
 *
 * 핵심은 현금 비중이다 — 그 변화가 공포·탐욕의 지표다. 칸의 너비가 비중이고, 색은 직전
 * Tick 대비 비중이 늘었는지(빨강)·줄었는지(파랑)만 옅게 나른다. 수치 라벨을 항상 함께 낸다.
 */
const CATEGORY_LABEL: Record<HeatmapBucket["category"], string> = {
  NASDAQ: "미국",
  KRX: "국내",
  CRYPTO: "코인",
  CASH: "현금",
};

const tintOf = (delta: number) =>
  delta > 0
    ? "bg-price-up-surface"
    : delta < 0
      ? "bg-price-down-surface"
      : "bg-muted";

function PositionHeatmap({
  heatmap,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  heatmap: Heatmap | null;
}) {
  return (
    <Card size="sm" className={cn("w-full", className)} {...props}>
      <CardHeader>
        <CardTitle>전체 포지션</CardTitle>
      </CardHeader>
      <CardContent>
        {heatmap === null ? (
          <div className="flex flex-col items-center justify-center gap-2 py-8 text-center">
            <PieChart className="size-8 text-muted-foreground/40" />
            <p className="text-sm font-medium text-muted-foreground">
              다음 종가 확정 후 카테고리별 자금 비중이 집계됩니다
            </p>
          </div>
        ) : (
          <>
            <div
              className="flex h-24 gap-1"
              role="img"
              aria-label="카테고리별 자금 비중"
            >
              {heatmap.buckets.map((bucket) => (
                <div
                  key={bucket.category}
                  style={{ flexGrow: bucket.weight }}
                  className={cn(
                    "flex min-w-20 basis-0 flex-col justify-between rounded-sm border border-border p-2",
                    tintOf(bucket.weightDelta)
                  )}
                >
                  <span className="truncate text-xs font-medium text-foreground">
                    {CATEGORY_LABEL[bucket.category]}
                  </span>
                  <span className="text-base font-semibold text-foreground tabular-nums">
                    {bucket.weight.toFixed(1)}%
                  </span>
                  <PriceChange
                    value={bucket.weightDelta}
                    unit="pp"
                    className="text-2xs"
                  />
                </div>
              ))}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

export { PositionHeatmap };
