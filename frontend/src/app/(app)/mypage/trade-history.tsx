"use client";

import { useCallback, useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useGameClock } from "@/hooks/use-game-clock";
import { useLiveQuery } from "@/hooks/use-live-query";
import { formatDayTime } from "@/lib/datetime";
import { localPrice, shares } from "@/lib/format";
import { updateExecutions } from "@/lib/live-query-reducers";
import type { InstrumentMeta } from "@/lib/market-types";
import { fetchExecutions } from "@/lib/order-api";

import { EmptyCard, PageFooter } from "./page-footer";

/** 한 번에 가져올 줄 수. 계약의 상한은 100 이다 (`ord.md`). */
const PAGE_SIZE = 20;

/**
 * 시즌 길이. 편성값이지만 화면에 내려오는 경로가 없어 여기 둔다 — 서버의
 * {@code ReplayState.FINAL_DAY} 와 같은 값이다.
 */
const FINAL_DAY = 3;

/**
 * 체결 내역. `GET /api/v1/executions` 를 최신순으로 그린다.
 *
 * **체결만 담는다.** 취소는 `DELETE` 로 사라지고 미체결 예약은 폐장에 소멸해, 그 둘만 담는
 * 경로가 계약에 없다 (`ord.md`).
 *
 * ponytail: 주문 이력 조회가 계약에 생기면 취소·만료를 이 표에 합치고 `결과` 열을 되살린다.
 * 지금 열을 두면 값이 언제나 `체결` 이라 아무것도 구별하지 못한다.
 *
 * @param instruments 종목 메타. 응답이 `instrumentId` 만 주므로 이름과 자릿수를 여기서 찾는다
 */
