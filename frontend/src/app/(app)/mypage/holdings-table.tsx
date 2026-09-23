"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowDown, ArrowUp, ArrowUpDown, Briefcase } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { krw, localPrice, shares, signed } from "@/lib/format";
import type { InstrumentMeta, PositionItem } from "@/lib/market-types";
import { cn, FOCUS_RING } from "@/lib/utils";

import { QuickSellDialog, sellableQuantity } from "./quick-sell-dialog";

type SortKey = "NAME" | "WEIGHT" | "RETURN";
type Sort = "DEFAULT" | SortKey;

const SORT_DESC_FIRST: Record<SortKey, boolean> = {
  NAME: false,
  WEIGHT: true,
  RETURN: true,
};

/**
 * 손익은 **원화 환산**으로 크게, 현지 통화를 그 아래 작게 둔다. 계좌 기준 통화가 원화라
 * 크기를 견주려면 원화가 위여야 하고, 종목 자체의 성과는 현지 통화로만 보인다 (`acc.md`).
 */
function pnlTone(value: number) {
  return value > 0
    ? "text-price-up"
    : value < 0
      ? "text-price-down"
      : "text-muted-foreground";
}

/** 부호 있는 원화. `PriceChange` 는 비율이라 `%` 를 붙여 금액에 쓸 수 없다. */
function signedKrw(amount: string) {
  const value = Number(amount);
  const sign = value > 0 ? "+" : value < 0 ? "−" : "";
  return `${sign}${krw(Math.abs(value))}`;
}

function signedLocal(position: PositionItem, scale: number) {
  const value = Number(position.unrealizedPnl);
  const sign = value > 0 ? "+" : value < 0 ? "−" : "";
  return `${sign}${localPrice(Math.abs(value), position.currency, scale)}`;
}

/**
 * 보유 종목. `GET /api/v1/accounts/me/positions` 를 그린다.
 *
 * 페이지가 없는 응답이다 (`acc.md`) — 한 사람이 담는 종목 수가 한 화면을 크게 넘지 않는다.
 * 넘치면 카드 안에서만 스크롤한다.
 *
 * **조회는 자산 현황 탭이 한다.** 자산 배분 카드가 같은 목록의 비중을 더하므로, 여기서 또
 * 부르면 같은 응답을 두 번 읽고 두 카드가 서로 다른 틱을 가리킬 수 있다.
 *
 * **줄을 누르면 그 종목의 차트로, 매도 버튼을 누르면 시장가 매도로 간다.** 보유 목록에서
 * 하고 싶은 일은 둘인데 — 지금 팔거나, 왜 이렇게 됐는지 보거나 — 줄 전체가 매도였을 때는
 * 뒤쪽이 갈 곳이 없었고 표를 훑다 잘못 누르면 매도 대화상자가 열렸다. 파는 일은 버튼이라는
 * 좁은 과녁에 두고, 넓은 과녁은 되돌릴 수 있는 쪽(이동)에 준다.
 *
 * 지정가 예약은 여전히 마켓 화면의 몫이고, 줄을 누르면 바로 그 화면이 열린다.
 *
 * @param positions 평가된 보유 포지션
 * @param instruments 종목 메타. 응답이 `instrumentId` 만 주므로 이름과 자릿수를 여기서 찾는다
 */
