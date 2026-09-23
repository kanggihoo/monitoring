"use client";

import { useEffect, useState } from "react";
import { Minus, Plus } from "lucide-react";

import { LoginPrompt } from "@/components/login-prompt";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { count, localPrice, shares } from "@/lib/format";
import { formatCountdown } from "@/lib/game-clock";
import { normalizeDecimal, normalizeQuantity } from "@/lib/numeric-input";
import type { OrderPreview } from "@/lib/order-api";
import { cn, TAB_LIST, TAB_TRIGGER } from "@/lib/utils";

/**
 * 매수·매도 버튼이 함께 쓰는 자리. 색만 각자 얹는다.
 *
 * **잠긴 버튼은 회색이 아니라 제 색의 옅은 판이다.** 회색으로 내려앉히면 매수와 매도가
 * 같은 모양이 되어, 무엇이 잠겼는지를 자리로만 알아내야 한다. 면은 제 색을 카드 면에
 * 섞어 옅히고 글자는 그 색을 한 단 누른다 — 뿌예 보이지만 무슨 버튼인지는 계속 읽힌다.
 *
 * `color-mix` 로 섞는 이유: 투명도(`/20`)는 뒤에 무엇이 오느냐에 따라 결과가 달라진다.
 * 카드 위와 모달 위에서 다른 색이 되면 안 된다.
 */
export const ORDER_BUTTON = "min-w-24 text-primary-foreground";

/** 매수 — 면·호버·잠긴 모습. */
const ORDER_BUY = cn(
  "bg-order-buy hover:bg-order-buy/80",
  "disabled:bg-[color-mix(in_oklch,var(--order-buy)_16%,var(--card))]",
  // 글자는 제 색을 한 단 눌러 둔다. 색 그대로 두면 옅은 면 위에서 4:1 에 그쳐
  // 읽기 힘들다 (S15P21A605-168 이 고친 그 문제다). 한 단 누르면 5:1 이 된다.
  "disabled:text-[color-mix(in_oklch,var(--order-buy)_85%,var(--foreground))]"
);

/** 매도 — 매수와 같은 규칙, 색만 다르다. 내 계좌의 빠른 매도도 이 색을 쓴다. */
export const ORDER_SELL = cn(
  "bg-order-sell hover:bg-order-sell/80",
  "disabled:bg-[color-mix(in_oklch,var(--order-sell)_16%,var(--card))]",
  // 글자는 제 색을 한 단 눌러 둔다. 색 그대로 두면 옅은 면 위에서 4:1 에 그쳐
  // 읽기 힘들다 (S15P21A605-168 이 고친 그 문제다). 한 단 누르면 5:1 이 된다.
  "disabled:text-[color-mix(in_oklch,var(--order-sell)_85%,var(--foreground))]"
);

/** 주문은 시장가와 예약(지정가) 두 축뿐이다 (기획 6장). 공매도·레버리지는 없다. */
export type OrderSide = "buy" | "sell";
export type OrderKind = "market" | "reserved";

/**
 * 편도 0.15% (왕복 0.3%). 무료 타임(12:00~12:10)에는 0 이 넘어온다.
 * 실제 수수료는 서버가 계산해 응답에 담는다 — 여기 값은 화면의 예상치다 (`_common.md §6.1`).
 */
export const BASE_FEE_RATE = 0.0015;

/**
 * 환전 수수료율. 서버의 {@code ExchangeQuote.FEE_RATE}(0.05%) 와 같은 값이며 무료 타임의
 * 대상이 아니다 (기획서 6.7.4). "최대 가능" 을 환전까지 끌어 써서 구할 때만 쓴다.
 */
const EXCHANGE_FEE_RATE = 0.0005;

/** 수량 칩이 제안하는 비율. 마지막은 최대다. */
const QUANTITY_RATIOS = [0.1, 0.25, 0.5] as const;

export type OrderSubmit = {
  side: OrderSide;
  kind: OrderKind;
  quantity: number;
  limitPrice: number | null;
};

/** 사용자가 지금까지 채운 주문. 미리보기를 부르는 쪽이 이 값을 받아 간다. */
export type OrderDraft = {
  /** 미리보기를 이 쪽 기준으로 물어야 수수료·부족분이 실제 주문과 같아진다. */
  side: OrderSide;
  kind: OrderKind;
  quantity: number;
  limitPrice: number | null;
};

/**
 * 주문 패널 — 상세 카드 아래에 가로로 놓인다 (시안). 매수·매도 버튼이 나란히 있고
 * 색은 주문 의도 토큰(`order-buy`·`order-sell`)이다.
 */
