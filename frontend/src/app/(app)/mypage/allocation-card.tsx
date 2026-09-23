import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { krw } from "@/lib/format";
import type {
  InstrumentMeta,
  PositionItem,
  Valuation,
} from "@/lib/market-types";

/** 대시보드의 `전체 포지션` 과 같은 네 갈래다. 그쪽은 전체 집계, 이쪽은 내 자산이다. */
const CATEGORY_LABEL = {
  CASH: "현금",
  NASDAQ: "미국",
  KRX: "국내",
  CRYPTO: "코인",
} as const;

type Category = keyof typeof CATEGORY_LABEL;

/** 한 갈래의 비중. 합이 100 이 되도록 나머지를 현금으로 채운다. */
export type Bucket = { category: Category; weight: number };

/**
 * 내 자산 배분. 보유 종목의 비중을 갈래별로 접고 나머지를 현금으로 둔다 — 비중은 서버가
 * 원화 환산으로 이미 계산해 주므로 (`acc.md`) 여기서 하는 일은 더하기뿐이다.
 */
export function allocation(
  positions: PositionItem[],
  instruments: InstrumentMeta[]
): Bucket[] {
  const sums: Record<Category, number> = {
    CASH: 0,
    NASDAQ: 0,
    KRX: 0,
    CRYPTO: 0,
  };
  for (const position of positions) {
    // 갈래는 종목 메타의 것이다 — 포지션 응답은 `instrumentId` 만 준다 (`acc.md`).
    // 메타를 못 찾은 비중은 어느 갈래도 아니므로 현금으로 새지 않게 그냥 뺀다.
    const category = instruments.find(
      (item) => item.instrumentId === position.instrumentId
    )?.category;
    if (category) sums[category] += position.weight;
  }
  sums.CASH = Math.max(0, 100 - sums.NASDAQ - sums.KRX - sums.CRYPTO);

  return (Object.keys(CATEGORY_LABEL) as Category[]).map((category) => ({
    category,
    weight: sums[category],
  }));
}

/**
 * 자산 배분 카드.
 *
 * **조회는 자산 현황 탭이 한다.** 보유 종목 표와 같은 목록의 비중을 더하므로 여기서 또
 * 부르지 않는다 — 따로 부르면 두 카드가 서로 다른 틱의 비중을 말할 수 있다.
 *
 * @param buckets `allocation()` 이 접은 갈래별 비중
 * @param valuation 실시간 계좌 평가. 아직 도착하지 않았으면 `null` 이라 곁말을 뺀다
 */
export function AllocationCard({
  buckets,
  valuation,
}: {
  buckets: Bucket[];
  valuation: Valuation | null;
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="text-base font-semibold">자산 배분</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 border-t border-border pt-3">
        {/* 대시보드 히트맵과 같은 표현이다 — 너비가 비중이고 색을 갈래에 배정하지
            않는다. 파랑·빨강은 등락에만 쓴다 (DESIGN.md). */}
        <div
          className="flex h-20 gap-1"
          role="img"
          aria-label="갈래별 내 자산 비중"
        >
          {buckets
            .filter((bucket) => bucket.weight > 0)
            .map((bucket) => (
              <div
                key={bucket.category}
                style={{ flexGrow: bucket.weight }}
                className="flex min-w-16 basis-0 flex-col justify-between rounded-sm border border-border bg-muted p-2"
              >
                <span className="truncate text-xs font-medium">
                  {CATEGORY_LABEL[bucket.category]}
                </span>
                <span className="text-base font-semibold tabular-nums">
                  {bucket.weight.toFixed(1)}%
                </span>
              </div>
            ))}
        </div>

        <Separator />

        {valuation && (
          <p className="text-xs text-muted-foreground tabular-nums">
            보유 평가액 {krw(valuation.holdingsValuationKrw)}
            {/* 최초 공개 전에는 환율이 오지 않는다 — 그때는 이 곁말을 빼고 보유
                평가액만 낸다. `krw(null)` 은 `NaN원` 이 된다. */}
            {valuation.usdKrwRate != null &&
              ` · 적용 환율 ${krw(valuation.usdKrwRate)} / $1`}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
