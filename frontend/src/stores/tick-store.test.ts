import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type {
  ClosesResponse,
  PriceSnapshot,
  Valuation,
} from "@/lib/market-types";
import type { Envelope } from "@/lib/ws";
import { dispatch } from "@/lib/ws-dispatch";
import { createChatStore } from "@/stores/chat-store";
import { createSessionStore } from "@/stores/session-store";
import { type AccountSnapshot, createTickStore } from "@/stores/tick-store";

// ── 픽스처 ──────────────────────────────────────────────────────────────────
// 종목 3 은 매일 열고, 종목 13 은 tick 402 의 거래일에 휴장이다.

function snapshot(
  tickSeq: number,
  closeOf3 = `${100 + tickSeq}.00`
): PriceSnapshot {
  return {
    asOfGameDate: dateOf(tickSeq),
    asOfTickSeq: tickSeq,
    usdKrwRate: "1200.00",
    prices: [
      {
        instrumentId: 3,
        close: closeOf3,
        closeKrw: "120000",
        prevClose: "100.00",
        changeRate: 1,
        marketClosed: false,
        tradedAmountKrw: "7000000",
      },
      {
        instrumentId: 13,
        close: "50000",
        closeKrw: "50000",
        prevClose: "50000",
        changeRate: tickSeq === 402 ? null : 0,
        marketClosed: tickSeq === 402,
        tradedAmountKrw: "0",
      },
    ],
  };
}

/** Tick 번호를 그날의 거래일로. 정렬만 맞으면 된다. */
const dateOf = (tickSeq: number) =>
  `2021-01-${String(tickSeq % 100).padStart(2, "0")}`;

function prices(tickSeq: number): Envelope {
  return {
    type: "market.prices",
    tickSeq,
    gameDate: dateOf(tickSeq),
    payload: snapshot(tickSeq),
  };
}

function valuation(tickSeq: number, totalValuationKrw: string): Envelope {
  return {
    type: "account.valuation",
    tickSeq,
    gameDate: dateOf(tickSeq),
    payload: { asOfTickSeq: tickSeq, totalValuationKrw },
  };
}

function heatmap(tickSeq: number): Envelope {
  return {
    type: "social.heatmap",
    tickSeq,
    gameDate: dateOf(tickSeq),
    payload: { asOfTickSeq: tickSeq, buckets: [] },
  };
}

function closes(
  instrumentId: number,
  upToTickSeq: number,
  from = 398
): ClosesResponse {
  const points = [];
  for (let tickSeq = from; tickSeq <= upToTickSeq; tickSeq++) {
    if (instrumentId === 13 && tickSeq === 402) continue; // 휴장일은 아예 없다
    points.push({ gameDate: dateOf(tickSeq), close: `${100 + tickSeq}.00` });
  }
  return {
    instrumentId,
    currency: "USD",
    priceScale: 2,
    asOfGameDate: dateOf(upToTickSeq),
    asOfTickSeq: upToTickSeq,
    points,
  };
}

/** 확정은 다음 Tick 도착으로만 — 조립 버퍼를 관찰하려고 정착 타이머를 끈다. */
const hydrated = (upTo = 400) => {
  const store = createTickStore({ settleMs: null });
  store.getState().hydrate(snapshot(upTo), closes(3, upTo));
  return store;
};

