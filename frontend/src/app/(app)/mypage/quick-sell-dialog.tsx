"use client";

import { useEffect, useState } from "react";
import { Minus, Plus, TrendingDown } from "lucide-react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { DialogRow } from "@/components/market/dialog-row";
import {
  BASE_FEE_RATE,
  ORDER_BUTTON,
  ORDER_SELL,
} from "@/components/market/order-ticket";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useGameClock } from "@/hooks/use-game-clock";
import { trackEvent } from "@/lib/analytics";
import { krw, localPrice, shares } from "@/lib/format";
import type { InstrumentMeta, PositionItem } from "@/lib/market-types";
import { normalizeQuantity } from "@/lib/numeric-input";
import {
  fetchOrderPreview,
  type OrderPreview,
  placeOrder,
} from "@/lib/order-api";
import { cn } from "@/lib/utils";
import {
  reloadPositions,
  reloadValuation,
  tickStore,
} from "@/stores/tick-store";

/** 주문 패널과 같은 값이다. 입력이 멎은 뒤에 한 번만 묻는다. */
const PREVIEW_DEBOUNCE_MS = 300;

/**
 * 지금 팔 수 있는 수량. **예약 매도로 잠긴 몫은 뺀다** — 잠긴 수량은 시장가 매도에 쓸 수
 * 없고 (`ord.md`), 보유 수량을 그대로 상한으로 삼으면 전량을 눌렀을 때 서버가 409 로 막는다.
 */
export function sellableQuantity(
  position: Pick<PositionItem, "quantity" | "lockedQuantity">
) {
  return Math.max(0, position.quantity - position.lockedQuantity);
}

/**
 * 보유 종목에서 곧바로 내는 **시장가 매도**. 보유 목록의 줄을 누르면 뜬다.
 *
 * **시장가만 받는다.** 예약은 지정가와 유효기간을 함께 정하는 일이라 마켓 화면의 주문
 * 패널이 맡는다 — 여기까지 들이면 잔고 검증과 phase 게이팅이 두 벌이 된다. 이 화면이 더하는
 * 것은 "이미 가진 것을 지금 값에 턴다" 는 한 가지 길뿐이다.
 *
 * 금액·수수료의 진실은 미리보기다. 못 받았을 때만 화면이 어림한다 (`ord.md`).
 *
 * @param position 팔 대상. 목록이 고른 줄이며, 바뀌면 부르는 쪽이 다시 마운트한다
 * @param meta 종목 메타. 이름과 자릿수가 여기 있다
 */
