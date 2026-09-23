import { apiErrorMessage, apiFetch } from "@/lib/api";
import { readJson, readPersonalJson } from "@/lib/market-api";

/** 환전 방향. 받는 통화가 아니라 **보내는 통화**가 앞에 온다 (`acc.md`). */
export type ExchangeDirection = "KRW_TO_USD" | "USD_TO_KRW";

/**
 * 부족분을 채우는 환전 요청. 미리보기가 통째로 내려준다.
 *
 * **프론트가 만들지 않는다.** `shortfall` 은 받는 통화(부족한 달러) 기준인데 환전은 보내는
 * 통화(원화) 기준이라, 환율과 수수료 0.05% 를 역산하면 반올림이 서버와 갈려 환전한 뒤에도
 * 1원이 모자라 주문이 다시 막힌다. 그래서 **반올림은 한 곳에서만 한다** (`ord.md`).
 */
export type ShortfallExchange = {
  direction: ExchangeDirection;
  /** 보내는 통화 기준 필요액. 환전 수수료를 포함한 올림값이다. */
  sendAmount: string;
};

/** `GET /api/v1/orders/preview` 의 응답 (`ord.md`). */
export type OrderPreview = {
  currency: "KRW" | "USD";
  referencePrice: string;
  estimatedAmount: string;
  /** 적용 요율(%). **매수는 언제나 0** 이고 매도만 무료 타임의 영향을 받는다. */
  appliedFeeRate: number;
  estimatedFee: string;
  /** 매수는 금액과 같고, 매도는 `금액 − 수수료` 다. */
  totalAmount: string;
  /** 잠기지 않은 결제 통화 현금. 내림한 값이라 이 숫자까지는 실제로 낼 수 있다. */
  availableCash: string;
  /** 결제 통화 기준 부족분. 부족하지 않으면 `"0"`, 매도는 언제나 `"0"`. */
  shortfall: string;
  /** 부족하지 않으면 `null`. */
  shortfallExchange: ShortfallExchange | null;
  feeFree: boolean;
};

export type OrderPreviewQuery = {
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  /** `LIMIT` 일 때만 보낸다. */
  limitPrice?: number | null;
};

/**
 * 주문 미리보기. 사용자가 수량을 치는 동안 여러 번 불리므로 부작용이 없어야 하고, 그래서
 * 계약이 `GET` 이다 (`ord.md`). 예약도 만들지 않고 체결 판정에도 관여하지 않는 **표시용**이다.
 *
 * `TRADING` 에서만 답한다 — 그 밖의 구간에서는 화면이 스스로 어림한 값을 쓴다.
 */
export function fetchOrderPreview(query: OrderPreviewQuery) {
  const params = new URLSearchParams({
    orderType: query.orderType,
    side: query.side,
    instrumentId: String(query.instrumentId),
    quantity: String(query.quantity),
  });
  if (query.orderType === "LIMIT" && query.limitPrice != null) {
    params.set("limitPrice", String(query.limitPrice));
  }

  return readJson<OrderPreview>(`/api/v1/orders/preview?${params}`);
}

/**
 * 환전 미리보기 (`acc.md`). **환율·수수료·수령액의 진실은 서버다.**
 *
 * 프론트가 `send × 0.05 / 100` 을 계산하면 안 되는 이유는 반올림이다 — 서버는 보내는 통화의
 * 자릿수(원화 0, 달러 2)로 두 번 반올림하는데, 화면이 그것을 흉내 내면 값이 갈린다. 부족분
 * 환전이 `shortfallExchange` 를 통째로 내려받는 것과 같은 이유다.
 *
 * `availableCash` 는 **내림한** 값이라 그 숫자까지는 실제로 보낼 수 있다.
 *
 * `TRADING` 에서만 답한다.
 */
export type ExchangePreview = {
  direction: ExchangeDirection;
  rate: string;
  sendAmount: string;
  /** 요율(%). **항상 0.05** 이고 무료 타임 대상이 아니다. */
  feeRate: number;
  /** 보내는 통화로 부과된다. */
  fee: string;
  receiveAmount: string;
  /** 잠기지 않은 **보내는 통화** 현금. 통화는 `direction` 이 정한다. */
  availableCash: string;
};

/** 환전 한 건. 실행 응답과 이력 항목이 같은 형태다 (`acc.md`). */
export type Exchange = {
  exchangeId: number;
  direction: ExchangeDirection;
  rate: string;
  sendAmount: string;
  feeRate: number;
  fee: string;
  receiveAmount: string;
  gameDate: string;
  tickSeq: number;
  /** 게임 시각이 아니라 **실제** 환전 시각이다 (기획 7.4.1). */
  exchangedAt: string;
};

