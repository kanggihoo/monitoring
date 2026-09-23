import {
  type APIRequestContext,
  type APIResponse,
  expect,
} from "@playwright/test";

import {
  cashScale,
  type Currency,
  type ExchangeDirection,
  gte,
  scaled,
} from "./money";

/**
 * 실서버 REST 를 그대로 두드리는 얇은 겹 (`docs/qa/user-checklist.md`).
 *
 * **화면을 거치지 않는 길을 따로 둔다.** 체크리스트의 판정 대부분이 "잠금액이 정확히
 * 돌아왔는가" 처럼 **금액의 정합성**이고, 그것을 DOM 에서 읽으면 표시 형식이 바뀔 때마다
 * 판정이 함께 흔들린다. 화면으로 확인해야 하는 것(버튼이 반응하는가, 안내가 뜨는가)만
 * 화면으로 본다.
 */

/** 쓰기 요청에 필요한 위조 방지 헤더. 없으면 백엔드가 403 으로 끊는다. */
const CSRF_HEADER = "X-XSRF-TOKEN";
const CSRF_COOKIE = "XSRF-TOKEN";

export type Clock = {
  phase: "BRIEFING" | "TRADING" | "SETTLING" | "REVIEW_ONLY";
  seasonDay: number;
  tickSeq: number | null;
  gameDate: string | null;
  nextTickAt: string | null;
  feeFree: boolean;
  leaderboardBlackout: boolean;
};

export type Account = {
  usdKrwRate: string;
  totalValuationKrw: string;
  krwCash: string;
  usdCash: string;
  lockedKrw: string;
  lockedUsd: string;
  holdingsValuationKrw: string;
  startingBalanceKrw: string;
};

export type Instrument = {
  instrumentId: number;
  name: string;
  category: "NASDAQ" | "KRX" | "CRYPTO";
  currency: Currency;
  priceScale: number;
  listingStatus: string;
  watched: boolean;
};

export type Position = {
  instrumentId: number;
  currency: Currency;
  quantity: number;
  lockedQuantity: number;
  avgPrice: string;
  currentPrice: string;
  valuation: string;
};

export type OrderPreview = {
  currency: Currency;
  referencePrice: string;
  estimatedAmount: string;
  appliedFeeRate: number;
  estimatedFee: string;
  totalAmount: string;
  availableCash: string;
  shortfall: string;
  shortfallExchange: {
    direction: ExchangeDirection;
    sendAmount: string;
  } | null;
  feeFree: boolean;
};

export type Execution = {
  executionId: number;
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  executedPrice: string;
  currency: Currency;
  amount: string;
  appliedFeeRate: number;
  fee: string;
  realizedPnl: string | null;
  tickSeq: number;
};

export type OpenOrder = {
  orderId: number;
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  limitPrice: string;
  currency: Currency;
  lockedAmount: string | null;
  lockedQuantity: number | null;
};

/** `POST /api/v1/orders` 의 본문. 시장가면 `limitPrice` 가 없다. */
export type OrderBody = {
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  instrumentId: number;
  quantity: number;
  limitPrice?: string;
};

export type ExchangeQuote = {
  direction: ExchangeDirection;
  rate: string;
  sendAmount: string;
  feeRate: number;
  fee: string;
  receiveAmount: string;
  availableCash: string;
};

/** `ApiResponse` 봉투를 벗긴다. 실패 응답의 본문을 메시지에 실어 둔다 — 상태코드만으로는 못 고친다. */
export async function unwrap<T>(
  response: APIResponse,
  what: string
): Promise<T> {
  if (!response.ok()) {
    throw new Error(
      `${what} 실패: ${response.status()} ${await response.text()}`
    );
  }
  return (await response.json()).data as T;
}

/**
 * 쓰기 요청. **매번 쿠키에서 토큰을 읽는다** — 한 번 읽어 들고 있으면 서버가 토큰을 돌린
 * 뒤부터 조용히 403 이 된다.
 */
async function write(
  request: APIRequestContext,
  method: "post" | "put" | "patch" | "delete",
  path: string,
  data?: unknown
): Promise<APIResponse> {
  const { cookies } = await request.storageState();
  const token = cookies.find((cookie) => cookie.name === CSRF_COOKIE)?.value;
  if (!token) {
    throw new Error(
      `${CSRF_COOKIE} 쿠키가 없습니다. 조회를 한 번 거친 세션이어야 합니다 (global-setup).`
    );
  }
  return request[method](path, {
    headers: { [CSRF_HEADER]: token },
    ...(data === undefined ? {} : { data }),
  });
}