export function QuickSellDialog({
  position,
  meta,
  onOpenChange,
}: {
  position: PositionItem;
  meta?: InstrumentMeta;
  onOpenChange: (open: boolean) => void;
}) {
  const clock = useGameClock();
  const price = useStore(
    tickStore,
    (state) => state.prices[position.instrumentId]
  );
  /** 확정된 Tick. 종가가 바뀌면 이 번호가 오르고, 그것이 미리보기를 다시 묻는 신호다. */
  const tickSeq = useStore(tickStore, (state) => state.tickSeq);

  const sellable = sellableQuantity(position);
  // **0 에서 시작한다.** 여는 순간 전량이 적혀 있으면 확인 버튼이 곧 "전량 매도" 가 되어,
  // 한 번 잘못 누르는 것과 다 파는 것이 같아진다.
  const [quantity, setQuantity] = useState("0");
  /**
   * 서버가 답한 미리보기와 **그 답이 선 자리** — 수량과 Tick 둘 다 기억한다. 시장가의
   * 기준가는 그 틱의 종가라, 수량이 그대로여도 틱이 넘어가면 금액이 옛 값이 된다.
   */
  const [answered, setAnswered] = useState<{
    quantity: number;
    tickSeq: number;
    data: OrderPreview;
  } | null>(null);
  const [placing, setPlacing] = useState(false);

  const name = meta?.name ?? `#${position.instrumentId}`;
  const scale = meta?.priceScale ?? 2;
  const money = (value: number) => localPrice(value, position.currency, scale);
  /**
   * 금액 칸의 자릿수는 **결제 통화**를 따른다 (원화 0 · 달러 2) — 미리보기가 그 자릿수로
   * 답하므로 (`ord.md`), 어림치도 같은 자리에서 끊어야 서버 값과 모양이 갈리지 않는다.
   * 가격(평단·현재가)은 종목의 `priceScale` 이라 `money` 와 나뉜다.
   */
  const cash = (value: number) =>
    position.currency === "KRW" ? krw(value) : localPrice(value, "USD", 2);

  const qty = Math.min(
    sellable,
    Math.max(0, Math.floor(Number(quantity) || 0))
  );

  // 휴장은 정상 상태이고 예약은 열려 있다 — 다만 이 대화상자는 시장가 전용이라 함께 막힌다.
  const closed = price?.marketClosed ?? false;
  const trading = clock.phase === "TRADING";
  const marketOpen = trading && !closed;

  /**
   * 미리보기 조회. 입력이 멎으면 한 번 묻고, **새 종가가 오면 다시 묻는다** (35초마다 한
   * 번). 모달을 열어 둔 채 틱이 지나가는 일이 흔하므로, 묻지 않으면 화면의 금액만 이전
   * 틱에 멈춰 있고 체결은 새 종가로 나간다.
   */
  useEffect(() => {
    if (!marketOpen || qty <= 0) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      fetchOrderPreview({
        orderType: "MARKET",
        side: "SELL",
        instrumentId: position.instrumentId,
        quantity: qty,
      })
        .then((data) => {
          if (!cancelled) setAnswered({ quantity: qty, tickSeq, data });
        })
        // 표시용이라 실패해도 버튼을 잠그지 않는다 (`ord.md`).
        .catch((error: unknown) => {
          console.warn("매도 미리보기를 불러오지 못했습니다", error);
        });
    }, PREVIEW_DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [marketOpen, qty, tickSeq, position.instrumentId]);

  /**
   * 지금 화면에 쓸 미리보기. **수량과 Tick 이 둘 다 지금 것일 때만** 쓴다. 어느 하나라도
   * 어긋나면 어림치로 돌아간다 — 어림치는 방금 갱신된 현재가로 곱한 값이라 낡은 서버 값보다
   * 사실에 가깝고, 300ms 뒤 새 답이 와서 `≈` 가 사라진다.
   */
  const preview =
    answered?.quantity === qty && answered.tickSeq === tickSeq
      ? answered.data
      : null;

  /**
   * 체결의 기준이 될 종가. **미리보기가 있으면 그쪽이다** — 포지션 응답의 `currentPrice` 와
   * 미리보기의 `referencePrice` 가 서로 다른 틱을 가리킬 수 있어, 섞어 쓰면 "지금 종가" 와
   * 아래 금액이 어긋난 값으로 나란히 선다.
   */
  const referencePrice = preview
    ? Number(preview.referencePrice)
    : Number(position.currentPrice);

  const amount = qty * referencePrice;
  const feeRate = clock.feeFree ? 0 : BASE_FEE_RATE;
  const fee = preview ? Number(preview.estimatedFee) : amount * feeRate;
  // 매도의 총액은 `금액 − 수수료` 다 — 손에 들어오는 돈이다 (`ord.md`).
  const total = preview ? Number(preview.totalAmount) : amount - fee;

  /** 버튼이 잠긴 이유. 수량을 고쳐서 풀 수 없는 사유만 적는다. */
  const blocked = !trading
    ? "지금은 거래 시간이 아닙니다"
    : closed
      ? "휴장 중인 종목입니다"
      : null;

  async function sell() {
    trackEvent("order_submit", {
      side: "SELL",
      order_type: "MARKET",
      instrument_name: name,
    });

    setPlacing(true);
    try {
      const created = await placeOrder({
        orderType: "MARKET",
        side: "SELL",
        instrumentId: position.instrumentId,
        quantity: qty,
      });
      trackEvent("order_result", {
        side: "SELL",
        order_type: "MARKET",
        status: "SUCCESS",
      });
      // **체결가는 응답의 값을 그대로 옮긴다.** 누르는 사이에 틱이 넘어가면 화면이 보여 준
      // 종가와 다른 값에 체결되는데, 그때 화면의 숫자를 되풀이하면 사실과 다른 말이 된다.
      const executed = created.execution;
      toast.success(
        executed
          ? `${name} ${shares(qty)}를 ${money(Number(executed.executedPrice))} 에 매도했습니다.`
          : `${name} ${shares(qty)} 매도가 체결됐습니다.`
      );

      // 보유와 현금이 방금 바뀌었다. 다음 Tick 을 기다리면 그때까지 목록과 헤더가 옛 값을
      // 말한다.
      reloadPositions();
      await reloadValuation();
      onOpenChange(false);
    } catch (error: unknown) {
      // 잔고·수량 부족과 거래 시간 외가 409 로 온다 (`lib/order-api.ts`).
      trackEvent("order_result", {
        side: "SELL",
        order_type: "MARKET",
        status: "FAILED",
      });
      toast.error(
        error instanceof Error ? error.message : "매도에 실패했습니다."
      );
    } finally {
      setPlacing(false);
    }
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogContent className="data-[size=default]:sm:max-w-md">
        <AlertDialogHeader>
          {/* 브리핑·부재 요약·부족분 모달과 같은 머리 모양이다 — 무슨 종류의 모달인지를
              배지가 먼저 말하고, 제목은 이번 건을 말한다. */}
          <Badge variant="secondary" className="gap-1">
            <TrendingDown className="size-3" aria-hidden />
            시장가 매도
          </Badge>
          <AlertDialogTitle>{name}</AlertDialogTitle>
          <AlertDialogDescription>
            지금 종가 {money(referencePrice)} 에 즉시 체결됩니다. 지정가로
            걸려면 마켓 화면의 주문 패널을 쓰세요.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="flex flex-col gap-1.5">
          <span className="flex items-center gap-1 text-xs">
            {/* 매도 가능 수량은 **라벨 밖**에 둔다. 안에 넣으면 칸의 이름이
                "수량 · 매도 가능 4주" 가 되어 화면 낭독기가 매번 따라 읽는다. */}
            <Label
              htmlFor="quick-sell-quantity"
              className="text-xs text-muted-foreground"
            >
              수량
            </Label>
            <span className="font-semibold text-foreground tabular-nums">
              · 매도 가능 {shares(sellable)}
            </span>
          </span>
          {/* 수량을 다루는 모양은 마켓 화면의 주문 패널과 같다 — 칸 옆에 ∓ 한 쌍이고,
              여기에만 `전량` 이 더 붙는다. 이 화면은 파는 일만 하므로 상한이 정해져 있다. */}
          <div className="flex items-center gap-1">
            <Input
              id="quick-sell-quantity"
              inputMode="numeric"
              value={quantity}
              // 1주 단위 정수다 (기획 6.5.2). 다 지우면 0 으로 되돌린다 — 빈 칸과 0 을
              // 함께 두면 같은 뜻을 두 모양으로 보이게 된다.
              onChange={(event) =>
                setQuantity(
                  // 주문 패널과 같은 정규화를 쓴다. 빈 문자열만 이 화면의 관례인
                  // `"0"` 으로 되돌린다.
                  normalizeQuantity(event.target.value) || "0"
                )
              }
              className="w-20 tabular-nums"
            />
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="1주 빼기"
              disabled={qty === 0}
              onClick={() => setQuantity(String(Math.max(0, qty - 1)))}
            >
              <Minus aria-hidden />
            </Button>
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="1주 더하기"
              // 가진 것보다 많이 팔 수 없다. 넘겨 두면 서버가 409 로 막는다.
              disabled={qty >= sellable}
              onClick={() =>
                setQuantity(normalizeQuantity(String(qty + 1)) || "0")
              }
            >
              <Plus aria-hidden />
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="ml-1"
              onClick={() => setQuantity(String(sellable))}
            >
              전량
            </Button>
          </div>
          {/* 상한이 보유 수량과 다른 이유를 말해 둔다. */}
          {position.lockedQuantity > 0 && (
            <p className="text-2xs text-muted-foreground">
              예약 매도로 {shares(position.lockedQuantity)}가 잠겨 있습니다.
              예약을 취소하면 함께 팔 수 있습니다.
            </p>
          )}
        </div>

        <dl className="flex flex-col text-sm">
          <DialogRow label="평단">
            <span className="tabular-nums">
              {money(Number(position.avgPrice))}
            </span>
          </DialogRow>
          <DialogRow label={`수수료 (${(feeRate * 100).toFixed(2)}%)`}>
            <span className="tabular-nums">{cash(fee)}</span>
          </DialogRow>
          {/* 서버가 답한 값에는 `≈` 를 붙이지 않는다 — 어림치와 확정치를 같은 모양으로 두면
              어느 쪽을 믿어야 하는지 화면이 말해 주지 못한다. */}
          <DialogRow label="받는 금액">
            <span className="font-semibold tabular-nums">
              {preview ? "" : "≈ "}
              {cash(total)}
            </span>
          </DialogRow>
        </dl>

        {blocked && (
          <p role="alert" className="text-xs text-destructive">
            {blocked}
          </p>
        )}

        {/* 기본 여백(`p-4`)은 버튼 두 개짜리 줄에 견줘 위아래가 과하다. 좌우는 본문과
            맞춰 두고 세로만 줄인다. */}
        <AlertDialogFooter className="py-2.5">
          {/* 너비를 매도 버튼과 맞춘다. 색은 면 없는 테두리라 파란 매도와 갈린다 — 취소가
              같은 무게로 서면 무엇이 주 동작인지 색으로 읽히지 않는다. */}
          <AlertDialogCancel variant="outline" className="min-w-24">
            닫기
          </AlertDialogCancel>
          {/* 매도 버튼의 색은 주문 패널과 같은 의도 토큰이다 (`order-sell`) — 같은 일을
              하는 버튼이 화면마다 다른 색이면 무엇을 누르는지 색으로 읽을 수 없다.
              잠긴 모습도 그쪽 규칙을 그대로 가져온다. */}
          <AlertDialogAction
            className={cn(ORDER_BUTTON, ORDER_SELL)}
            disabled={blocked !== null || qty <= 0 || placing}
            onClick={(event) => {
              // 기본 동작은 모달을 닫는다. 실패하면 문구를 보여 줄 자리가 사라지므로 닫는
              // 것은 성공한 뒤에 우리가 한다.
              event.preventDefault();
              void sell();
            }}
          >
            {qty > 0 ? `${shares(qty)} 매도` : "매도"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