export function HoldingsTable({
  positions,
  instruments,
}: {
  positions: PositionItem[];
  instruments: InstrumentMeta[];
}) {
  const router = useRouter();
  const [sort, setSort] = useState<Sort>("DEFAULT");
  const [desc, setDesc] = useState(false);

  const metaOf = (instrumentId: number) =>
    instruments.find((item) => item.instrumentId === instrumentId);

  const pressSort = (key: SortKey) => {
    if (sort !== key) {
      setSort(key);
      setDesc(SORT_DESC_FIRST[key]);
      return;
    }
    setDesc(!desc);
  };

  // 서버의 기본 순서는 종목 ID 오름차순이다. 정렬을 누르기 전에는 그 순서를 건드리지 않는다.
  const sortedPositions = [...positions].sort((a, b) => {
    const gap =
      sort === "NAME"
        ? (metaOf(a.instrumentId)?.name ?? `#${a.instrumentId}`).localeCompare(
            metaOf(b.instrumentId)?.name ?? `#${b.instrumentId}`,
            "ko"
          )
        : sort === "WEIGHT"
          ? a.weight - b.weight
          : sort === "RETURN"
            ? a.returnRate - b.returnRate
            : 0;

    if (gap !== 0) return desc ? -gap : gap;
    return a.instrumentId - b.instrumentId;
  });

  /** 마켓 화면은 `instrumentId` 질의를 읽어 그 종목을 편다 (`market-screen.tsx`). */
  const chartHref = (instrumentId: number) => `/?instrumentId=${instrumentId}`;

  /**
   * 매도 대화상자를 연 종목. **줄이 아니라 식별자를 쥔다** — 틱마다 목록이 갈리므로, 누른
   * 순간의 줄을 들고 있으면 대화상자의 현재가가 뒤에 열린 표와 어긋난다.
   */
  const [sellingId, setSellingId] = useState<number | null>(null);
  const selling = positions.find((item) => item.instrumentId === sellingId);

  return (
    <Card size="sm" className="flex min-h-0 flex-1 flex-col">
      <CardHeader className="shrink-0">
        <CardTitle>보유 종목</CardTitle>
      </CardHeader>

      {positions.length === 0 ? (
        <CardContent className="flex flex-col items-center justify-center gap-2 py-12 text-center">
          <Briefcase aria-hidden className="size-8 text-muted-foreground/40" />
          <p className="text-sm font-medium text-muted-foreground">
            보유한 종목이 없습니다.
          </p>
        </CardContent>
      ) : (
        <ScrollArea className="min-h-0 flex-1">
          <CardContent className="px-6">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent [&>th]:text-base">
                  <TableHead>
                    <button
                      type="button"
                      onClick={() => pressSort("NAME")}
                      aria-label={
                        sort === "NAME"
                          ? `종목명 ${desc ? "내림차순" : "오름차순"} 정렬`
                          : "종목명 정렬"
                      }
                      className={cn(
                        "inline-flex items-center gap-1",
                        FOCUS_RING,
                        sort === "NAME"
                          ? "text-foreground"
                          : "text-muted-foreground"
                      )}
                    >
                      종목
                      {sort === "NAME" ? (
                        desc ? (
                          <ArrowDown aria-hidden className="size-3" />
                        ) : (
                          <ArrowUp aria-hidden className="size-3" />
                        )
                      ) : (
                        <ArrowUpDown aria-hidden className="size-3" />
                      )}
                    </button>
                  </TableHead>
                  <TableHead className="text-right">수량</TableHead>
                  <TableHead className="text-right">평단</TableHead>
                  <TableHead className="text-right">현재가</TableHead>
                  <TableHead className="text-right">평가금액</TableHead>
                  <TableHead className="text-right">손익</TableHead>
                  <TableHead className="text-right">
                    <button
                      type="button"
                      onClick={() => pressSort("RETURN")}
                      aria-label={
                        sort === "RETURN"
                          ? `수익률 ${desc ? "내림차순" : "오름차순"} 정렬`
                          : "수익률 정렬"
                      }
                      className={cn(
                        "inline-flex items-center gap-1",
                        FOCUS_RING,
                        sort === "RETURN"
                          ? "text-foreground"
                          : "text-muted-foreground"
                      )}
                    >
                      수익률
                      {sort === "RETURN" ? (
                        desc ? (
                          <ArrowDown aria-hidden className="size-3" />
                        ) : (
                          <ArrowUp aria-hidden className="size-3" />
                        )
                      ) : (
                        <ArrowUpDown aria-hidden className="size-3" />
                      )}
                    </button>
                  </TableHead>
                  <TableHead className="text-right">
                    <button
                      type="button"
                      onClick={() => pressSort("WEIGHT")}
                      aria-label={
                        sort === "WEIGHT"
                          ? `비중 ${desc ? "내림차순" : "오름차순"} 정렬`
                          : "비중 정렬"
                      }
                      className={cn(
                        "inline-flex items-center gap-1",
                        FOCUS_RING,
                        sort === "WEIGHT"
                          ? "text-foreground"
                          : "text-muted-foreground"
                      )}
                    >
                      비중
                      {sort === "WEIGHT" ? (
                        desc ? (
                          <ArrowDown aria-hidden className="size-3" />
                        ) : (
                          <ArrowUp aria-hidden className="size-3" />
                        )
                      ) : (
                        <ArrowUpDown aria-hidden className="size-3" />
                      )}
                    </button>
                  </TableHead>
                  {/* 머리말을 비운다 — 아래가 버튼뿐이라 이름 붙일 값이 없다. */}
                  <TableHead className="w-0" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {sortedPositions.map((position) => {
                  const meta = metaOf(position.instrumentId);
                  // 자릿수는 종목 메타의 값이다 (`_common.md §6.2`). 메타가 없는 종목은
                  // 유니버스에 없다는 뜻이라 자리만 지키고 값은 손대지 않는다.
                  const scale = meta?.priceScale ?? 2;
                  // 예약이 전부 잠근 줄은 팔 것이 없다. 버튼만 꺼지고 줄은 그대로 열린다 —
                  // 팔 수 없는 종목일수록 왜 그런지 보러 가는 길은 열려 있어야 한다.
                  const sellable = sellableQuantity(position);

                  return (
                    <TableRow
                      key={position.instrumentId}
                      // 줄 전체가 차트로 가는 손잡이다. 줄에 tabIndex 를 주지 않는다 —
                      // 키보드는 종목명 링크와 매도 버튼으로 두 목적지에 다 닿는다.
                      onClick={() =>
                        router.push(chartHref(position.instrumentId))
                      }
                      className="cursor-pointer hover:bg-accent/40"
                    >
                      <TableCell className="font-medium">
                        {/* 줄에도 같은 목적지가 걸려 있지만 링크를 따로 둔다 — 키보드와
                            새 탭으로 여는 것은 진짜 `<a>` 에만 있다. */}
                        <Link
                          href={chartHref(position.instrumentId)}
                          onClick={(event) => event.stopPropagation()}
                          className="hover:underline"
                        >
                          {meta?.name ?? `#${position.instrumentId}`}
                        </Link>
                      </TableCell>

                      <TableCell className="text-right tabular-nums">
                        {shares(position.quantity)}
                        {/* 예약 매도로 잠긴 몫은 따로 말한다 (기획 7.3.4). */}
                        {position.lockedQuantity > 0 && (
                          <span className="block text-2xs text-muted-foreground">
                            주문 중 {shares(position.lockedQuantity)}
                          </span>
                        )}
                      </TableCell>

                      <TableCell className="text-right tabular-nums">
                        {localPrice(
                          position.avgPrice,
                          position.currency,
                          scale
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {localPrice(
                          position.currentPrice,
                          position.currency,
                          scale
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {krw(position.valuationKrw)}
                      </TableCell>

                      <TableCell
                        className={cn(
                          "text-right tabular-nums",
                          pnlTone(Number(position.unrealizedPnlKrw))
                        )}
                      >
                        {signedKrw(position.unrealizedPnlKrw)}
                        {/* 원화 손익과 부호가 갈릴 수 있어 함께 낸다 (`acc.md`). */}
                        {position.currency !== "KRW" && (
                          <span className="block text-2xs opacity-70">
                            {signedLocal(position, scale)}
                          </span>
                        )}
                      </TableCell>

                      <TableCell
                        className={cn(
                          "text-right tabular-nums",
                          pnlTone(position.returnRate)
                        )}
                      >
                        {signed(position.returnRate, 2)}%
                      </TableCell>
                      <TableCell className="text-right text-muted-foreground tabular-nums">
                        {position.weight.toFixed(1)}%
                      </TableCell>

                      <TableCell className="text-right">
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={sellable === 0}
                          // 줄은 차트로 간다. 버블링을 그대로 두면 매도 대화상자를 열면서
                          // 뒤에서 화면이 바뀐다.
                          onClick={(event) => {
                            event.stopPropagation();
                            setSellingId(position.instrumentId);
                          }}
                          className="text-price-down"
                        >
                          매도
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </CardContent>
        </ScrollArea>
      )}

      {/* 종목이 바뀌면 다시 마운트해 수량 입력을 새로 연다 — 앞서 고른 종목의 수량이 남아
          있으면 다른 종목을 그 수량으로 팔게 된다. */}
      {selling && (
        <QuickSellDialog
          key={selling.instrumentId}
          position={selling}
          meta={metaOf(selling.instrumentId)}
          onOpenChange={(open) => !open && setSellingId(null)}
        />
      )}
    </Card>
  );
}