/** 환전 이력 페이지 (`_common.md §5`). 최신순 고정. */
export type ExchangePage = {
  content: Exchange[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

export const fetchExchangePreview = (
  direction: ExchangeDirection,
  amount: string
) =>
  readPersonalJson<ExchangePreview>(
    `/api/v1/accounts/me/exchanges/preview?${new URLSearchParams({ direction, amount })}`
  );

export const fetchExchanges = (page = 0, size = 20) =>
  readPersonalJson<ExchangePage>(
    `/api/v1/accounts/me/exchanges?page=${page}&size=${size}`
  );

/**
 * 환전 실행. **미리보기가 준 `sendAmount` 를 값 그대로 보낸다.**
 *
 * 필드 이름만 옮긴다 — 미리보기는 보내는 금액을 `sendAmount` 로 부르고 환전 요청은 `amount`
 * 로 받는다 (`acc.md`). 이름이 다를 뿐 값은 손대지 않는다. 여기서 자릿수를 다듬거나 다시
 * 계산하면 부족분 객체가 존재하는 이유가 사라진다.
 *
 * 부족분 환전(`shortfallExchange`)과 환전 패널이 **같은 함수를 쓴다** — 둘 다 `{방향,
 * 보내는 금액}` 이고, 나누면 요청 본문 만드는 코드가 두 벌이 된다.
 *
 * @throws 실패 응답의 문구를 담은 Error. 잔고 부족은 409 로 온다
 */
export async function submitExchange(
  request: ShortfallExchange
): Promise<Exchange> {
  const response = await apiFetch("/api/v1/accounts/me/exchanges", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      direction: request.direction,
      amount: request.sendAmount,
    }),
  });

  if (!response.ok) {
    throw new Error(await apiErrorMessage(response, "환전에 실패했습니다."));
  }
  return ((await response.json()) as { data: Exchange }).data;
}

// ─────────────────────────────────────────────────────────────────────────────
// 주문 등록·조회·정정·취소, 그리고 체결 내역 (`ord.md`).
//
// 미리보기와 달리 여기부터는 **상태를 바꾼다** — 등록·정정·취소는 `apiFetch` 를 거쳐
// 위조 방지 토큰을 실어야 한다 (ADR-0001). 조회 둘은 개인 조회라 401 이 세션 만료다.
// ─────────────────────────────────────────────────────────────────────────────

/** 미체결 예약 한 건. `GET /api/v1/orders` 의 `orders[]` (`ord.md`). */
export type OpenOrder = {
  orderId: number;
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  limitPrice: string;
  currency: "KRW" | "USD";
  /** **매수만.** `지정가 × 수량` — 매수에 수수료가 없어 잠금액이 곧 체결 금액이다. */
  lockedAmount: string | null;
  /** **매도만.** 잠긴 보유 수량. */
  lockedQuantity: number | null;
  expiresAt: string;
  createdGameDate: string;
  createdAt: string;
};

/**
 * 미체결 예약 목록. 페이지가 없다 — 틱마다 갱신되는 목록이라 나눠 읽으면 페이지 사이에
 * 체결이 일어나 같은 예약이 두 번 보이거나 빠진다 (`ord.md`).
 */
export type OpenOrders = {
  /** 공개 기준 틱. 최초 공개 전에는 `null`. */
  asOfTickSeq: number | null;
  orders: OpenOrder[];
};

/**
 * 체결 한 건. 불변이며 복기·시즌 리포트의 원천이다 (`ord.md`).
 *
 * `appliedFeeRate` 와 `realizedPnl` 은 **저장값**이다 — 매도 요율이 시점에 의존하므로
 * (무료 타임 0%) 나중에 재생해 계산하면 값이 달라진다.
 */
export type Execution = {
  executionId: number;
  /** 예약에서 체결됐으면 원 주문 ID, 시장가면 null. */
  orderId?: number | null;
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  /** 그 틱의 종가. 예약이라도 지정가가 아니다 (`ord.md` 6.4.2). */
  executedPrice: string;
  currency: "KRW" | "USD";
  /** `executedPrice × quantity`. 수수료를 포함하지 않는다. */
  amount: string;
  /** 적용 요율(%). **매수는 언제나 0.** */
  appliedFeeRate: number;
  fee: string;
  /** **매도만.** 매수는 `null`. */
  realizedPnl: string | null;
  gameDate: string;
  tickSeq: number;
  executedAt: string;
};

