"use client";

import { useStore } from "zustand";

import { PriceChange } from "@/components/market/price-change";
import { Stat } from "@/components/market/stat";
import { Card, CardContent } from "@/components/ui/card";
import { usePositions } from "@/hooks/use-positions";
import { krw, usd } from "@/lib/format";
import type { InstrumentMeta } from "@/lib/market-types";
import { tickStore } from "@/stores/tick-store";

import { allocation, AllocationCard } from "./allocation-card";
import { ExchangePanel } from "./exchange-panel";
import { HoldingsTable } from "./holdings-table";

/**
 * 자산 현황. 계좌 그 자체를 보는 탭이다 — 요약·보유 종목·자산 배분·환전.
 *
 * 요약은 실시간 `account.valuation` 이다 — 헤더가 보는 것과 같은 값이라 둘이 어긋나지
 * 않는다. **아직 도착하지 않았으면 숫자를 그리지 않는다** — 자리 채움 계좌를 그리면 남의
 * 시드와 남의 손익이 내 화면에 잠깐 뜨고, 그 값을 보고 주문 금액을 정할 수 있다.
 *
 * **보유 종목 조회를 이 탭이 쥔다.** 아래 표와 자산 배분 카드가 같은 목록을 보므로 한 번만
 * 읽는다 — 따로 부르면 두 카드가 서로 다른 틱의 비중을 말할 수 있다.
 *
 * @param instruments 종목 메타. 포지션의 `instrumentId` 를 이름·갈래·자릿수로 바꾼다
 */
export function AssetsOverview({
  instruments,
}: {
  instruments: InstrumentMeta[];
}) {
  const valuation = useStore(tickStore, (state) => state.valuation);
  const { data: held } = usePositions();
  const positions = held?.positions ?? [];
  const buckets = allocation(positions, instruments);

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      {/* 요약은 한 줄이다. 카드 넷으로 쪼개면 같은 계좌의 네 면이 서로 다른 것처럼 보인다. */}
      <Card size="sm" className="shrink-0">
        {!valuation ? (
          <CardContent className="py-6 text-center text-sm text-muted-foreground">
            계좌를 불러오는 중입니다.
          </CardContent>
        ) : (
          <CardContent className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Stat
              label="총 자산"
              value={
                <span className="text-2xl font-semibold">
                  {krw(valuation.totalValuationKrw)}
                </span>
              }
            />
            <Stat
              label="평가손익"
              value={
                <span className="text-2xl font-semibold">
                  <PriceChange value={valuation.cumulativeReturn} />
                </span>
              }
            />
            {/* `krwCash` 는 잠긴 몫을 포함한다. 주문 가능액은 프론트가 뺀다 (`acc.md`). */}
            <Stat
              label="원화 현금"
              value={
                <span className="text-2xl font-semibold">
                  {krw(Number(valuation.krwCash) - Number(valuation.lockedKrw))}
                </span>
              }
              hint={`주문 가능 · 잠김 ${krw(valuation.lockedKrw)}`}
            />
            <Stat
              label="달러 현금"
              value={
                <span className="text-2xl font-semibold">
                  {usd(valuation.usdCash)}
                </span>
              }
            />
          </CardContent>
        )}
      </Card>

      {/* 열이 갈리는 `xl` 아래에서는 보유 종목·자산 배분이 한 열로 쌓이므로, 셸이
          그러듯 여기서도 높이를 뷰포트에 묶지 않는다 (`(app)/layout.tsx` 의 같은 주석).
          묶어 두면 표가 든 카드가 머리말만 남기고 눌린다 — 표는 제 안에서 스크롤하겠다고
          `flex-1` 을 쓰는데, 그 기준 크기가 0 이라 바깥 높이가 모자라면 0 이 된다. */}
      <div className="grid min-h-0 gap-3 max-xl:flex-none xl:flex-1 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <HoldingsTable positions={positions} instruments={instruments} />

        <div className="flex flex-col gap-3">
          <AllocationCard buckets={buckets} valuation={valuation} />

          <ExchangePanel />
        </div>
      </div>
    </div>
  );
}