export const api = (request: APIRequestContext) => ({
  clock: async () =>
    unwrap<Clock>(await request.get("/api/v1/game/clock"), "시계 조회"),

  me: async () =>
    unwrap<{ id: number; nickname: string; role: string }>(
      await request.get("/api/v1/users/me"),
      "내 정보 조회"
    ),

  account: async () =>
    unwrap<Account>(await request.get("/api/v1/accounts/me"), "계좌 조회"),

  positions: async () =>
    (
      await unwrap<{ positions: Position[] }>(
        await request.get("/api/v1/accounts/me/positions"),
        "보유 조회"
      )
    ).positions,

  instruments: async () =>
    (
      await unwrap<{ instruments: Instrument[] }>(
        await request.get("/api/v1/market/instruments"),
        "종목 조회"
      )
    ).instruments,

  openOrders: async () =>
    (
      await unwrap<{ orders: OpenOrder[] }>(
        await request.get("/api/v1/orders"),
        "예약 조회"
      )
    ).orders,

  executions: async (
    params: { page?: number; size?: number; seasonDay?: number } = {}
  ) => {
    const query = new URLSearchParams({
      page: String(params.page ?? 0),
      size: String(params.size ?? 20),
      ...(params.seasonDay === undefined
        ? {}
        : { seasonDay: String(params.seasonDay) }),
    });
    return unwrap<{
      content: Execution[];
      page: number;
      size: number;
      totalElements: number;
      totalPages: number;
      hasNext: boolean;
    }>(await request.get(`/api/v1/executions?${query}`), "체결 조회");
  },

  previewOrder: async (params: {
    orderType: "MARKET" | "LIMIT";
    side: "BUY" | "SELL";
    instrumentId: number;
    quantity: number;
    limitPrice?: string;
  }) => {
    const query = new URLSearchParams({
      orderType: params.orderType,
      side: params.side,
      instrumentId: String(params.instrumentId),
      quantity: String(params.quantity),
      ...(params.limitPrice === undefined
        ? {}
        : { limitPrice: params.limitPrice }),
    });
    return unwrap<OrderPreview>(
      await request.get(`/api/v1/orders/preview?${query}`),
      "주문 미리보기"
    );
  },

  /** 원본 응답. 휴장(`409`)을 거르며 종목을 훑는 쪽이 쓴다. */
  previewOrderRaw: (params: {
    orderType: "MARKET" | "LIMIT";
    side: "BUY" | "SELL";
    instrumentId: number;
    quantity: number;
  }) =>
    request.get(
      `/api/v1/orders/preview?${new URLSearchParams({
        orderType: params.orderType,
        side: params.side,
        instrumentId: String(params.instrumentId),
        quantity: String(params.quantity),
      })}`
    ),

  /** 원본 응답을 그대로 돌린다 — 거부를 기대하는 케이스가 상태코드와 본문을 본다. */
  placeOrderRaw: (body: OrderBody) =>
    write(request, "post", "/api/v1/orders", body),

  placeOrder: async (body: OrderBody) =>
    unwrap<{
      orderType: "MARKET" | "LIMIT";
      execution: Execution | null;
      order: OpenOrder | null;
    }>(await write(request, "post", "/api/v1/orders", body), "주문"),

  amendOrderRaw: (
    orderId: number,
    body: { quantity?: number; limitPrice?: string }
  ) => write(request, "patch", `/api/v1/orders/${orderId}`, body),

  cancelOrderRaw: (orderId: number) =>
    write(request, "delete", `/api/v1/orders/${orderId}`),

  previewExchange: async (direction: ExchangeDirection, amount: string) =>
    unwrap<ExchangeQuote>(
      await request.get(
        `/api/v1/accounts/me/exchanges/preview?direction=${direction}&amount=${amount}`
      ),
      "환전 미리보기"
    ),

  exchangeRaw: (direction: ExchangeDirection, amount: string) =>
    write(request, "post", "/api/v1/accounts/me/exchanges", {
      direction,
      amount,
    }),

  exchange: async (direction: ExchangeDirection, amount: string) =>
    unwrap<ExchangeQuote & { exchangeId: number }>(
      await write(request, "post", "/api/v1/accounts/me/exchanges", {
        direction,
        amount,
      }),
      "환전"
    ),

  exchangeHistory: async (size = 20) =>
    unwrap<{ content: { exchangeId: number }[]; totalElements: number }>(
      await request.get(`/api/v1/accounts/me/exchanges?page=0&size=${size}`),
      "환전 이력"
    ),

  watchlistAddRaw: (instrumentId: number) =>
    write(request, "put", `/api/v1/users/me/watchlist/${instrumentId}`),

  watchlistRemoveRaw: (instrumentId: number) =>
    write(request, "delete", `/api/v1/users/me/watchlist/${instrumentId}`),

  // 커서 페이지네이션이라 `items` 다. 첫 항목이 가장 최근이다 (`soc.md`).
  chatMessages: async (size = 50) =>
    (
      await unwrap<{ items: { messageId: number; content: string | null }[] }>(
        await request.get(`/api/v1/chat/messages?size=${size}`),
        "채팅 조회"
      )
    ).items,

  sendChatRaw: (content: string) =>
    write(request, "post", "/api/v1/chat/messages", { content }),

  ipoOfferings: async () =>
    (
      await unwrap<{ offerings: IpoOffering[] }>(
        await request.get("/api/v1/ipo/offerings"),
        "공모 조회"
      )
    ).offerings,

  subscribeIpoRaw: (offeringId: number) =>
    write(request, "post", `/api/v1/ipo/offerings/${offeringId}/subscription`),

  cancelIpoRaw: (offeringId: number) =>
    write(
      request,
      "delete",
      `/api/v1/ipo/offerings/${offeringId}/subscription`
    ),

  inquiries: async () =>
    unwrap<{
      content: { id: number; title: string; status: string }[];
      totalElements: number;
    }>(await request.get("/api/v1/inquiries?page=0&size=50"), "문의 목록"),

  createInquiry: async (title: string, content: string) =>
    unwrap<{ id: number; title: string; status: string }>(
      await write(request, "post", "/api/v1/inquiries", { title, content }),
      "문의 작성"
    ),

  deleteInquiryRaw: (id: number) =>
    write(request, "delete", `/api/v1/inquiries/${id}`),

  getInquiryRaw: (id: number) => request.get(`/api/v1/inquiries/${id}`),

  myRanking: async () =>
    unwrap<{
      seasonRank: number | null;
      totalParticipants: number;
      blackout: boolean;
    }>(await request.get("/api/v1/rankings/me"), "내 순위"),

  /** 일차 복기. `available === false` 인 일차는 아직 볼 것이 없다. */
  dailyReports: async () =>
    (
      await unwrap<{
        items: {
          seasonDay: number;
          available: boolean;
          /** 확정 전 일차는 `null` 이다. */
          dailyReturn: number | null;
        }[];
      }>(await request.get("/api/v1/reports/daily"), "복기 목록")
    ).items,

  /** 성향 설문. 답한 적이 없으면 `answers` 가 `null` 이다 (`S15P21A605-215`). */
  selfProfile: async () =>
    unwrap<{ answers: string | null }>(
      await request.get("/api/v1/onboarding/self-profile"),
      "성향 설문 조회"
    ),

  /** 덮어쓰기다 — 최초 저장과 수정을 가르지 않는다. **삭제는 계약에 없다.** */
  saveSelfProfileRaw: (answers: string) =>
    write(request, "put", "/api/v1/onboarding/self-profile", { answers }),

  /** 오늘의 브리핑. 공모 일정(`ipoWindows`)이 여기서 온다 (`S15P21A605-281`). */
  briefing: async () =>
    unwrap<{
      ipoWindows: { disclosureAt: string; subscriptionClosesAt: string }[];
    }>(await request.get("/api/v1/seasons/current/briefing"), "브리핑 조회"),

  /** 리더보드 한 페이지. 트랙마다 다른 기준의 순위다 (`rank.md`). */
  leaderboard: async (track: "DAILY" | "POINT" | "VALUATION", size = 20) =>
    unwrap<{
      content: {
        rank: number;
        nickname: string;
        points: number | null;
        isMe: boolean;
      }[];
      totalElements: number;
    }>(
      await request.get(`/api/v1/rankings?track=${track}&page=0&size=${size}`),
      "리더보드 조회"
    ),

  /** 알림 목록. 최신순 고정이고 `unreadCount` 가 봉투에 함께 온다 (`noti.md`). */
  notifications: async (size = 50) =>
    unwrap<{
      unreadCount: number;
      content: {
        notificationId: number;
        kind: string;
        title: string;
        body: string;
        referenceId: number | null;
      }[];
      totalElements: number;
    }>(
      await request.get(`/api/v1/notifications?page=0&size=${size}`),
      "알림 조회"
    ),

  /**
   * 한 일차의 복기 리포트. **`available === false` 인 일차는 409 다** — 호출부가 먼저
   * `dailyReports()` 로 거를 것을 전제한다.
   */
  dailyReport: async (seasonDay: number) =>
    unwrap<DailyReport>(
      await request.get(`/api/v1/reports/daily/${seasonDay}`),
      `${seasonDay}일차 리포트`
    ),

  /** 시즌 리포트. **확정 전에는 409 라** 호출부가 그 상태를 먼저 가른다. */
  seasonReportRaw: () => request.get("/api/v1/reports/season"),

  adminRaw: (path: string) => request.get(`/api/v1/admin/${path}`),

  /**
   * 관리자 전용 쓰기 경로를 **일반 세션으로** 두드린다. 권한 경계 케이스가 쓴다.
   *
   * 위조 방지 토큰을 제대로 실어 보낸다 — 아무 값이나 넣으면 권한이 아니라 토큰 때문에
   * 막혀서, 통과해도 권한 경계를 증명하지 못한다.
   */
  adminPostRaw: (path: string) =>
    write(request, "post", `/api/v1/admin/${path}`),
});

