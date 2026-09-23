import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { krw, usd } from "@/lib/format";
import type { Valuation } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { PriceChange } from "./price-change";
import { Stat } from "./stat";

/**
 * 내 계좌 요약. `account.valuation`(= `GET /accounts/me`) 을 그대로 그린다 — 시작 잔고도
 * 서버가 준다. 시드 상수를 두지 않는다 (v0.2 의 1,000만 원이 굳어 v0.7 의 1억과 10배 어긋난
 * 전례가 있다).
 */
function PortfolioSummary({
  valuation,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  valuation: Valuation;
}) {
  const allCash = Number(valuation.holdingsValuationKrw) === 0;

  return (
    <Card size="sm" className={cn("w-full", className)} {...props}>
      <CardHeader>
        <CardTitle className="text-base font-semibold">내 계좌</CardTitle>
      </CardHeader>

      {/* 세 카드(날짜·내 계좌·오늘의 성적)의 구분선을 같은 높이에 두려고 첫 칸의 높이를
          맞춘다 — 안의 내용은 길이가 저마다 달라 그대로 두면 선이 층지어 보인다. */}
      <CardContent className="flex min-h-15 items-center justify-between gap-2">
        <span className="text-3xl font-semibold text-foreground tabular-nums">
          {krw(valuation.totalValuationKrw)}
        </span>
        <PriceChange value={valuation.cumulativeReturn} variant="badge" />
      </CardContent>

      <Separator />

      <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        <Stat
          label="오늘"
          value={<PriceChange value={valuation.dailyReturn} />}
        />
        <Stat
          label="원화 현금"
          value={krw(valuation.krwCash)}
          hint={`잠김 ${krw(valuation.lockedKrw)}`}
        />
        <Stat label="달러 현금" value={usd(valuation.usdCash)} />
      </CardContent>

      {allCash && (
        <CardContent className="text-sm text-muted-foreground">
          현재 보유 중인 종목이 없습니다 (현금 100%).
        </CardContent>
      )}
    </Card>
  );
}

export { PortfolioSummary };
