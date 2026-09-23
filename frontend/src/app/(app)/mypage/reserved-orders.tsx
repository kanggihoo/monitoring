"use client";

import { useState } from "react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
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
import { localPrice, shares } from "@/lib/format";
import { updateOpenOrders } from "@/lib/live-query-reducers";
import type { InstrumentMeta } from "@/lib/market-types";
import {
  amendOrder,
  cancelOrder,
  fetchOpenOrders,
  type OpenOrder,
} from "@/lib/order-api";

/**
 * 정정 요청에 실을 것만 골라 낸다.
 *
 * **바뀐 것만 보낸다.** 비운 쪽은 서버가 지금 값을 그대로 쓰므로(`ord.md`) 값을 다시 실어
 * 보낼 이유가 없고, 다시 보내면 그 사이 우리가 낡게 읽은 값으로 조건을 덮어쓴다.
 *
 * 0 이하와 빈 칸은 바뀌지 않은 것으로 본다 — 지우는 도중의 입력을 그대로 보내면 서버가
 * 거절하고, 그 거절이 사용자에게는 "정정이 안 된다" 로 보인다. 수량은 1주 단위 정수다
 * (기획 6.5.2).
 *
 * @param order 지금 걸려 있는 예약
 * @param input 사용자가 칸에 적어 둔 문자열
 * @returns 보낼 변경. 바뀐 것이 없으면 빈 객체
 */
export function amendChange(
  order: Pick<OpenOrder, "quantity" | "limitPrice">,
  input: { quantity: string; limitPrice: string }
): { quantity?: number; limitPrice?: number } {
  const change: { quantity?: number; limitPrice?: number } = {};
  const quantity = Math.floor(Number(input.quantity));
  const limitPrice = Number(input.limitPrice);

  if (quantity > 0 && quantity !== order.quantity) {
    change.quantity = quantity;
  }
  // 문자열로 견주면 `40000` 과 `40000.00` 이 다른 값이 된다 — 계약이 자릿수를 붙여 주므로
  // 사용자가 손대지 않은 칸도 매번 정정으로 나간다.
  if (limitPrice > 0 && limitPrice !== Number(order.limitPrice)) {
    change.limitPrice = limitPrice;
  }
  return change;
}

/** 매수는 현금을, 매도는 수량을 잠근다 — 둘 중 하나만 값이 있다 (`ord.md`). */
function locked(order: OpenOrder, scale: number) {
  if (order.lockedQuantity !== null) return shares(order.lockedQuantity);
  if (order.lockedAmount !== null) {
    return localPrice(order.lockedAmount, order.currency, scale);
  }
  return "—";
}

/**
 * 미체결 예약. `GET /api/v1/orders` 를 그리고 그 자리에서 정정·취소한다.
 *
 * 페이지를 두지 않는다 — 1인 1계좌에 예약은 몇 건뿐이고 폐장에 소멸한다 (`ord.md`).
 *
 * **정정은 줄 안에서 한다.** 모달을 띄우면 지금 걸린 조건이 가려져, 바꾸려는 값과 원래 값을
 * 나란히 볼 수 없다. 비운 칸은 서버가 지금 값을 그대로 쓴다.
 *
 * @param instruments 종목 메타. 응답이 `instrumentId` 만 주므로 이름과 자릿수를 여기서 찾는다
 */