describe("틱 조각 묶기와 확정", () => {
  it("같은 Tick 의 조각 둘을 순서를 바꿔 넣어도 확정 전까지 화면 상태가 바뀌지 않는다", () => {
    const store = hydrated();
    const before = store.getState();

    store.getState().receive(valuation(401, "101000000"));
    store.getState().receive(prices(401));

    const after = store.getState();
    expect(after.tickSeq).toBe(400);
    expect(after.prices).toBe(before.prices);
    expect(after.valuation).toBeNull();
    expect(after.pendingTickSeq).toBe(401);
  });

  it("다음 Tick 번호가 도착하면 이전 Tick 이 통째로 확정되고 버퍼가 빈다", () => {
    const store = hydrated();
    store.getState().receive(valuation(401, "101000000"));
    store.getState().receive(prices(401));

    store.getState().receive(prices(402));

    const state = store.getState();
    // 가격과 평가액이 같은 순간에 같은 Tick 으로 바뀐다.
    expect(state.tickSeq).toBe(401);
    expect(state.prices[3]?.close).toBe("501.00");
    expect(state.valuation?.totalValuationKrw).toBe("101000000");
    expect(state.pendingTickSeq).toBe(402);
  });

  it("조각이 하나뿐인 Tick 과 셋인 Tick 이 섞여도 동작한다", () => {
    const store = hydrated();
    store.getState().receive(prices(401)); // 하나
    store.getState().receive(prices(402)); // 셋
    store.getState().receive(heatmap(402));
    store.getState().receive(valuation(402, "99000000"));
    store.getState().receive(prices(403)); // 402 확정

    const state = store.getState();
    expect(state.tickSeq).toBe(402);
    expect(state.heatmap?.asOfTickSeq).toBe(402);
    expect(state.valuation?.totalValuationKrw).toBe("99000000");
    expect(state.needsResync).toBe(false);
  });

  it("조각이 잠깐 멈추면 다음 Tick 을 기다리지 않고 확정한다 (정착 타이머)", () => {
    vi.useFakeTimers();
    const store = createTickStore({ settleMs: 300 });
    store.getState().hydrate(snapshot(400));

    store.getState().receive(prices(401));
    store.getState().receive(valuation(401, "1"));
    expect(store.getState().tickSeq).toBe(400);

    vi.advanceTimersByTime(300);
    expect(store.getState().tickSeq).toBe(401);
    expect(store.getState().pendingTickSeq).toBeNull();

    // 늦게 온 같은 번호의 조각은 중복이라 버려진다.
    store.getState().receive(valuation(401, "2"));
    vi.advanceTimersByTime(300);
    expect(store.getState().valuation?.totalValuationKrw).toBe("1");
    vi.useRealTimers();
  });

  it("뉴스는 100건, 체결 알림은 50건을 넘지 않는다", () => {
    const store = hydrated();
    for (let tickSeq = 401; tickSeq <= 460; tickSeq++) {
      store.getState().receive({
        type: "news.published",
        tickSeq,
        gameDate: dateOf(tickSeq),
        payload: [{ newsId: tickSeq * 2 }, { newsId: tickSeq * 2 + 1 }],
      });
      store.getState().receive({
        type: "order.filled",
        tickSeq,
        gameDate: dateOf(tickSeq),
        payload: { orderId: tickSeq },
      });
    }
    store.getState().receive(prices(461));

    expect(store.getState().news).toHaveLength(100);
    expect(store.getState().fills).toHaveLength(50);
    // 최신이 앞이다.
    expect(store.getState().news[0]?.newsId).toBe(920);
  });
});

describe("히스토리 이어붙이기", () => {
  it("기준 Tick 보다 큰 것만 이어붙고 같거나 작은 것은 버려진다", () => {
    const store = createTickStore({ settleMs: null });

    // 구독 뒤, 히스토리 전 — 쌓기만 한다. 화면은 비어 있다.
    store.getState().receive(prices(399));
    store.getState().receive(prices(400));
    store.getState().receive(prices(401));
    expect(store.getState().tickSeq).toBe(0);

    store.getState().hydrate(snapshot(400), closes(3, 400));
    store.getState().receive(prices(402)); // 401 확정

    const points = store.getState().series?.points ?? [];
    expect(points.map((point) => point.gameDate)).toEqual(
      [398, 399, 400, 401].map(dateOf)
    );
    // 399·400 은 히스토리에 이미 있다 — 중복 점이 없다.
    expect(new Set(points.map((point) => point.gameDate)).size).toBe(
      points.length
    );
  });

  it("구독 전에 흐른 Tick 이 사라지지 않는다 — 히스토리를 먼저 부르면 실패한다", () => {
    // 옳은 순서: 구독 → 버퍼링 → 히스토리(asOf 400) → 그 사이 온 401 이어붙기
    const right = createTickStore({ settleMs: null });
    right.getState().receive(prices(401));
    right.getState().hydrate(snapshot(400), closes(3, 400));
    right.getState().receive(prices(402));
    expect(right.getState().series?.points.at(-1)?.gameDate).toBe(dateOf(401));

    // 틀린 순서: 히스토리(asOf 400) 를 받아 둔 채 구독이 늦어 401 을 못 받았다.
    const wrong = createTickStore({ settleMs: null });
    wrong.getState().hydrate(snapshot(400), closes(3, 400));
    wrong.getState().receive(prices(402));
    wrong.getState().receive(prices(403)); // 402 확정 — 401 은 어디에도 없다
    expect(wrong.getState().series?.points.at(-1)?.gameDate).toBe(dateOf(402));
    expect(wrong.getState().needsResync).toBe(true);
  });

  it("휴장일은 차트에 포인트를 추가하지 않는다", () => {
    const store = createTickStore({ settleMs: null });
    store.getState().hydrate(snapshot(400), closes(13, 400));
    store.getState().receive(prices(401));
    store.getState().receive(prices(402)); // 13 은 이날 휴장
    store.getState().receive(prices(403));

    const dates = store
      .getState()
      .series?.points.map((point) => point.gameDate);
    expect(dates).toContain(dateOf(401));
    expect(dates).not.toContain(dateOf(402));
  });

  it("차트 종목을 바꾸는 사이 지난 Tick 의 종가를 현재가로 채운다", () => {
    const store = hydrated(400);
    store.getState().receive(prices(401));
    store.getState().receive(prices(402)); // 401 확정

    // 응답은 400 기준인데 화면은 401 이다.
    store.getState().setSeries(closes(3, 400));
    expect(store.getState().series?.points.at(-1)).toEqual({
      gameDate: dateOf(401),
      close: "501.00",
    });
  });
});

