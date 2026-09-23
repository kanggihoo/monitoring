import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { krw } from "@/lib/format";
import type { Heatmap, HeatmapBucket } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { PriceChange } from "./price-change";

const CATEGORY_LABEL: Record<HeatmapBucket["category"], string> = {
  NASDAQ: "미국",
  KRX: "국내",
  CRYPTO: "코인",
  CASH: "현금",
};

/**
 * 갈래별 농도. **무채색이다** — 등락 밖에서 빨강·파랑을 쓰지 않는다 (DESIGN.md 금지표).
 *
 * 네 단계를 각각 3:1 로 벌리려면 명도 범위가 27:1 필요한데 흰 배경~검정이 21:1 이라
 * 애초에 불가능하다. 그래서 **색은 정보를 나르지 않는다** — 순서만 암시하고 이름·비중·
 * 변화는 옆의 범례가 글자로 전부 낸다. 조각의 경계는 색이 아니라 간극과 1px 테두리다.
 *
 * `foreground` 하나에 알파만 얹으므로 새 토큰이 없고, 다크에서는 그 토큰이 뒤집혀 따라온다.
 */
const TONE: Record<HeatmapBucket["category"], number> = {
  NASDAQ: 0.85,
  KRX: 0.55,
  CRYPTO: 0.32,
  CASH: 0.15,
};

/**
 * 조·억으로 접은 원화. 도넛 구멍(지름이 원의 60%)에 들어야 해서다 — 전체 플레이어 합계라
 * `krw()` 로 펴면 열여섯 자가 되어 링을 뚫는다. 정확한 자릿수는 범례의 갈래별 평가액이 낸다.
 */
const compactKrw = (amount: string) => {
  const value = Number(amount);
  if (value >= 1e12) return `${(value / 1e12).toFixed(1)}조원`;
  if (value >= 1e8)
    return `${Math.round(value / 1e8).toLocaleString("ko-KR")}억원`;
  return krw(value);
};

const R = 38;
const STROKE = 16;
const CIRCUMFERENCE = 2 * Math.PI * R;
/** 조각 사이 간극. 카드 바탕이 그대로 비쳐 흰 틈이 된다. */
const GAP = 2.5;

function PositionHeatmapV2({
  heatmap,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  heatmap: Heatmap | null;
}) {
  return (
    <Card size="sm" className={cn("w-full", className)} {...props}>
      <CardHeader>
        <CardTitle>전체 포지션 현황</CardTitle>
        <CardDescription className="text-xs">
          지금 전체 플레이어의 자금이 어디에 있나. 집계값이며 개인은 식별되지
          않는다.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {heatmap === null ? (
          <p className="py-6 text-center text-xs text-muted-foreground">
            다음 종가에 집계가 도착한다.
          </p>
        ) : (
          // 실제 자리가 대시보드 오른쪽 기둥이라 좁다. 뷰포트가 아니라 **제 폭**을 보고
          // 접어야 카드를 어디에 두든 같게 동작한다.
          <div className="@container/heat">
            <div className="grid items-center gap-4 @sm/heat:grid-cols-[auto_minmax(0,26rem)]">
              <Donut
                buckets={heatmap.buckets}
                total={heatmap.totalValuationKrw}
              />
              <ul className="flex flex-col gap-3">
                {heatmap.buckets.map((bucket) => (
                  <li
                    key={bucket.category}
                    className="grid grid-cols-[0.75rem_minmax(0,1fr)_auto] items-center gap-x-2.5"
                  >
                    {/* 테두리는 농도와 무관하게 늘 또렷해야 옅은 갈래도 찾을 수 있다.
                        `opacity` 를 쓰면 테두리까지 흐려지므로 배경만 섞는다. */}
                    <span
                      aria-hidden
                      className="size-3 rounded-full border border-border"
                      style={{
                        backgroundColor: `color-mix(in oklab, var(--color-foreground) ${
                          TONE[bucket.category] * 100
                        }%, transparent)`,
                      }}
                    />
                    <div className="min-w-0">
                      <p className="truncate font-medium">
                        {CATEGORY_LABEL[bucket.category]}
                      </p>
                      <p className="text-2xs text-muted-foreground tabular-nums">
                        {krw(bucket.valuationKrw)}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="font-semibold tabular-nums">
                        {bucket.weight.toFixed(1)}%
                      </p>
                      <PriceChange
                        value={bucket.weightDelta}
                        unit="pp"
                        className="text-2xs"
                      />
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Donut({
  buckets,
  total,
}: {
  buckets: HeatmapBucket[];
  total: string;
}) {
  // 조각의 시작점은 앞선 비중의 누적이다. 렌더 도중 변수를 굴리지 않도록
  // (React Compiler 가 막는다) 앞부분을 그때그때 더해 낸다 — 갈래는 넷뿐이다.
  const segments = buckets.map((bucket, index) => ({
    bucket,
    start:
      (buckets.slice(0, index).reduce((sum, item) => sum + item.weight, 0) /
        100) *
      CIRCUMFERENCE,
  }));

  return (
    <div className="relative mx-auto size-40 shrink-0">
      <svg
        viewBox="0 0 100 100"
        className="size-full -rotate-90"
        role="img"
        aria-label={`갈래별 자금 비중. ${buckets
          .map((b) => `${CATEGORY_LABEL[b.category]} ${b.weight.toFixed(1)}%`)
          .join(", ")}`}
      >
        {segments.map(({ bucket, start }) => {
          const length = (bucket.weight / 100) * CIRCUMFERENCE;
          const drawn = Math.max(length - GAP, 0);
          const dash = `${drawn} ${CIRCUMFERENCE - drawn}`;
          if (drawn === 0) return null;

          return (
            <circle
              key={bucket.category}
              cx={50}
              cy={50}
              r={R}
              fill="none"
              strokeWidth={STROKE}
              strokeDasharray={dash}
              strokeDashoffset={-start}
              className="stroke-foreground"
              strokeOpacity={TONE[bucket.category]}
            />
          );
        })}
        {/* 밝은 조각도 경계는 보여야 한다. 색 대비로 못 버는 몫을 선이 낸다. */}
        <circle
          cx={50}
          cy={50}
          r={R + STROKE / 2}
          fill="none"
          strokeWidth={1}
          className="stroke-border"
        />
        <circle
          cx={50}
          cy={50}
          r={R - STROKE / 2}
          fill="none"
          strokeWidth={1}
          className="stroke-border"
        />
      </svg>

      {/* 가운데 글자는 도넛 구멍 안에 든다 — 구멍 지름이 60% 라 폭을 그만큼 묶는다. */}
      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-0.5">
        <span className="text-2xs text-muted-foreground">총 평가액</span>
        <span className="text-sm font-semibold text-foreground tabular-nums">
          {compactKrw(total)}
        </span>
      </div>
    </div>
  );
}

export { PositionHeatmapV2 };