export function TradeHistory({
  instruments,
}: {
  instruments: InstrumentMeta[];
}) {
  const [page, setPage] = useState(0);
  // null 이 '전체' 다. 0 을 빈 값으로 쓰면 1일차와 구별되지 않는다.
  const [day, setDay] = useState<number | null>(null);
  const { seasonDay } = useGameClock();
  // 페이지·일차가 바뀌면 참조가 바뀌어 다시 읽는다. 틱 갱신은 훅이 따로 건다.
  const read = useCallback(
    () => fetchExecutions(page, PAGE_SIZE, day ?? undefined),
    [page, day]
  );
  const { data, error } = useLiveQuery(read, updateExecutions);

  /**
   * 고를 수 있는 일차. **아직 오지 않은 일차는 담지 않는다** — 누르면 늘 빈 표가
   * 나오는 칩을 두면 거래가 없는 것인지 오지 않은 것인지 구별되지 않는다.
   *
   * 시계를 아직 모르면(`null`) 시즌 전체를 연다. 지난 시즌의 내역을 볼 때도 그렇다.
   */
  const days = Array.from(
    { length: Math.min(seasonDay ?? FINAL_DAY, FINAL_DAY) },
    (_, index) => index + 1
  );

  /** 일차를 바꾸면 첫 페이지로 돌아간다 — 3페이지를 보다 바꾸면 빈 페이지가 나온다. */
  const chooseDay = (next: number | null) => {
    setDay(next);
    setPage(0);
  };

  const filters = (
    <div className="flex flex-wrap items-center gap-1.5">
      {[null, ...days].map((value) => (
        <Button
          key={value ?? "all"}
          type="button"
          size="xs"
          variant={day === value ? "secondary" : "ghost"}
          aria-pressed={day === value}
          onClick={() => chooseDay(value)}
        >
          {value === null ? "전체" : `${value}일차`}
        </Button>
      ))}
    </div>
  );

  const metaOf = (instrumentId: number) =>
    instruments.find((item) => item.instrumentId === instrumentId);

  // 첫 조회가 끝나기 전과 빈 목록을 가른다 — 둘을 같은 문구로 덮으면 못 불러온 것이
  // 거래한 적이 없는 것처럼 보인다.
  if (!data || data.content.length === 0) {
    return (
      <Card size="sm" className="min-h-0 flex-1">
        {/* 빈 표에서도 칩을 남긴다 — 사라지면 고른 일차를 되돌릴 방법이 없다. */}
        <CardContent>{filters}</CardContent>
        <CardContent>
          <EmptyCard>
            {!data
              ? (error ?? "체결 내역을 불러오는 중입니다.")
              : day === null
                ? "아직 체결된 주문이 없다."
                : `${day}일차에는 체결된 주문이 없다.`}
          </EmptyCard>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card size="sm" className="flex min-h-0 flex-1 flex-col">
      <CardContent className="shrink-0">{filters}</CardContent>
      {/* 카드가 남은 높이를 채우고 줄이 넘치면 안에서만 스크롤한다 — 화면이 길어지지 않는다. */}
      <ScrollArea className="min-h-0 flex-1">
        <CardContent className="px-6">
          <Table>
            <TableHeader>
              {/* 표 안에서 고를 것이 없으므로 줄에 hover 를 주지 않는다. */}
              <TableRow className="hover:bg-transparent [&>th]:text-base">
                <TableHead>거래 시각</TableHead>
                <TableHead>종목</TableHead>
                <TableHead>구분</TableHead>
                <TableHead className="text-right">수량</TableHead>
                <TableHead className="text-right">체결가</TableHead>
                <TableHead className="text-right">금액</TableHead>
                <TableHead className="text-right">수수료</TableHead>
                <TableHead className="text-right">실현손익</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.content.map((execution) => {
                const meta = metaOf(execution.instrumentId);
                const scale = meta?.priceScale ?? 2;

                return (
                  <TableRow
                    key={execution.executionId}
                    className="hover:bg-transparent"
                  >
                    {/* 게임 시각이 아니라 **실제로 체결된 시각**이다 (기획 7.4.1). */}
                    <TableCell className="tabular-nums">
                      {formatDayTime(execution.executedAt)}
                    </TableCell>
                    <TableCell className="font-medium">
                      {meta?.name ?? `#${execution.instrumentId}`}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant="secondary"
                        className={
                          execution.side === "BUY"
                            ? "text-price-up"
                            : "text-price-down"
                        }
                      >
                        {execution.side === "BUY" ? "매수" : "매도"}
                      </Badge>
                      <span className="ml-1.5 text-2xs text-muted-foreground">
                        {execution.orderType === "MARKET" ? "시장가" : "예약"}
                      </span>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {shares(execution.quantity)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {localPrice(
                        execution.executedPrice,
                        execution.currency,
                        scale
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {localPrice(execution.amount, execution.currency, scale)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {localPrice(execution.fee, execution.currency, scale)}
                      {/* 계약의 요율은 이미 퍼센트다 — `0.15` 가 0.15% 다 (`ord.md`). */}
                      <span className="ml-1 text-2xs text-muted-foreground">
                        · {execution.appliedFeeRate.toFixed(2)}%
                      </span>
                    </TableCell>
                    {/* 실현손익은 매도 체결에만 있다 (`ord.md`). 비율이 아니라 금액이라
                      `PriceChange` 를 쓰지 않는다 — 그쪽은 값에 % 를 붙인다. */}
                    <TableCell className="text-right tabular-nums">
                      {execution.realizedPnl ? (
                        <span
                          className={
                            Number(execution.realizedPnl) >= 0
                              ? "text-price-up"
                              : "text-price-down"
                          }
                        >
                          {Number(execution.realizedPnl) >= 0 ? "+" : "−"}
                          {localPrice(
                            Math.abs(Number(execution.realizedPnl)),
                            execution.currency,
                            scale
                          )}
                        </span>
                      ) : (
                        "—"
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </CardContent>
      </ScrollArea>

      <PageFooter meta={data} unit="건" onPage={setPage} />
    </Card>
  );
}