describe("구멍 감지와 회복", () => {
  it("Tick 번호가 1씩 늘지 않으면 재조회 신호가 나고, 재조회 응답을 넣으면 메워지고 신호가 멈춘다", () => {
    const store = hydrated(400);
    store.getState().receive(prices(401));
    store.getState().receive(prices(403)); // 402 유실 → 401 확정
    store.getState().receive(prices(404)); // 403 확정 → 구멍

    expect(store.getState().tickSeq).toBe(403);
    expect(store.getState().needsResync).toBe(true);
    expect(
      store.getState().series?.points.map((point) => point.gameDate)
    ).not.toContain(dateOf(402));

    // 재조회. 조회 응답은 유실과 무관하게 402 를 담고 있다 (브로드캐스트만 빠졌다).
    store.getState().hydrate(snapshot(404), closes(3, 404));

    const state = store.getState();
    expect(state.needsResync).toBe(false);
    expect(state.tickSeq).toBe(404);
    expect(state.series?.points.map((point) => point.gameDate)).toContain(
      dateOf(402)
    );

    // 회복 뒤 정상 흐름에서는 신호가 다시 나지 않는다 — 무한 재조회가 아니다.
    store.getState().receive(prices(405));
    store.getState().receive(prices(406));
    expect(store.getState().needsResync).toBe(false);
    expect(store.getState().tickSeq).toBe(405);
  });

  // 1일차 브리핑·되감기 직후의 조회는 `asOfTickSeq: null` 이다. 기준을 못 세운 채 굳으면
  // 개장 뒤 방송이 전부 버퍼로만 들어가 새로고침 전까지 화면이 멎는다 — 그것이 이 검사다.
  describe("최초 공개 전 하이드레이션", () => {
    /** 조회가 "아직 공개된 틱이 없다" 고 답한 응답 (`MarketPricesResponse.beforeFirstPublish`). */
    const beforeFirstPublish: PriceSnapshot = {
      asOfGameDate: null,
      asOfTickSeq: null,
      usdKrwRate: null,
      prices: [],
    };

    it("기준을 세우지 않고 개장을 기다린다 — 곧바로 재조회를 걸지는 않는다", () => {
      const store = createTickStore({ settleMs: null });
      store.getState().hydrate(beforeFirstPublish);

      const state = store.getState();
      expect(state.baseTickSeq).toBeNull();
      expect(state.awaitingFirstPublish).toBe(true);
      // 조회가 방금 "없다" 고 답했다. 바로 다시 물으면 같은 답을 받고 맴돈다.
      expect(state.needsResync).toBe(false);
    });

    it("개장 뒤 첫 봉투가 재조회를 걸고, 그 응답으로 화면이 선다", () => {
      const store = createTickStore({ settleMs: null });
      store.getState().hydrate(beforeFirstPublish);

      store.getState().receive(prices(1));
      expect(store.getState().needsResync).toBe(true);
      expect(store.getState().tickSeq).toBe(0); // 아직 기준이 없다 — 그리지 않는다

      // 재조회 (`MarketLiveProvider` 의 `needsResync` 구독이 부른다).
      store.getState().hydrate(snapshot(1), closes(3, 1, 1));

      const state = store.getState();
      expect(state.awaitingFirstPublish).toBe(false);
      expect(state.needsResync).toBe(false);
      expect(state.tickSeq).toBe(1);
      expect(state.prices[3]?.close).toBe("101.00");

      // 그 뒤로는 방송이 그대로 이어붙는다.
      store.getState().receive(prices(2));
      store.getState().receive(prices(3));
      expect(store.getState().tickSeq).toBe(2);
      expect(store.getState().needsResync).toBe(false);
    });

    it("기다리는 동안 쌓인 봉투를 버리지 않는다", () => {
      const store = createTickStore({ settleMs: null });
      store.getState().receive(prices(1)); // 히스토리 전 도착
      store.getState().hydrate(beforeFirstPublish);

      // 조회는 아직 없다고 하지만 봉투는 이미 왔다. 다음 봉투가 재조회를 건다.
      store.getState().receive(prices(2));
      expect(store.getState().needsResync).toBe(true);

      store.getState().hydrate(snapshot(1), closes(3, 1, 1));
      // 쌓아 둔 2 번이 기준 위에 그대로 얹힌다.
      store.getState().receive(prices(3));
      expect(store.getState().tickSeq).toBe(2);
    });
  });

  it("재연결하면 다시 버퍼링하고, 히스토리를 받으면 그 뒤 것만 붙는다", () => {
    const store = hydrated(400);
    store.getState().reset();

    store.getState().receive(prices(405)); // 새 연결 뒤 도착 — 쌓기만
    expect(store.getState().tickSeq).toBe(400);

    store.getState().hydrate(snapshot(404), closes(3, 404));
    store.getState().receive(prices(406));
    expect(store.getState().tickSeq).toBe(405);
    expect(store.getState().needsResync).toBe(false);
  });
});