function OrderTicket({
  lastPrice,
  currency,
  priceScale,
  cash,
  holdingQuantity = 0,
  feeRate = BASE_FEE_RATE,
  feeFreeSeconds = null,
  marketOpen = true,
  tradingOpen = true,
  synced = true,
  isLoggedIn = false,
  preview = null,
  placedRevision = 0,
  exchangeableCash = 0,
  usdKrwRate = null,
  submitting = false,
  onDraftChange,
  onSubmit,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "onSubmit" | "children"> & {
  /** 현재 표시 중인 최신 확정 종가. 시장가는 이 가격에 즉시 체결된다 (기획 6.1.2). */
  lastPrice: number;
  currency: "KRW" | "USD";
  priceScale: number;
  /** 결제 통화의 주문 가능 현금. */
  cash: number;
  /**
   * **반대쪽 통화**의 환전 가능 현금. 부족분을 채울 수 있는지 판정하는 데 쓴다 — 미리보기는
   * 결제 통화 잔고만 알려 주므로, 보내는 쪽 잔고는 화면이 안다 (`valuation`).
   *
   * 잠긴 금액은 빠진 값이어야 한다: 예약·청약이 점유한 현금은 환전에 쓸 수 없다.
   */
  exchangeableCash?: number;
  /**
   * 그 게임 거래일의 원/달러 매매기준율. 금액을 원화로 함께 보여 주고, "최대 가능" 을
   * 환전까지 포함해 구하는 데 쓴다. 모르면 둘 다 환전 몫 없이 결제 통화로만 말한다.
   */
  usdKrwRate?: number | null;
  holdingQuantity?: number;
  feeRate?: number;
  /** 무료 타임 종료까지 남은 초. 무료 타임이 아니면 null. */
  feeFreeSeconds?: number | null;
  /** 휴장 중에는 시장가를 체결할 시세가 없다. 예약만 받는다. */
  marketOpen?: boolean;
  /**
   * 지금이 <b>거래 구간(11:05~16:40)</b>인가. 아니면 시장가도 예약도 받지 않는다.
   *
   * <b>{@code marketOpen} 과 다르다.</b> 휴장은 그날 시세가 없을 뿐 정상 상태라 예약이
   * 열려 있지만, 거래 시간이 아니면 서버가 둘 다 409 로 거절한다
   * ({@code PhaseGate.requireTrading}). 둘을 한 값으로 합치면 브리핑·정산·열람 전용
   * 구간에서 예약 탭이 열린 것처럼 보이고, 다 채워 누른 뒤에야 오류를 본다.
   *
   * 기본값은 열림이다 — 이 값을 넘기지 않는 화면(스토리·미리보기)까지 잠그지 않는다.
   */
  tradingOpen?: boolean;
  /**
   * 화면이 들고 있는 시세·잔고가 **지금 서버와 맞는가.** false 면 두 버튼을 함께 잠근다.
   *
   * 연결이 끊긴 동안에도 가격·자산·순위는 옛 값으로 남고 카운트다운은 계속 돈다 — 화면만
   * 보면 멀쩡하다. 그 상태로 낸 주문은 사용자가 본 것과 다른 가격·잔고로 판정된다.
   *
   * **휴장과 다르다.** 휴장은 정상 상태이고 예약 주문이 열려 있다 (`marketOpen` 참고).
   * 이쪽은 화면을 믿을 수 없는 상태라 예약도 함께 막는다.
   */
  synced?: boolean;
  /**
   * 현재 로그인 상태. 미인증이면 읽는 영역은 그대로 두고 주문 버튼 자리를 로그인 안내로
   * 바꾼다 — 진입점을 지우면 무엇을 할 수 있는 서비스인지 알 수 없고, 눌렀는데 아무 일도
   * 없으면 고장으로 오해한다 (ADR-0005).
   *
   * 기본값은 미인증이다. 빠뜨렸을 때 주문 버튼이 열리는 쪽이 위험하다.
   */
  isLoggedIn?: boolean;
  /**
   * 서버가 답한 **매수** 미리보기. 없으면(휴장·조회 실패) 화면이 스스로 어림한다 —
   * 미리보기는 표시용이라 못 받았다고 주문 패널을 잠글 이유가 없다 (`ord.md`).
   */
  preview?: OrderPreview | null;
  /**
   * 주문이 **접수된** 횟수. 바뀔 때마다 수량 칸을 비운다 — 방금 낸 수량이 남아 있으면 같은
   * 주문을 한 번 더 낸 것으로 오해하기 쉽고, 체결된 뒤의 한도·보유가 이미 달라져 있다.
   *
   * 누른 순간이 아니라 **성공한 순간**에 올린다 (`market-screen`). 실패(잔고 부족·거래
   * 시간 외)에 비우면 고쳐서 다시 낼 값을 사용자가 처음부터 쳐야 한다.
   */
  placedRevision?: number;
  /**
   * 주문이 서버에 가 있는 동안 참. 두 버튼을 함께 잠근다 — 매수를 누른 뒤 매도를 누를 수
   * 있으면 잔고를 한 번 본 판단으로 두 주문이 나간다.
   */
  submitting?: boolean;
  /** 입력이 바뀔 때마다 불린다. 미리보기 조회를 부르는 쪽이 받아 간다. */
  onDraftChange?: (draft: OrderDraft) => void;
  /**
   * 돈이 모자란 채로 눌린 매수도 여기로 온다. 부족분을 어떻게 안내할지는 이 패널이 아니라
   * 미리보기를 쥔 쪽이 정한다 — 잔고·환율이 거기 있다.
   */
  onSubmit?: (order: OrderSubmit) => void;
}) {
  const [kind, setKind] = useState<OrderKind>(
    marketOpen ? "market" : "reserved"
  );
  /**
   * 사고 있는가 팔고 있는가. **탭이지 버튼이 아니다** — 고른 쪽 기준으로 수수료·최대 수량·
   * 잠금 사유가 전부 달라지므로, 두 버튼을 나란히 두면 화면이 양쪽을 동시에 설명해야 한다.
   */
  const [side, setSide] = useState<OrderSide>("buy");
  const [quantity, setQuantity] = useState("");
  /**
   * `최대` 칩으로 채운 수량인가. **한도는 틱마다 움직인다** — 시세가 오르면 같은 돈으로 살
   * 수 있는 수량이 줄어드는데, 눌러 둔 옛 한도가 칸에 남아 있으면 그 주문은 서버에서
   * `부족` 으로 막힌다. 사용자가 본 것은 "최대" 였는데 낼 수 없는 수량이 되는 셈이다.
   *
   * 그래서 `최대` 로 채운 동안에는 한도를 따라간다. 손으로 고치거나 비율 칩·±·방향 전환이
   * 개입하면 그 순간 따라가기를 멈춘다 — 그때부터는 사용자가 정한 수량이다.
   */
  const [followMax, setFollowMax] = useState(false);
  const [limitPrice, setLimitPrice] = useState(lastPrice.toFixed(priceScale));
  /**
   * 이 패널의 금액 서식. **끝자리가 전부 0 인 소수점은 지운다** (`$221.00` → `$221`).
   *
   * 목록·차트의 `localPrice` 는 자릿수를 맞춰 세로로 읽히게 해야 하지만, 여기 숫자들은
   * 한 줄에 하나씩 서 있어 견줄 상대가 없다 — 그 자리에서 `.00` 은 읽을 것이 없는 두
   * 글자다. **일부만 0 인 끝자리는 남긴다**: `$199.70` 의 0 은 센트 자릿수를 말한다.
   */
  const trimZeroCents = (text: string) => text.replace(/\.0+$/, "");
  const money = (value: number) =>
    trimZeroCents(localPrice(value, currency, priceScale));
  /** 부족액은 종목 가격 자릿수가 아니라 결제 통화 자릿수(원 0, 달러 2)로 적는다. */
  const settlementMoney = (value: number) =>
    trimZeroCents(localPrice(value, currency, currency === "KRW" ? 0 : 2));
  const qty = Math.max(0, Math.floor(Number(quantity) || 0));
  // 시장가 미리보기가 왔다면 서버가 실제 주문 판정에 쓴 기준가가 진실이다. 화면의 다음 틱
  // 가격으로 최대 수량을 계산하면 서버에서는 더 살 수 있는데도 한도를 작게 말할 수 있다.
  const price =
    kind === "market"
      ? preview
        ? Number(preview.referencePrice)
        : lastPrice
      : Number(limitPrice) || 0;

  /** 서버 `OrderQuote` 와 같은 결제 통화 자릿수의 HALF_UP 금액이다. */
  const settlementScale = currency === "KRW" ? 1 : 100;
  const roundedOrderAmount = (orderQuantity: number) =>
    Math.round(orderQuantity * price * settlementScale) / settlementScale;

  // 미리보기가 있으면 그 값이 진실이다. 화면의 곱셈은 그것을 못 받았을 때의 어림치다.
  const amount = preview
    ? Number(preview.estimatedAmount)
    : roundedOrderAmount(qty);
  // **수수료는 매도에만 붙는다** (`OrderQuote`). 어림치도 그 계약을 따라야 미리보기가
  // 오기 전후로 금액이 널뛰지 않는다.
  const fee = preview
    ? Number(preview.estimatedFee)
    : side === "sell"
      ? amount * feeRate
      : 0;
  // 매수는 낼 돈, 매도는 받을 돈이다.
  const total = preview
    ? Number(preview.totalAmount)
    : side === "sell"
      ? amount - fee
      : amount;
  const available = preview ? Number(preview.availableCash) : cash;

  // 돈이 모자라도 **환전으로 채울 수 있으면** 매수를 잠그지 않는다. 그 길을 눌러서 알게
  // 되는 편이 낫다 — 부족분 확인이 얼마를 바꿀지까지 말해 준다. 부족분은 매수에만 생긴다
  // (`ord.md`).
  //
  // **`shortfallExchange` 가 있다고 채울 수 있는 것은 아니다.** 그 값은 "부족분이 있다" 는
  // 뜻이고 (부족하지 않으면 `null`), 보낼 돈이 있는지는 서버가 보지 않는다. 그래서 보내는
  // 통화 잔고와 견주는 일은 여기서 한다.
  const shortfallExchange = preview?.shortfallExchange ?? null;
  const sendAmount = shortfallExchange
    ? Number(shortfallExchange.sendAmount)
    : 0;
  const fillable = shortfallExchange !== null && sendAmount <= exchangeableCash;
  /** 환전해도 모자란 금액. 보내는 통화 기준이다. */
  const unfillableBy = shortfallExchange ? sendAmount - exchangeableCash : 0;

  // **미리보기를 못 받았으면 어림치로 잠그지 않는다.** 부족분을 아는 것은 서버뿐이고, 그
  // 답이 오기 전(디바운스 300ms)이나 조회가 실패한 뒤에 잠가 두면 달러가 0 인 채 미국
  // 종목을 고른 사람에게 버튼이 이유 없이 죽어 있다 — 실패한 조회는 다시 오지도 않는다.
  // 누르면 부르는 쪽이 그 자리에서 묻고 부족분이면 환전 확인으로 보낸다 (`market-screen`).
  const canSell =
    tradingOpen && synced && qty > 0 && price > 0 && qty <= holdingQuantity;

  /**
   * 환전으로 끌어 올 수 있는 **결제 통화** 금액. 서버도 수령액을 내림하므로(`ExchangeQuote`)
   * 여기서도 내림한다 — 올리면 "최대 가능" 이 실제보다 커져 그 수량이 체결되지 않는다.
   */
  const exchangeableInSettlement = (() => {
    if (!usdKrwRate || usdKrwRate <= 0 || exchangeableCash <= 0) return 0;
    const net = exchangeableCash * (1 - EXCHANGE_FEE_RATE);
    const converted = currency === "KRW" ? net * usdKrwRate : net / usdKrwRate;
    const scale = currency === "KRW" ? 1 : 100;
    return Math.floor(converted * scale) / scale;
  })();

  /** 지금 낼 수 있는 최대 수량. 매수는 가진 돈이, 매도는 가진 주식이 한도다. */
  const budget = available + exchangeableInSettlement;
  const estimatedMaxQuantity = (() => {
    if (side === "sell") return holdingQuantity;
    if (price <= 0 || budget <= 0) return 0;

    // 서버는 `가격 × 수량`을 원화 0자리·달러 2자리로 HALF_UP 한 뒤 잔고와 비교한다.
    // 단가가 1센트보다 작은 코인은 여러 주를 더 담아도 청구액이 같을 수 있으므로 단순히
    // `floor(잔고 / 가격)` 하면 실제 최대보다 작다. 같은 판정으로 마지막 가능한 수량을 찾는다.
    let low = 0;
    let high = Math.ceil((budget + 1 / settlementScale) / price) + 1;
    while (low < high) {
      const middle = Math.ceil((low + high) / 2);
      if (roundedOrderAmount(middle) <= budget) low = middle;
      else high = middle - 1;
    }
    return low;
  })();

  /**
   * 서버가 알려 준 부족분을 몇 주 줄여야 메울 수 있는지 환산한다.
   *
   * `exchangeableCash` 는 계좌 스냅샷이라 주문 미리보기가 본 실제 주문 가능액과 잠깐
   * 어긋날 수 있다. 그때 로컬 계산만 믿으면 `최대` 를 눌렀는데도 서버가 `4,672원 부족`
   * 이라고 답한다. 미리보기의 `shortfallExchange.sendAmount` 가 로컬 잔고를 넘었다면 그
   * 차이를 **보내는 통화 기준 한 주 가격**으로 나눠 한도를 낮춘다.
   *
   * 올림하는 이유는 한 주 미만의 부족분도 한 주를 빼야 주문이 가능하기 때문이다. 다음
   * 미리보기가 새 수량을 검증하므로 환율·수수료 반올림의 최종 판단은 여전히 서버가 한다.
   */
  const serverMaxBasis = [
    side,
    kind,
    price,
    cash,
    exchangeableCash,
    usdKrwRate,
  ].join(":");
  const [serverMax, setServerMax] = useState<{
    basis: string;
    quantity: number;
  } | null>(null);
  const rememberedServerMax =
    serverMax?.basis === serverMaxBasis ? serverMax.quantity : null;

  const serverShortfallQuantity = (() => {
    if (
      side !== "buy" ||
      !shortfallExchange ||
      fillable ||
      unfillableBy <= 0 ||
      !usdKrwRate ||
      usdKrwRate <= 0 ||
      price <= 0 ||
      qty !== estimatedMaxQuantity
    ) {
      return 0;
    }

    const sendCostPerShare =
      currency === "USD"
        ? (price * usdKrwRate) / (1 - EXCHANGE_FEE_RATE)
        : price / usdKrwRate / (1 - EXCHANGE_FEE_RATE);

    return Math.max(1, Math.ceil(unfillableBy / sendCostPerShare));
  })();

  const answeredServerMax =
    serverShortfallQuantity > 0
      ? Math.max(0, estimatedMaxQuantity - serverShortfallQuantity)
      : null;
  if (answeredServerMax !== null && answeredServerMax !== rememberedServerMax) {
    // 미리보기 prop 은 수량 state 보다 한 렌더 늦게 사라진다. 서버가 답한 상한을 기억하지
    // 않으면 1,687 → 1,686 → 1,687 로 왕복한다. 같은 가격·잔고 기준에서만 기억한다.
    setServerMax({ basis: serverMaxBasis, quantity: answeredServerMax });
  }

  const maxQuantity = Math.max(
    0,
    Math.min(
      estimatedMaxQuantity,
      answeredServerMax ?? rememberedServerMax ?? estimatedMaxQuantity
    )
  );
  const exceedsMax =
    side === "buy" && maxQuantity > 0 && qty > maxQuantity && !fillable;
  /** 화면에 표시한 최대 수량보다 더 담은 주문 금액. 두 숫자가 같은 기준으로 읽혀야 한다. */
  const maxShortfall = Math.max(
    0,
    roundedOrderAmount(qty) - roundedOrderAmount(maxQuantity)
  );
  const canBuy =
    tradingOpen &&
    synced &&
    qty > 0 &&
    price > 0 &&
    !exceedsMax &&
    (!preview || total <= available || fillable);

  /** 원화 환산. 결제 통화가 원화면 환산할 것이 없다. */
  // **반올림해서 넘긴다.** `localPrice` 의 원화 서식은 소수 자릿수를 따로 지정하지 않아
  // 환산에서 나온 끝자리가 그대로 붙는다 (243,228.323원). 원화에는 보조 단위가 없다.
  const inKrw = (value: number) =>
    currency === "KRW" || !usdKrwRate || usdKrwRate <= 0
      ? null
      : localPrice(Math.round(value * usdKrwRate), "KRW", 0);

  /**
   * 버튼이 잠긴 이유. **수량을 채운 뒤에만 말한다** — 빈 칸에 대고 "부족" 이라고 하면
   * 아직 아무것도 안 한 사람에게 잘못했다고 하는 것이 된다.
   *
   * 매수와 매도는 막히는 까닭이 서로 다르다 (돈이 모자람 / 주식이 모자람). 둘 다 걸리면
   * 둘 다 적는다 — 하나만 적으면 나머지 버튼은 왜 잠겼는지 알 수 없다.
   */
  const blockedReasons = [
    // **가장 먼저 말한다.** 거래 시간이 아니면 무엇을 고쳐도 풀리지 않으므로, 수량이나
    // 잔고 이야기를 앞세우면 고칠 수 있는 문제처럼 읽힌다. 서버도 같은 이유로 거절한다
    // (`PhaseGate.requireTrading`).
    !tradingOpen ? "지금은 거래 시간이 아닙니다" : null,
    // **수량과 무관한 유일한 사유다.** 아래 둘과 달리 입력을 고쳐서 풀 수 없으므로 빈 칸에도
    // 적는다 — 버튼이 왜 죽어 있는지 말하지 않으면 고장으로 읽힌다.
    !synced ? "실시간 시세·잔고를 다시 맞추는 중" : null,
    // **지정가가 비어 있으면 그 사실을 말한다.** 가격이 0 이면 두 버튼이 함께 잠기는데
    // (`canBuy`·`canSell` 의 `price > 0`), 아래 사유들은 전부 수량 이야기라 여기서 말하지
    // 않으면 칸을 비운 사람에게는 버튼이 까닭 없이 죽어 있다. 시장가에는 해당이 없다 —
    // 그쪽 가격은 사용자가 정하지 않는다.
    kind === "reserved" && price <= 0 ? "지정가를 입력해 주세요" : null,
    !fillable && shortfallExchange !== null
      ? `${settlementMoney(maxShortfall)} 부족`
      : exceedsMax
        ? `${settlementMoney(maxShortfall)} 부족`
        : null,
    // **매도에만 해당한다.** 가진 것보다 많이 적는 일은 매수에서 정상이다 — 그쪽 한도는
    // 가진 돈이고, 이 문구가 매수에 뜨면 살 수 있는 수량을 적어 두고 못 산다고 읽는다.
    //
    // **한 주도 없으면 말하지 않는다.** 살 것을 고르다 매도 탭을 잠깐 눌러 본 사람에게도
    // 수량을 칠 때마다 "수량 부족" 이 뜬다 — 팔 것이 없다는 사실은 그 자체로 분명하다.
    // 몇 주를 가졌는지는 수량 칸 위에 늘 서 있으므로 여기서 되풀이하지 않는다.
    side === "sell" && holdingQuantity > 0 && qty > holdingQuantity
      ? "보유 수량 부족"
      : null,
  ].filter((reason) => reason !== null);

  /**
   * 주문이 접수되면 수량을 비운다.
   *
   * 효과가 아니라 **렌더 중에** 맞춘다 — 값이 바뀐 것을 본 그 렌더에서 비우면 빈 칸이 한
   * 번에 그려지고, 효과로 미루면 방금 낸 수량이 한 프레임 더 서 있다가 사라진다
   * (react.dev "You Might Not Need an Effect" 의 prop 변화에 state 맞추기).
   */
  const [placedSeen, setPlacedSeen] = useState(placedRevision);
  if (placedRevision !== placedSeen) {
    setPlacedSeen(placedRevision);
    setFollowMax(false);
    setQuantity("");
  }

  /**
   * `최대` 로 채운 수량은 한도를 따라 움직인다. 틱마다 시세가 바뀌어 한도가 줄면 옛 한도로
   * 낸 주문이 `부족` 으로 막히기 때문이다 (위 `followMax`).
   */
  const nextMax = maxQuantity > 0 ? String(maxQuantity) : "";
  if (followMax && quantity !== nextMax) {
    setQuantity(nextMax);
  }

  // 입력이 멎을 때까지 기다리는 것은 부르는 쪽의 몫이다. 여기서는 무엇이 바뀌었는지만 알린다.
  useEffect(() => {
    onDraftChange?.({
      side,
      kind,
      quantity: qty,
      limitPrice: kind === "reserved" ? price : null,
    });
  }, [side, kind, qty, price, onDraftChange]);

  const submit = () =>
    onSubmit?.({
      side,
      kind,
      quantity: qty,
      limitPrice: kind === "reserved" ? price : null,
    });

  return (
    <Card
      size="sm"
      className={cn("@container/order w-full", className)}
      {...props}
    >
      {/* 도구줄 — 무엇을(방향) 어떻게(유형) 낼지만 고른다. 값은 아래 줄이 받는다. */}
      <CardContent className="flex flex-wrap items-center gap-3 md:flex-nowrap md:overflow-x-auto">
        <Tabs
          className="shrink-0"
          value={side}
          onValueChange={(value) => {
            setSide(value as OrderSide);
            setFollowMax(false);
            // **방향을 바꾸면 수량을 비운다.** 매수에 적은 170 은 가진 돈에서 나온
            // 수량이라 매도에서는 뜻이 없다 — 한도가 보유 주식으로 바뀌므로 그대로
            // 두면 대개 "보유 수량 부족" 인 채로 서 있고, 운 나쁘면 팔 생각이 없던
            // 수량이 그대로 눌린다.
            setQuantity("");
          }}
          aria-label="주문 방향"
        >
          <TabsList
            className={cn(TAB_LIST, "group-data-horizontal/tabs:h-[37px]")}
          >
            {/* 고른 쪽이 주문 의도 색을 입는다 — 무엇을 하려는지가 아래 버튼까지 가기
                전에 여기서 먼저 읽힌다. */}
            <TabsTrigger
              value="buy"
              className={cn(
                TAB_TRIGGER,
                "px-5 text-sm data-active:bg-order-buy dark:data-active:bg-order-buy"
              )}
            >
              매수
            </TabsTrigger>
            <TabsTrigger
              value="sell"
              className={cn(
                TAB_TRIGGER,
                "px-5 text-sm data-active:bg-order-sell dark:data-active:bg-order-sell"
              )}
            >
              매도
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <span aria-hidden className="h-6 w-px bg-border" />

        {/* 유형. **`지정가`·`시장가` 를 따로 두지 않는다** — 일반 주문이 곧 시장가이고
            예약 주문이 곧 지정가라, 한 번 더 고르게 하면 같은 것을 두 번 묻는 셈이다. */}
        <Tabs
          className="shrink-0"
          value={kind}
          onValueChange={(value) => setKind(value as OrderKind)}
          aria-label="주문 유형"
        >
          <TabsList
            className={cn(TAB_LIST, "group-data-horizontal/tabs:h-[37px]")}
          >
            <TabsTrigger
              value="market"
              disabled={!marketOpen}
              className={cn(TAB_TRIGGER, "px-5 text-sm")}
            >
              일반 주문
            </TabsTrigger>
            <TabsTrigger
              value="reserved"
              className={cn(TAB_TRIGGER, "px-5 text-sm")}
            >
              예약 주문
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <span className="ml-auto min-w-0 shrink md:truncate">
          {feeFreeSeconds !== null ? (
            <Badge variant="secondary" className="text-sm">
              수수료 무료 타임 · 종료까지 {formatCountdown(feeFreeSeconds)}
            </Badge>
          ) : (
            // 폰에서는 접는다. 한 줄로 두면 327px 라 카드(312px)를 넘고, 카드가
            // `overflow-hidden` 이라 넘친 만큼은 스크롤도 없이 사라진다 — 주문 조건을
            // 고지하는 문장이 고지되지 않는다.
            <Badge
              variant="outline"
              className="text-sm max-md:whitespace-normal"
            >
              매도 수수료 0.15% | 매일 12:00 10분간 수수료 무료
            </Badge>
          )}
        </span>
      </CardContent>

      {/* 값을 채우는 줄. 수량 → 가격 → 금액 → 실행 순서로 읽힌다.
       *
       * **열마다 폭과 줄 높이를 고정한다.** 그러지 않으면 두 가지가 무너진다 — 예약 주문의
       * 지정가 칸이 현재가 글자보다 넓어 실행 버튼이 다음 줄로 밀려 카드가 통째로 커지고,
       * 라벨 줄의 높이가 칸마다 달라 `수량`·`지정가`·`예상 체결 금액` 이 서로 다른 눈높이에
       * 선다. 라벨 h-5, 값 h-10, 보조 h-5 로 세 칸이 같은 격자를 쓴다.
       *
       * **기준은 화면이 아니라 카드 자신의 폭이다 (`@container/order`).** 화면이 넓어도 이 카드는
       * 3열 마켓 화면 가운데 칸에 들어가 좁을 수 있다 — `md:` 로 재면 1280 폭에서 카드가
       * 440px 인데도 "넓은 화면" 으로 쳐서 한 줄을 강요하고, 값 줄이 254px 넘쳐 가로 스크롤이
       * 생겼다. 임계값 `40rem` 은 값 줄이 최소 폭으로 접혔을 때 필요한 폭이며, 칸 폭이 전부
       * rem 이라 루트 배율(110%)을 따라 같이 움직인다.
       *
       * **그 위로는 줄바꿈이 없다.** 접히는 순간 카드가 한 줄만큼 자라고 실행 버튼이
       * 값에서 떨어져 나가므로, 폭이 모자라면 접는 대신 **줄인다** — 값 칸 셋은 `shrink` 로
       * `min-w` 까지 좁아지고 그 아래로는 글자가 잘린다(`truncate`). 실행 버튼만 `shrink-0`
       * 이다: 눌러야 하는 것은 줄일 수 없다. 그 `min-w` 합보다도 좁아지면 **가로로 민다**
       * (`overflow-x-auto`) — 잘라 버리면 그 폭에서는 주문 버튼에 손이 닿지 않는다.
       *
       * **`40rem` 아래에서는 접는다.** 값 줄이 필요로 하는 598px 는 폰 카드(351px)의 갑절에
       * 가까워, 안 접으면 화면 너비의 두 배를 옆으로 밀어야 주문 버튼에 닿는다. 접기를 막는
       * 쪽이 도리어 못 쓰게 만드는 폭이 있고, 폰이 그 폭이다 (`0002` 의 모바일 셸). 칸 **안쪽**도 같은 규칙이라 라벨·보조 문구에
       * `whitespace-nowrap` 이 붙는다 — 글자가 접히면 고정한 줄 높이가 깨진다.
       */}
      <CardContent className="flex flex-wrap items-start gap-x-6 gap-y-3 border-t border-border pt-3 @min-[40rem]/order:flex-nowrap @min-[40rem]/order:gap-y-0 @min-[40rem]/order:overflow-x-auto">
        <div className="flex w-52 shrink-0 flex-col gap-1.5">
          <span className="flex h-5 items-center gap-2 overflow-hidden text-sm whitespace-nowrap">
            {/* `수량` 이라는 글자는 두지 않는다 — 바로 아래 칸의 `수량 입력` 자리글이
                같은 말을 하고 있고, 그 두 글자가 자리를 차지한 만큼 옆의 숫자가 잘렸다.
                낭독기에는 남는다 (`sr-only`). */}
            <Label htmlFor="order-quantity" className="sr-only">
              수량
            </Label>
            {/* 가진 것과 낼 수 있는 것을 수량 칸 **바로 위**에 둔다. 몇 주를 적을지 정하는
                자리에서 쓰는 숫자라, 카드 위쪽이나 헤더로 보내면 눈이 떠났다 와야 한다.

                **단위는 한 번만 붙인다** (`보유 1,234,567 · 최대 6,982,353주`). 둘 다
                주식 수라 `주` 를 두 번 적을 이유가 없고, 저가 코인은 일곱 자리로 사고
                파는데 두 번 붙이면 193px 이 되어 192px 칸을 넘는다 — 넘친 만큼은
                잘린다(`overflow-hidden`).

                **매도는 `보유` 하나다.** 그쪽 한도는 곧 보유 수량이라(`maxQuantity`)
                둘을 나란히 두면 같은 수를 두 번 적는 셈이다. */}
            {isLoggedIn && (
              <span className="truncate text-muted-foreground tabular-nums">
                {side === "sell"
                  ? `보유 ${shares(holdingQuantity)}`
                  : holdingQuantity > 0
                    ? `보유 ${count(holdingQuantity)} · 최대 ${shares(maxQuantity)}`
                    : `최대 ${shares(maxQuantity)}`}
              </span>
            )}
          </span>
          <div className="flex h-10 flex-nowrap items-center gap-1">
            <Input
              id="order-quantity"
              inputMode="numeric"
              placeholder="수량 입력"
              // **보이는 값만 끊는다.** 상태는 숫자만 들고 있고(`quantity`),
              // `normalizeQuantity` 가 숫자 아닌 글자를 지우므로 되돌아올 때 쉼표가
              // 저절로 빠진다 — 상태에 쉼표를 넣으면 `Number()` 가 NaN 을 본다.
              value={
                quantity === "" ? "" : Number(quantity).toLocaleString("ko-KR")
              }
              onChange={(e) => {
                setFollowMax(false);
                setQuantity(normalizeQuantity(e.target.value));
              }}
              className="w-full text-base tabular-nums"
            />
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="1주 빼기"
              // 0 은 수량이 아니다. 빈 칸으로 되돌려 입력 칸이 "000" 같은 값을
              // 들고 있지 않게 한다.
              onClick={() => {
                setFollowMax(false);
                setQuantity(qty > 1 ? normalizeQuantity(String(qty - 1)) : "");
              }}
            >
              <Minus aria-hidden />
            </Button>
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="1주 더하기"
              onClick={() => {
                setFollowMax(false);
                setQuantity(normalizeQuantity(String(qty + 1)));
              }}
            >
              <Plus aria-hidden />
            </Button>
          </div>
          {/* 비율 칩. **최대에서 잘라 쓴다** — 전부 같은 한도에서 나오므로 `최대` 만
              맞으면 나머지도 맞는다. */}
          <div className="flex h-8 flex-nowrap items-center gap-1 overflow-hidden">
            {isLoggedIn &&
              QUANTITY_RATIOS.map((ratio) => (
                <Button
                  key={ratio}
                  type="button"
                  variant="outline"
                  size="xs"
                  disabled={!synced || maxQuantity < 1}
                  onClick={() => {
                    setFollowMax(false);
                    setQuantity(String(Math.floor(maxQuantity * ratio)));
                  }}
                  className="text-[13px] tabular-nums"
                >
                  {ratio * 100}%
                </Button>
              ))}
            {isLoggedIn && (
              <Button
                type="button"
                variant="outline"
                size="xs"
                disabled={!synced || maxQuantity < 1}
                onClick={() => {
                  setFollowMax(true);
                  setQuantity(String(maxQuantity));
                }}
                className={cn(
                  "text-[13px]",
                  side === "buy" ? "text-order-buy" : "text-order-sell"
                )}
              >
                최대
              </Button>
            )}
          </div>
        </div>

        {/* 가격 칸. 일반·예약이 **같은 폭**이어야 탭을 바꿔도 뒤의 칸들이 제자리에 선다. */}
        <div className="flex w-28 min-w-24 shrink flex-col gap-1.5">
          <span className="flex h-5 items-center gap-2 overflow-hidden text-sm whitespace-nowrap text-muted-foreground">
            {kind === "reserved" ? (
              <>
                <Label htmlFor="order-limit" className="text-sm">
                  지정가
                </Label>
                <Button
                  type="button"
                  variant="link"
                  size="xs"
                  onClick={() => setLimitPrice(lastPrice.toFixed(priceScale))}
                  className="ml-auto h-5 py-0"
                >
                  현재가 맞춤
                </Button>
              </>
            ) : (
              "현재가"
            )}
          </span>
          {kind === "reserved" ? (
            <Input
              id="order-limit"
              inputMode="decimal"
              value={limitPrice}
              onChange={(e) =>
                setLimitPrice(normalizeDecimal(e.target.value, priceScale))
              }
              className="h-10 w-full text-base tabular-nums"
            />
          ) : (
            <span className="flex h-10 items-center truncate text-xl font-semibold whitespace-nowrap tabular-nums">
              {money(lastPrice)}
            </span>
          )}
          <span className="flex h-5 items-center truncate text-sm whitespace-nowrap text-muted-foreground tabular-nums">
            {inKrw(price || lastPrice) ? `≈ ${inKrw(price || lastPrice)}` : ""}
          </span>
        </div>

        <div className="flex w-40 min-w-28 shrink flex-col gap-1.5">
          <span className="flex h-5 items-center truncate text-sm whitespace-nowrap text-muted-foreground">
            예상 체결 금액
          </span>
          <span className="flex h-10 items-center truncate text-xl font-semibold whitespace-nowrap tabular-nums">
            {money(total)}
          </span>
          {/* 수수료는 **적지 않는다.** 매도의 `예상 체결 금액` 은 이미 수수료를 뺀 받을
              돈이라(`total`), 같은 자리에 요율까지 늘어놓으면 어느 쪽이 실제로 들어오는
              돈인지 한 번 더 셈해야 한다. 요율은 도구줄의 배지가 늘 말하고 있다. */}
          <span className="flex h-5 items-center overflow-hidden text-sm whitespace-nowrap text-muted-foreground tabular-nums">
            {inKrw(total) && <span>≈ {inKrw(total)}</span>}
          </span>
        </div>

        {isLoggedIn ? (
          <div className="ml-auto flex shrink-0 flex-col items-end gap-1.5">
            {/* **잠긴 버튼은 이유를 말한다.** 자리는 늘 비워 둔다 — 문구가 생길 때만
                그리면 그 순간 패널이 한 줄만큼 자라 버튼이 아래로 밀린다.

                색은 `destructive` 다. 파랑은 이 앱에서 하락 전용이라(DESIGN.md) 막힌
                상태에 쓰면 등락으로 읽힌다. */}
            <p
              role="status"
              className="flex h-5 items-center truncate text-sm whitespace-nowrap text-destructive"
            >
              {blockedReasons.join(" · ")}
            </p>

            {/* 버튼 하나다. 무엇을 하는지는 위의 두 탭이 이미 말했고, 버튼은 그것을
                되풀이해 확인만 받는다. */}
            <Button
              disabled={(side === "buy" ? !canBuy : !canSell) || submitting}
              onClick={submit}
              className={cn(
                ORDER_BUTTON,
                side === "buy" ? ORDER_BUY : ORDER_SELL,
                "whitespace-nowrap",
                // 값 칸의 입력·숫자와 같은 h-10 이다. `lg` 로 키우면 이 칸만 높아져
                // 밑선이 어긋나고, 그 차이만큼 카드가 자란다.
                "h-10 px-6 text-base"
              )}
            >
              {side === "buy" ? "매수" : "매도"} 주문
            </Button>
          </div>
        ) : (
          <LoginPrompt
            message="주문은 로그인 후에 넣을 수 있습니다"
            label="로그인하고 주문"
            className="ml-auto py-0"
          />
        )}
      </CardContent>
    </Card>
  );
}

export { OrderTicket };