export function ReservedOrders({
  instruments,
}: {
  instruments: InstrumentMeta[];
}) {
  // 폐장에 미체결 예약이 자동 취소되는 것은 **서버가 한다** — 계약이 "폐장 후
  // `GET /orders` 는 항상 빈 목록" 이라고 못박으므로(`ord.md` · 기획 6.2.4) 화면에서
  // phase 를 보고 거를 필요가 없다.
  const { data, error, reload } = useLiveQuery(
    fetchOpenOrders,
    updateOpenOrders
  );
  /** 지금 정정 중인 줄과 입력값. 한 번에 한 줄만 연다. */
  const [editing, setEditing] = useState<{
    orderId: number;
    quantity: string;
    limitPrice: string;
  } | null>(null);
  /** 취소 확인을 기다리는 예약. */
  const [confirming, setConfirming] = useState<OpenOrder | null>(null);
  /** 왕복 중인 예약. 같은 줄의 버튼을 함께 잠근다. */
  const [pending, setPending] = useState<number | null>(null);

  const metaOf = (instrumentId: number) =>
    instruments.find((item) => item.instrumentId === instrumentId);

  async function save(order: OpenOrder) {
    if (!editing) return;

    const change = amendChange(order, editing);
    if (Object.keys(change).length === 0) {
      setEditing(null);
      return;
    }

    setPending(order.orderId);
    try {
      await amendOrder(order.orderId, change);
      toast.success("예약을 정정했습니다.");
      setEditing(null);
      reload();
    } catch (cause: unknown) {
      toast.error(
        cause instanceof Error ? cause.message : "정정에 실패했습니다."
      );
    } finally {
      setPending(null);
    }
  }

  async function remove(order: OpenOrder) {
    setPending(order.orderId);
    try {
      await cancelOrder(order.orderId);
      toast.success("예약을 취소했습니다. 잠긴 자금이 반환됩니다.");
      setConfirming(null);
      reload();
    } catch (cause: unknown) {
      toast.error(
        cause instanceof Error ? cause.message : "취소에 실패했습니다."
      );
    } finally {
      setPending(null);
    }
  }

  const orders = data?.orders;

  // 첫 조회가 끝나기 전과 빈 목록을 가른다 — 둘을 같은 문구로 덮으면 못 불러온 것이
  // 예약이 없는 것처럼 보인다.
  if (!orders) {
    return (
      <Card size="sm" className="min-h-0 flex-1">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          {error ?? "예약 주문을 불러오는 중입니다."}
        </CardContent>
      </Card>
    );
  }

  if (orders.length === 0) {
    return (
      <Card size="sm" className="min-h-0 flex-1">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          예약한 주문이 없습니다.
        </CardContent>
      </Card>
    );
  }

  return (
    <Card size="sm" className="flex min-h-0 flex-1 flex-col">
      {/* 카드가 남은 높이를 채우고 줄이 넘치면 안에서만 스크롤한다 — 화면이 길어지지 않는다. */}
      <ScrollArea className="min-h-0 flex-1">
        <CardContent className="px-6">
          <Table>
            <TableHeader>
              {/* 표 안에서 고를 것이 없으므로 줄에 hover 를 주지 않는다 — 공용
                `TableRow` 의 기본값을 이 표에서만 끈다. */}
              <TableRow className="hover:bg-transparent [&>th]:text-base">
                <TableHead>거래 시각</TableHead>
                <TableHead>종목</TableHead>
                <TableHead>구분</TableHead>
                <TableHead className="text-right">수량</TableHead>
                <TableHead className="text-right">지정가</TableHead>
                <TableHead className="text-right">금액</TableHead>
                <TableHead className="text-right">관리</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {orders.map((order) => {
                const meta = metaOf(order.instrumentId);
                const scale = meta?.priceScale ?? 2;
                const edit =
                  editing?.orderId === order.orderId ? editing : null;
                const busy = pending === order.orderId;

                return (
                  <TableRow
                    key={order.orderId}
                    className="hover:bg-transparent"
                  >
                    {/* 게임 시각이 아니라 **실제로 누른 시각**이다 (기획 7.4.1). */}
                    <TableCell className="tabular-nums">
                      {formatDayTime(order.createdAt)}
                    </TableCell>
                    <TableCell className="font-medium">
                      {meta?.name ?? `#${order.instrumentId}`}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant="secondary"
                        className={
                          order.side === "BUY"
                            ? "text-price-up"
                            : "text-price-down"
                        }
                      >
                        {order.side === "BUY" ? "매수" : "매도"}
                      </Badge>
                    </TableCell>

                    <TableCell className="text-right tabular-nums">
                      {edit ? (
                        <Input
                          value={edit.quantity}
                          onChange={(event) =>
                            setEditing({
                              ...edit,
                              quantity: event.target.value,
                            })
                          }
                          inputMode="numeric"
                          aria-label="정정할 수량"
                          className="ml-auto h-8 w-20 text-right"
                        />
                      ) : (
                        shares(order.quantity)
                      )}
                    </TableCell>

                    <TableCell className="text-right tabular-nums">
                      {edit ? (
                        <Input
                          value={edit.limitPrice}
                          onChange={(event) =>
                            setEditing({
                              ...edit,
                              limitPrice: event.target.value,
                            })
                          }
                          inputMode="decimal"
                          aria-label="정정할 지정가"
                          className="ml-auto h-8 w-28 text-right"
                        />
                      ) : (
                        localPrice(order.limitPrice, order.currency, scale)
                      )}
                    </TableCell>

                    <TableCell className="text-right tabular-nums">
                      {locked(order, scale)}
                    </TableCell>

                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1.5">
                        {edit ? (
                          <>
                            <Button
                              size="sm"
                              variant="secondary"
                              disabled={busy}
                              onClick={() => void save(order)}
                            >
                              저장
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => setEditing(null)}
                            >
                              되돌리기
                            </Button>
                          </>
                        ) : (
                          <>
                            <Button
                              size="sm"
                              variant="secondary"
                              disabled={busy}
                              onClick={() =>
                                setEditing({
                                  orderId: order.orderId,
                                  quantity: String(order.quantity),
                                  limitPrice: order.limitPrice,
                                })
                              }
                            >
                              정정
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => setConfirming(order)}
                              className="text-price-down"
                            >
                              취소
                            </Button>
                          </>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </CardContent>
      </ScrollArea>

      {/* 취소는 잠긴 자금을 풀고 줄을 없앤다. 되돌릴 수는 있지만(다시 걸면 된다) 그 사이
          시세가 지나가므로, 잘못 눌러 사라지는 일이 없게 한 번 묻는다. */}
      <AlertDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>이 예약을 취소하겠습니까?</AlertDialogTitle>
            <AlertDialogDescription>
              {confirming &&
                `${metaOf(confirming.instrumentId)?.name ?? `#${confirming.instrumentId}`} ${
                  confirming.side === "BUY" ? "매수" : "매도"
                } ${shares(confirming.quantity)} · 지정가 ${localPrice(
                  confirming.limitPrice,
                  confirming.currency,
                  metaOf(confirming.instrumentId)?.priceScale ?? 2
                )}`}
              . 잠긴 자금과 수량이 즉시 반환되고, 다시 걸려면 새로 주문해야
              합니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>그대로 두기</AlertDialogCancel>
            <AlertDialogAction
              disabled={pending !== null}
              onClick={(event) => {
                // 기본 동작은 모달을 닫는다. 요청이 실패하면 문구를 보여 줄 자리가
                // 사라지므로 닫는 것은 성공한 뒤에 우리가 한다.
                event.preventDefault();
                if (confirming) void remove(confirming);
              }}
            >
              예약 취소
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