describe("계좌 동기화 표시", () => {
  /** 이 검사가 보는 것은 값이 아니라 **이번 회차에 조회가 성공했는가**다. */
  const VALUATION = {
    asOfGameDate: dateOf(400),
    asOfTickSeq: 400,
    usdKrwRate: "1200.00",
    totalValuationKrw: "1000000",
    krwCash: "500000",
    usdCash: "100.00",
    lockedKrw: "0",
    lockedUsd: "0.00",
    holdingsValuationKrw: "500000",
    startingBalanceKrw: "1000000",
    cumulativeReturn: 0,
    dailyReturn: 0,
  } satisfies Valuation;
  const ACCOUNT: AccountSnapshot = { valuation: VALUATION };

  it("계좌 조회가 성공한 회차에만 선다", () => {
    const store = createTickStore({ settleMs: null });

    store.getState().hydrate(snapshot(400), closes(3, 400), ACCOUNT);

    expect(store.getState().accountSynced).toBe(true);
  });

  /**
   * **`valuation !== null` 로는 가려지지 않는 상태다.** 계좌 조회만 실패한 재조회는 옛 값을
   * 그대로 두므로 화면에는 숫자가 남는다 — 그 값으로 주문을 받으면 안 된다.
   */
  it("계좌 조회가 실패하면 옛 값은 남고 표시만 내려간다", () => {
    const store = createTickStore({ settleMs: null });
    store.getState().hydrate(snapshot(400), closes(3, 400), ACCOUNT);

    store.getState().hydrate(snapshot(401), closes(3, 401), {});

    expect(store.getState().valuation).not.toBeNull();
    expect(store.getState().accountSynced).toBe(false);
  });

  // 재연결은 잔고를 깜빡이게 하지 않는다. 값은 남기고 "맞다" 는 표시만 내린다.
  it("재연결하면 계좌 값은 남고 표시만 내려간다", () => {
    const store = createTickStore({ settleMs: null });
    store.getState().hydrate(snapshot(400), closes(3, 400), ACCOUNT);

    store.getState().reset();

    expect(store.getState().valuation).not.toBeNull();
    expect(store.getState().accountSynced).toBe(false);
  });

  // 주문·환전 직후의 재조회다. 서버에서 방금 읽어 온 값이라 다시 선다.
  it("잔고를 다시 읽으면 표시가 다시 선다", () => {
    const store = createTickStore({ settleMs: null });
    store.getState().hydrate(snapshot(400), closes(3, 400), ACCOUNT);
    store.getState().reset();

    store.getState().setValuation(VALUATION);

    expect(store.getState().accountSynced).toBe(true);
  });
});