/** 그 일차를 들고 끝낸 종목 한 줄 (`DailyReportResponse.ClosingHolding`). */
export type ClosingHolding = {
  instrumentId: number;
  currency: Currency;
  priceScale: number;
  quantity: number;
  totalCost: string;
  avgPrice: string;
  valuationKrw: string;
};

/**
 * 일차를 끝낸 자산의 확정 스냅샷. 스냅샷이 없는 옛 일차는 `null` 이고 화면이 버튼째 지운다.
 */
export type Closing = {
  totalValuationKrw: string;
  krwCash: string;
  usdCash: string;
  usdKrw: string;
  holdingsValuationKrw: string;
  asOfGameDate: string;
  dailyRank: number;
  participantCount: number;
  maxDrawdown: number | null;
  /** 마지막 일차는 `false` 다 — 그 뒤에 개장이 없다. */
  resetsNextDay: boolean;
};

/** 복기 리포트에서 이 스위트가 보는 부분만. 나머지 필드는 계약이 넓어 여기서 좁힌다. */
export type DailyReport = {
  seasonDay: number;
  summary: { dailyReturn: number; executionCount: number };
  closing: Closing | null;
  closingHoldings: ClosingHolding[];
};

export type IpoOffering = {
  offeringId: number;
  instrumentId: number;
  name: string;
  status: string;
  currency: Currency;
  offerPrice: string;
  depositAmount: string;
  mySubscription: { subscriptionId: number } | null;
};