/** 페이지 응답 (`_common.md §5`). */
export type ExecutionPage = {
  content: Execution[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

/**
 * 주문 생성 결과 (`ord.md`). **둘 중 하나만 채워진다** — 시장가는 미체결 상태를 거치지
 * 않으므로 `execution` 만, 예약은 등록 시점에 체결을 판정하지 않으므로 `order` 만 온다.
 * 어느 쪽인지는 `orderType` 이 결정론적으로 말한다.
 */
export type OrderCreated = {
  orderType: "MARKET" | "LIMIT";
  execution: Execution | null;
  order: OpenOrder | null;
};

/** `POST /api/v1/orders` 의 본문. 수량은 **1주 단위 정수**다 (기획 6.5.2). */
export type OrderRequest = {
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  /** `LIMIT` 일 때만 쓰인다. 시장가면 서버가 무시한다. */
  limitPrice?: number | null;
};

/**
 * 주문 등록. 시장가와 예약이 **한 경로**다 — 잔고 검증·수수료 계산이 둘에 같이 적용되므로
 * 경로를 나누면 그 로직이 두 곳에 복제된다 (`ord.md`).
 *
 * 부족분 매수를 여기서 막지 않는다. 그 판단은 미리보기를 쥔 화면이 이미 했다.
 *
 * @throws 실패 응답의 문구를 담은 Error. 잔고 부족·거래 시간 외는 409 로 온다
 */
export async function placeOrder(request: OrderRequest): Promise<OrderCreated> {
  const response = await apiFetch("/api/v1/orders", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      orderType: request.orderType,
      side: request.side,
      instrumentId: request.instrumentId,
      // 시장가에 지정가를 실어 보내지 않는다. 서버가 무시하지만, 보내면 요청만 보고는
      // 어느 유형인지 읽을 수 없어 로그를 되짚을 때 오해가 생긴다.
      ...(request.orderType === "LIMIT" && request.limitPrice != null
        ? { limitPrice: request.limitPrice }
        : {}),
      quantity: request.quantity,
    }),
  });

  if (!response.ok) {
    throw new Error(await apiErrorMessage(response, "주문에 실패했습니다."));
  }
  return ((await response.json()) as { data: OrderCreated }).data;
}

/** 미체결 예약 목록. 폐장 뒤에는 계약이 **항상 빈 목록**을 준다 (`ord.md` · 기획 6.2.4). */
export const fetchOpenOrders = () =>
  readPersonalJson<OpenOrders>("/api/v1/orders");

/**
 * 체결 내역. 최신순 고정이다 (`ord.md`).
 *
 * @param page 0부터 시작한다
 * @param size 페이지당 항목 수. 서버가 100 으로 자른다
 * @param seasonDay 일차 필터. 오늘의 집계처럼 하루만 셀 때 쓴다. 비우면 전체다
 */
export const fetchExecutions = (page = 0, size = 20, seasonDay?: number) =>
  readPersonalJson<ExecutionPage>(
    `/api/v1/executions?page=${page}&size=${size}` +
      (seasonDay == null ? "" : `&seasonDay=${seasonDay}`)
  );

/**
 * 예약 정정. **비운 쪽은 지금 값을 그대로 쓴다** — 수량만 고치려는 사람이 지정가를 다시
 * 보내야 한다면 그 사이에 값을 잘못 옮겨 적는 순간 의도하지 않은 조건이 된다 (`ord.md`).
 *
 * 방향과 종목은 바꿀 수 없다. 그것을 바꾸는 것은 다른 예약이지 같은 예약의 정정이 아니다.
 *
 * @throws 실패 응답의 문구를 담은 Error. 이미 체결·소멸한 예약은 409 로 온다
 */
export async function amendOrder(
  orderId: number,
  change: { quantity?: number; limitPrice?: number }
): Promise<OpenOrder> {
  const response = await apiFetch(`/api/v1/orders/${orderId}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(change),
  });

  if (!response.ok) {
    throw new Error(await apiErrorMessage(response, "정정에 실패했습니다."));
  }
  return ((await response.json()) as { data: OpenOrder }).data;
}

/**
 * 예약 취소. 잠긴 자금·수량이 반환된다 (`ord.md`).
 *
 * @throws 실패 응답의 문구를 담은 Error. 이미 체결된 예약은 409 로 온다
 */
export async function cancelOrder(orderId: number): Promise<void> {
  const response = await apiFetch(`/api/v1/orders/${orderId}`, {
    method: "DELETE",
  });

  if (!response.ok) {
    throw new Error(await apiErrorMessage(response, "취소에 실패했습니다."));
  }
}