describe("개인 데이터 정리", () => {
  it("정리하면 개인 필드가 사라지고 공개 데이터는 남는다", () => {
    const store = hydrated(400);
    store.getState().receive(valuation(401, "5"));
    store.getState().receive(heatmap(401));
    store.getState().receive(prices(402)); // 401 확정
    expect(store.getState().valuation).not.toBeNull();
    expect(store.getState().heatmap).not.toBeNull();

    store.getState().clearPersonal();

    const state = store.getState();
    expect(state.valuation).toBeNull();
    expect(state.myRank).toBeNull();
    expect(state.heatmap).toBeNull();
    expect(state.fills).toEqual([]);
    // 공개 데이터는 그대로다 — 로그아웃해도 시세는 계속 본다.
    expect(state.tickSeq).toBe(401);
    expect(Object.keys(state.prices)).not.toHaveLength(0);
    expect(state.series).not.toBeNull();
  });

  it("정리한 뒤 이어지는 Tick 에도 개인 큐의 데이터는 다시 나타나지 않는다", () => {
    const store = hydrated(400);
    store.getState().receive(valuation(401, "5"));
    store.getState().receive(prices(402));
    store.getState().clearPersonal();

    // 미인증으로 재연결한 뒤에는 공개 조각만 온다 — 개인 큐를 구독하지 않으므로.
    store.getState().receive(prices(403));
    store.getState().receive(prices(404));

    expect(store.getState().tickSeq).toBe(403);
    expect(store.getState().valuation).toBeNull();
    expect(store.getState().myRank).toBeNull();
    expect(store.getState().fills).toEqual([]);
  });

  /**
   * **히트맵은 예외다.** 공개 토픽의 전체 브로드캐스트라 미인증 연결에도 계속 도착한다 —
   * 정리는 그 순간까지만 유효하다. 익명 4버킷 집계라 유출은 아니고 화면도 미인증에 그리지
   * 않지만, 이 한계를 모르면 `clearPersonal` 이 보장하는 것을 넓게 읽는다
   * (`tick-store.ts` 의 `ponytail:` 주석).
   */
  it("히트맵은 정리해도 공개 브로드캐스트로 다시 채워진다", () => {
    const store = hydrated(400);
    store.getState().receive(heatmap(401));
    store.getState().receive(prices(402));
    expect(store.getState().heatmap).not.toBeNull();

    store.getState().clearPersonal();
    expect(store.getState().heatmap).toBeNull();

    store.getState().receive(heatmap(403));
    store.getState().receive(prices(404));

    expect(store.getState().heatmap).not.toBeNull();
  });

  /** 재연결마다 불리는 `reset` 에 개인 데이터 삭제를 얹으면 잔고가 깜빡인다. */
  it("재연결로는 개인 데이터가 지워지지 않는다", () => {
    const store = hydrated(400);
    store.getState().receive(valuation(401, "5"));
    store.getState().receive(prices(402));
    const before = store.getState().valuation;

    store.getState().reset();
    expect(store.getState().valuation).toBe(before);

    store.getState().hydrate(snapshot(404), closes(3, 404));
    expect(store.getState().valuation).toBe(before);
  });
});