export type Api = ReturnType<typeof api>;

/** 주문 가능 현금. `krwCash` 는 잠긴 몫을 포함하므로 그대로 쓰면 안 된다 (`acc.md`). */
export const availableKrw = (account: Account) =>
  (scaled(account.krwCash, 0) - scaled(account.lockedKrw, 0)).toString();

export const availableUsd = (account: Account) => {
  const value = scaled(account.usdCash, 2) - scaled(account.lockedUsd, 2);
  return `${value / BigInt(100)}.${String(value % BigInt(100)).padStart(2, "0")}`;
};

/**
 * 거래에 쓸 원화 종목을 고른다. **가장 싼 것을 고른다** — 케이스가 10주를 사고도 다음
 * 케이스의 전제를 남길 수 있어야 한다. 원화 종목이라 환전 없이 바로 살 수 있다.
 */
export async function cheapestKrwInstrument(client: Api, quantity = 10) {
  const instruments = (await client.instruments()).filter(
    (item) => item.currency === "KRW" && item.listingStatus === "LISTED"
  );
  expect(instruments.length, "거래 가능한 원화 종목").toBeGreaterThan(0);

  let best: { instrument: Instrument; preview: OrderPreview } | null = null;
  for (const instrument of instruments) {
    // **휴장 종목을 건너뛴다.** 그날 가격이 없는 종목은 미리보기가 `409` 이고
    // (`휴장 중인 종목은 시장가로 주문할 수 없습니다`), 그것은 환경 상태이지 실패가
    // 아니다 — 봉투를 벗기는 `previewOrder` 로 훑으면 휴장 종목 하나가 케이스를 통째로
    // 죽인다 (실측: 2일차에 `U-RSV-01`~`04` 가 그래서 넷 다 깨졌다).
    const response = await client.previewOrderRaw({
      orderType: "MARKET",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity,
    });
    if (!response.ok()) continue;
    const preview = (await response.json()).data as OrderPreview;
    if (
      !best ||
      scaled(preview.totalAmount, 0) < scaled(best.preview.totalAmount, 0)
    ) {
      best = { instrument, preview };
    }
  }
  // 전부 휴장이면 고를 것이 없다. **부르는 쪽이 `test.skip` 한다** (`requireKrwInstrument`).
  return best;
}

