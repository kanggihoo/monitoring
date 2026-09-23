"use client";

import { useCallback, useState } from "react";

import { Badge } from "@/components/ui/badge";
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
import { useLiveQuery } from "@/hooks/use-live-query";
import { formatDayTime } from "@/lib/datetime";
import { gameDay, krw } from "@/lib/format";
import { updateExchanges } from "@/lib/live-query-reducers";
import { type Exchange, fetchExchanges } from "@/lib/order-api";

import { EmptyCard, PageFooter } from "./page-footer";

const PAGE_SIZE = 20;

const usd = (value: string) =>
  `$${Number(value).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/**
 * 보내는 통화와 받는 통화의 표기가 방향마다 뒤집힌다. 한 줄에 두 통화가 같이 있어서
 * 잘못 붙이면 `$1,500,000` 같은 숫자가 나온다.
 */
function amounts(exchange: Exchange) {
  const toUsd = exchange.direction === "KRW_TO_USD";
  return {
    send: toUsd ? krw(Number(exchange.sendAmount)) : usd(exchange.sendAmount),
    fee: toUsd ? krw(Number(exchange.fee)) : usd(exchange.fee),
    receive: toUsd
      ? usd(exchange.receiveAmount)
      : krw(Number(exchange.receiveAmount)),
  };
}

/**
 * 환전 이력. `GET /api/v1/accounts/me/exchanges` 를 최신순으로 그린다 (기획 7.4.3).
 *
 * **체결 표와 합치지 않는다.** 환전은 종목·수량·손익이 없고 방향·환율만 있어 열이 하나도
 * 겹치지 않는다 (`acc.md`) — 한 표에 넣으면 절반이 늘 빈 칸이다. 기획 7.4.3 이 말한 "내역에
 * 포함" 은 화면에서의 통합이고, 그 통합을 같은 탭의 전환으로 한다.
 *
 * ponytail: 시간순으로 뒤섞어 한 표로 보고 싶으면 두 응답을 병합해야 하는데, 각자 페이지가
 * 달라 경계에서 줄이 빠지거나 겹친다. 서버가 통합 이력 경로를 내주면 그때 합친다.
 */
export function ExchangeHistory() {
  const [page, setPage] = useState(0);
  const read = useCallback(() => fetchExchanges(page, PAGE_SIZE), [page]);
  const { data, error } = useLiveQuery(read, updateExchanges);

  if (!data || data.content.length === 0) {
    return (
      <Card size="sm" className="min-h-0 flex-1">
        <CardContent>
          <EmptyCard>
            {!data
              ? (error ?? "환전 이력을 불러오는 중입니다.")
              : "아직 환전한 적이 없다."}
          </EmptyCard>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card size="sm" className="flex min-h-0 flex-1 flex-col">
      <ScrollArea className="min-h-0 flex-1">
        <CardContent className="px-6">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent [&>th]:text-base">
                <TableHead>환전 시각</TableHead>
                <TableHead>방향</TableHead>
                <TableHead className="text-right">보낸 금액</TableHead>
                <TableHead className="text-right">적용 환율</TableHead>
                <TableHead className="text-right">수수료</TableHead>
                <TableHead className="text-right">받은 금액</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.content.map((exchange) => {
                const value = amounts(exchange);
                const toUsd = exchange.direction === "KRW_TO_USD";

                return (
                  <TableRow
                    key={exchange.exchangeId}
                    className="hover:bg-transparent"
                  >
                    {/* 게임 시각이 아니라 **실제로 환전한 시각**이다 (기획 7.4.1). 게임
                        거래일은 환율이 어느 날 값인지를 말하므로 그 옆에 함께 적는다. */}
                    <TableCell className="tabular-nums">
                      {formatDayTime(exchange.exchangedAt)}
                      <span className="ml-1.5 text-2xs text-muted-foreground">
                        {gameDay(exchange.gameDate)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary">
                        {toUsd ? "원화 → 달러" : "달러 → 원화"}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {value.send}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {krw(Number(exchange.rate))} / $1
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {value.fee}
                      {/* 계약의 요율은 이미 퍼센트다 — `0.05` 가 0.05% 다 (`acc.md`). */}
                      <span className="ml-1 text-2xs text-muted-foreground">
                        · {exchange.feeRate.toFixed(2)}%
                      </span>
                    </TableCell>
                    <TableCell className="text-right font-medium tabular-nums">
                      {value.receive}
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