describe("디스패처", () => {
  const stores = {
    tick: createTickStore({ settleMs: null }),
    session: createSessionStore(),
    chat: createChatStore(),
  };

  beforeEach(() => {
    stores.tick.getState().hydrate(snapshot(400));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("타입 하나만 보고 가른다 — destination 이 같아도 스토어가 다르다", () => {
    // /user/queue/me 에 섞여 오는 셋: 매 Tick(평가액) · 사건(폐장 자동 취소, tickSeq 없음)
    expect(dispatch(valuation(401, "5"), stores)).toBe(true);
    expect(stores.tick.getState().pendingTickSeq).toBe(401);

    expect(
      dispatch(
        {
          type: "order.expired",
          tickSeq: null,
          gameDate: null,
          payload: { orderId: 9 },
        },
        stores
      )
    ).toBe(true);
    expect(stores.session.getState().events[0]?.type).toBe("order.expired");

    expect(
      dispatch(
        {
          type: "chat.message",
          tickSeq: 401,
          gameDate: null,
          payload: { messageId: 1, content: "hi", deleted: false },
        },
        stores
      )
    ).toBe(true);
    expect(stores.chat.getState().messages).toHaveLength(1);

    expect(
      dispatch(
        { type: "nope", tickSeq: null, gameDate: null, payload: {} },
        stores
      )
    ).toBe(false);
  });

  it("phase 전환은 세션 시계를 갈아 끼우고 오프셋을 다시 잡는다", () => {
    dispatch(
      {
        type: "game.phase",
        tickSeq: 401,
        gameDate: dateOf(401),
        payload: {
          phase: "SETTLING",
          phaseEndsAt: "2026-09-21T08:00:00Z",
          serverTime: "2026-09-21T07:40:00Z",
          gameDate: dateOf(401),
          gameMonth: "2021-01",
          tickSeq: 401,
          nextTickAt: null,
          feeFree: false,
          leaderboardBlackout: false,
          seasonDay: 1,
        },
      },
      stores
    );
    const session = stores.session.getState();
    expect(session.phase).toBe("SETTLING");
    expect(session.offsetMs).toBeTypeOf("number");
  });

  it("game.clock 은 시계를 세우지 않고 '다시 물어라' 만 올린다", () => {
    const before = stores.session.getState().clockRevision;

    expect(
      dispatch(
        {
          type: "game.clock",
          tickSeq: null,
          gameDate: null,
          replayEpoch: 0,
          payload: { reason: "PAUSED" },
        },
        stores
      )
    ).toBe(true);

    // payload 에 시계 값이 없으므로 세션 시계는 그대로다. 신호만 오른다.
    expect(stores.session.getState().clockRevision).toBe(before + 1);
  });

  // 이 둘은 스토어를 따로 쓴다 — 세대는 연결 하나를 가로질러 남는 값이라, 위 검사들이
  // 남긴 세대를 물려받으면 첫 줄부터 "세대가 바뀌었다" 가 되어 버린다.
  const freshStores = () => ({
    tick: createTickStore({ settleMs: null }),
    session: createSessionStore(),
    chat: createChatStore(),
  });

  it("세대가 바뀌면 기준을 버리고 재조회를 건다 — 되감긴 Tick 이 중복으로 버려지지 않는다", () => {
    const own = freshStores();
    own.tick.getState().hydrate(snapshot(400), closes(3, 400));

    // 지금까지 본 세대를 알려 둔다 (재생 메시지는 전부 세대를 싣는다).
    dispatch({ ...prices(401), replayEpoch: 3 }, own);
    expect(own.tick.getState().replayEpoch).toBe(3);

    // 관리자가 되감았다. 가장 먼저 오는 것은 가격이 아니라 game.clock 이다.
    dispatch(
      {
        type: "game.clock",
        tickSeq: null,
        gameDate: null,
        replayEpoch: 4,
        payload: { reason: "SEEK_STARTED" },
      },
      own
    );

    const reset = own.tick.getState();
    expect(reset.tickSeq).toBe(0); // 400 인 채로 두면 새 1·2·3 이 전부 버려진다
    expect(reset.baseTickSeq).toBeNull();
    expect(reset.needsResync).toBe(true);

    // 재조회가 들어오면 되감긴 순번 위에서 다시 이어붙는다.
    own.tick.getState().hydrate(snapshot(1), closes(3, 1, 1));
    dispatch({ ...prices(2), replayEpoch: 4 }, own);
    dispatch({ ...prices(3), replayEpoch: 4 }, own);
    expect(own.tick.getState().tickSeq).toBe(2);
    expect(own.tick.getState().needsResync).toBe(false);
  });

  it("세대가 없는 봉투는 세대 판정에 끼어들지 않는다", () => {
    const own = freshStores();
    own.tick.getState().hydrate(snapshot(400), closes(3, 400));

    dispatch({ ...prices(401), replayEpoch: 3 }, own);
    // 개인 큐의 평가액에는 replayEpoch 가 없다 (재생 메시지가 아니다).
    dispatch(valuation(402, "5"), own);

    expect(own.tick.getState().replayEpoch).toBe(3);
    expect(own.tick.getState().needsResync).toBe(false);
  });

  it("채팅은 200건을 넘지 않고 삭제는 행을 남긴다", () => {
    const chat = createChatStore();
    for (let messageId = 1; messageId <= 250; messageId++) {
      chat.getState().receive({
        messageId,
        nickname: "a",
        avatarId: 1,
        content: "x",
        sentAt: "",
        gameDate: "",
        withdrawn: false,
        deleted: false,
      });
    }
    expect(chat.getState().messages).toHaveLength(200);
    chat.getState().markDeleted(250);
    expect(chat.getState().messages.at(-1)).toMatchObject({
      messageId: 250,
      deleted: true,
    });
  });
});