/**
 * 원화 종목 하나. **미리보기를 부르지 않는다** — `GET /orders/preview` 는 거래 구간 밖에서
 * `409` 라, 바로 그 구간을 확인하는 케이스는 가격을 물어볼 수 없다.
 */
export async function anyKrwInstrument(client: Api) {
  const instrument = (await client.instruments()).find(
    (item) => item.currency === "KRW" && item.listingStatus === "LISTED"
  );
  expect(instrument, "거래 가능한 원화 종목").toBeTruthy();
  return instrument!;
}

/** 미국(달러) 종목 하나. 환전이 끼는 케이스가 쓴다. */
export async function anyUsdInstrument(client: Api) {
  const instrument = (await client.instruments()).find(
    (item) =>
      item.currency === "USD" &&
      item.listingStatus === "LISTED" &&
      item.priceScale === 2
  );
  expect(instrument, "거래 가능한 달러 종목").toBeTruthy();
  return instrument!;
}

/**
 * 보유 수량을 만든다. **앞 케이스가 만들어 줬다고 가정하지 않는다** (체크리스트 §각 케이스의
 * 책임). 이미 충분하면 아무것도 하지 않는다.
 *
 * @returns 준비된 종목. 현금이 모자라 만들지 못하면 `null` — 부르는 쪽이 `test.skip` 한다.
 */
export async function ensureHolding(client: Api, quantity: number) {
  const positions = await client.positions();
  const instruments = await client.instruments();
  const krw = (instrumentId: number) =>
    instruments.find(
      (item) => item.instrumentId === instrumentId && item.currency === "KRW"
    );

  for (const position of positions) {
    if (position.quantity - position.lockedQuantity < quantity) continue;
    const meta = krw(position.instrumentId);
    if (!meta) continue;
    // **가진 것만으로는 부족하다 — 오늘 팔 수 있어야 한다.** 그날 휴장인 종목은 시장가
    // 미리보기가 `409` 라, 보유만 보고 돌려주면 부르는 쪽이 그 자리에서 죽는다
    // (실측: 3일차에 `U-SEL-01`·`03`·`04` 가 그래서 깨졌다). 팔 수 있는지는 서버에 묻는다.
    const sellable = await client.previewOrderRaw({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: position.instrumentId,
      quantity,
    });
    if (sellable.ok()) return meta;
  }

  const pick = await cheapestKrwInstrument(client, quantity);
  if (pick === null) return null;
  const { instrument, preview } = pick;

  const account = await client.account();
  if (!gte(availableKrw(account), preview.totalAmount, 0)) return null;

  await client.placeOrder({
    orderType: "MARKET",
    side: "BUY",
    instrumentId: instrument.instrumentId,
    quantity,
  });
  return instrument;
}

/** 이 스위트가 남긴 미체결 예약을 치운다. 남기면 현금이 잠긴 채 다음 실행이 시작된다. */
export async function cancelOpenOrders(client: Api, orderIds: number[]) {
  const open = new Set(
    (await client.openOrders()).map((order) => order.orderId)
  );
  for (const orderId of orderIds) {
    if (open.has(orderId)) await client.cancelOrderRaw(orderId);
  }
}

/**
 * 원화 현금을 되살린다. **전액을 쓰는 케이스 전용이다** — 그 계정은 지난 실행이 현금을
 * 비워 둔 채로 끝나므로, 준비 단계가 보유를 되팔아 스스로 전제를 만든다.
 */
export async function liquidateKrwPositions(client: Api) {
  const instruments = await client.instruments();
  for (const position of await client.positions()) {
    const sellable = position.quantity - position.lockedQuantity;
    const meta = instruments.find(
      (item) => item.instrumentId === position.instrumentId
    );
    if (sellable <= 0 || meta?.currency !== "KRW") continue;
    await client.placeOrderRaw({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: position.instrumentId,
      quantity: sellable,
    });
  }
}

/** 통화 자릿수를 곁들여 금액을 비교한다. 실패 메시지에 무엇을 비교했는지 남긴다. */
export const expectAmount = (
  actual: string,
  expected: string,
  currency: Currency,
  what: string
) =>
  expect(scaled(actual, cashScale(currency)), what).toBe(
    scaled(expected, cashScale(currency))
  );
