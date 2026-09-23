"use client";

import { createStore } from "zustand/vanilla";

// 값 import 는 이것 하나다. 스토어는 서버를 부르지 않지만, **잔고를 다시 읽는 일**만은
// 예외로 여기 둔다 — 주문·환전·청약이 끝나는 자리마다 같은 여섯 줄을 복제하고 있었다.
import { fetchAccount, fetchPositions } from "@/lib/market-api";
import type {
  ClosesResponse,
  Heatmap,
  NewsItem,
  OrderFilled,
  Positions,
  PriceEntry,
  PriceSnapshot,
  RankingMe,
  Valuation,
} from "@/lib/market-types";
import type { Envelope } from "@/lib/ws";

// ─────────────────────────────────────────────────────────────────────────────
// 틱 스토어 — 한 Tick 의 조각을 모아 **한 번의 setState** 로 쓴다.
//
// **클라이언트 전용이다.** 모듈 최상위에서 만든 스토어는 서버에서 요청 간 공유되므로 서버
// 컴포넌트에서 import 하면 사용자 계좌가 섞인다. 위의 "use client" 가 그 경계다.
//
// React 를 import 하지 않는다. 그래야 브라우저 없이 통째로 테스트한다 (`vitest`).
// ─────────────────────────────────────────────────────────────────────────────

/** 뉴스·체결 알림 상한. 덮어쓰기 조각(가격·환율·평가액·순위)은 상한이 필요 없다. */
const NEWS_LIMIT = 100;
const FILLS_LIMIT = 50;
/** 한 종목의 약 16년 거래일. 과거 전체 데이터는 서버에 두고 화면 시계열은 제한한다. */
const SERIES_LIMIT = 4096;

/**
 * 조립 버퍼가 마지막 조각을 받은 뒤 확정까지 기다리는 시간.
 *
 * 계약의 확정 규칙은 "다음 `tickSeq` 가 도착하면 이전 Tick 확정" 하나다. 그 규칙만 두면
 * 화면이 항상 한 Tick(31~36초) 늦게 바뀌어 "다음 종가까지" 카운트다운이 0 이 돼도 가격이
 * 그대로다. 한 Tick 의 조각은 서버가 한 순회에서 밀리초 안에 내보내므로, 조각이 잠깐 멈추면
 * 그 Tick 을 확정한다. 다음 `tickSeq` 도착은 여전히 확정 조건이고, 늦게 온 같은 번호의
 * 조각은 중복으로 버려진다.
 */
const DEFAULT_SETTLE_MS = 300;

export type Series = {
  instrumentId: number;
  currency: "KRW" | "USD";
  priceScale: number;
  points: { gameDate: string; close: string }[];
};

export type TickState = {
  /** 확정된 Tick. 0 은 아직 없음. */
  tickSeq: number;
  gameDate: string | null;
  usdKrwRate: string | null;
  prices: Record<number, PriceEntry>;
  /** 차트 시계열. 가격과 같은 갱신에서 함께 바뀌어야 Tick 이 어긋나지 않는다. */
  series: Series | null;
  valuation: Valuation | null;
  positions: Positions | null;
  myRank: RankingMe | null;
  heatmap: Heatmap | null;
  news: NewsItem[];
  fills: (OrderFilled & { tickSeq: number })[];
  /**
   * 히스토리의 기준 Tick. null 이면 아직 히스토리를 받지 못한 상태라 도착 메시지를 버퍼에
   * 쌓기만 한다 (`ws.md` 초기 로드 절차 3단계).
   */
  baseTickSeq: number | null;
  /**
   * 히스토리를 받았는데 **아직 공개된 틱이 없었다** (1일차 브리핑·되감기 직후). 조회가
   * `asOfTickSeq: null` 을 주므로 기준을 세울 수 없어 `baseTickSeq` 가 null 로 남는다.
   *
   * **이 상태에서 첫 봉투가 오면 재조회를 건다.** 그것 말고는 기준을 세울 길이 없어 — 방송이
   * 아무리 와도 `receive` 가 버퍼에만 쌓고, 사용자가 새로고침할 때까지 화면이 멎는다.
   * 재조회는 종가 시계열·계좌까지 함께 세우므로 봉투에서 직접 세우는 것보다 온전하다.
   */
  awaitingFirstPublish: boolean;
  /**
   * 마지막으로 본 재생 세대 (`Envelope.replayEpoch`). null 이면 아직 재생 메시지를 받지
   * 못한 상태다.
   *
   * **세대가 바뀌면 `tickSeq` 가 되감긴다** — 관리자 초기화·뒤로 이동이 그것이다. 순번만
   * 보면 되감긴 틱은 전부 "이미 지난 번호" 라 버려지고, 화면이 되감기 전 가격에 굳는다.
   */
  replayEpoch: number | null;
  /** 확정 대기 중인 Tick 번호. 화면이 아니라 테스트가 본다. */
  pendingTickSeq: number | null;
  /** `tickSeq` 가 1씩 늘지 않았다 — 히스토리를 다시 조회해야 한다. */
  needsResync: boolean;
  /**
   * **이번 동기화 회차에서 계좌 조회가 성공했는가.**
   *
   * `valuation !== null` 로는 알 수 없다 — `hydrate` 는 실패한 조회의 자리에 옛 값을 그대로
   * 두고(화면을 비우러 온 것이 아니다), `reset` 은 계좌를 지우지 않는다(재연결마다 잔고가
   * 깜빡인다). 그래서 **화면에 값이 있다**와 **그 값이 지금 서버와 맞다**가 갈린다.
   *
   * 표시는 옛 값을 계속 쓰고, 주문 가능 판단만 이쪽을 본다.
   */
  accountSynced: boolean;
};

/**
 * 초기 로드가 REST 로 받아 오는 계좌 쪽 상태. **셋이 따로 실패할 수 있어 전부 선택이다** —
 * 하나가 없다고 나머지까지 비워 두면 화면이 더 많이 빈다.
 */
export type AccountSnapshot = {
  valuation?: Valuation;
  positions?: Positions;
  myRank?: RankingMe;
  heatmap?: Heatmap;
};

export type TickActions = {
  /** 봉투 하나를 받는다. `tickSeq` 가 없는 봉투는 이 스토어의 것이 아니다. */
  receive: (envelope: Envelope) => void;
  /**
   * 재생 메시지가 말한 세대를 본다. **디스패처가 종류를 가르기 전에 부른다** — 되감기를
   * 가장 먼저 알리는 것은 가격이 아니라 `game.clock`(`SEEK_STARTED`) 이라 `receive` 에
   * 두면 한 박자 늦는다.
   *
   * 세대가 바뀌었으면 들고 있던 기준·조립 버퍼를 버리고 재조회를 건다. 가격·시계열은
   * 지우지 않는다 — 재조회가 한 번에 갈아 끼우므로 그 사이 화면을 비울 이유가 없다.
   */
  observeEpoch: (replayEpoch: number) => void;
  /**
   * 히스토리로 상태를 세운다. 기준 Tick 보다 큰 버퍼만 이어붙이고 같거나 작은 것은
   * 버린다. 구멍 회복(재조회)도 같은 함수다 — 응답을 넣으면 신호가 멈춘다.
   *
   * @param account 계좌·순위·집계의 첫 값. 방송이 멎는 구간에서는 이것이 유일한 출처다.
   *                가격과 **같은 갱신에** 넣어야 버퍼가 그 위에 순서대로 얹힌다
   */
  hydrate: (
    prices: PriceSnapshot,
    closes?: ClosesResponse,
    account?: AccountSnapshot
  ) => void;
  /** 차트 종목을 바꾼다. 가격은 그대로다. */
  setSeries: (closes: ClosesResponse) => void;
  /**
   * 평가액만 갈아 끼운다. 환전처럼 **틱과 무관하게 잔고가 바뀌는 일** 뒤에 쓴다 — 다음
   * 방송까지 기다리면 헤더는 옛 현금을, 주문 패널은 새 현금을 말한다.
   */
  setValuation: (valuation: Valuation) => void;
  setPositions: (positions: Positions) => void;
  /**
   * 내 순위만 갈아 끼운다. **틱이 더 오지 않는 구간에 필요하다** — `ranking.me` 는 틱마다
   * 오는데 폐장(16:40)에 암전이 풀리는 순간에는 그날의 틱이 이미 끝나 있다. 마지막으로 받은
   * 값이 암전 중의 것(순위 `null`)이라, 다시 읽어 넣지 않으면 헤더가 밤새 "암전"에 굳는다.
   */
  setMyRank: (myRank: RankingMe) => void;
  /** 재연결. 새 연결이므로 구독 뒤 히스토리를 다시 불러야 하고 그때까지 다시 버퍼링한다. */
  reset: () => void;
  /**
   * 로그아웃·세션 종료. 개인 필드만 지우고 공개 데이터(가격·시계열·뉴스)는 남긴다.
   *
   * **`reset` 과 따로 두는 이유**는 `reset` 이 재연결마다 불리기 때문이다. 거기에 개인 데이터
   * 삭제를 얹으면 재연결할 때마다 잔고가 깜빡인다.
   */
  clearPersonal: () => void;
};

export type TickStore = ReturnType<typeof createTickStore>;

const INITIAL: TickState = {
  tickSeq: 0,
  gameDate: null,
  usdKrwRate: null,
  prices: {},
  series: null,
  valuation: null,
  positions: null,
  myRank: null,
  heatmap: null,
  news: [],
  fills: [],
  baseTickSeq: null,
  awaitingFirstPublish: false,
  replayEpoch: null,
  pendingTickSeq: null,
  needsResync: false,
  accountSynced: false,
};

/**
 * @param options.settleMs 마지막 조각 뒤 확정까지 기다리는 ms. null 이면 다음 `tickSeq`
 *                         도착으로만 확정한다 (테스트가 조립 버퍼를 관찰할 때 쓴다)
 */
export function createTickStore(options?: { settleMs?: number | null }) {
  const settleMs =
    options?.settleMs === undefined ? DEFAULT_SETTLE_MS : options.settleMs;

  // 조립 버퍼와 구독 뒤 백로그는 화면 상태가 아니라 여기 둔다 — 화면은 확정된 것만 본다.
  let pending: { tickSeq: number; fragments: Envelope[] } | null = null;
  let backlog: Envelope[] = [];
  let settleTimer: ReturnType<typeof setTimeout> | null = null;

  const store = createStore<TickState & TickActions>()((set, get) => {
    function clearSettle() {
      if (settleTimer) {
        clearTimeout(settleTimer);
        settleTimer = null;
      }
    }

    function commitPending() {
      clearSettle();
      if (!pending) {
        return;
      }
      const { tickSeq, fragments } = pending;
      pending = null;
      set((state) => ({
        ...applyFragments(state, tickSeq, fragments),
        tickSeq,
        pendingTickSeq: null,
        // 확정 전 Tick 이 있는데 1 만큼 늘지 않았으면 구멍이다.
        needsResync:
          state.needsResync ||
          (state.tickSeq > 0 && tickSeq !== state.tickSeq + 1),
      }));
    }

    function stage(envelope: Envelope) {
      const tickSeq = envelope.tickSeq as number;
      if (tickSeq <= get().tickSeq) {
        return; // 이미 확정된 Tick 의 조각 — 히스토리에 포함돼 있다. 중복이다.
      }
      if (pending && tickSeq > pending.tickSeq) {
        commitPending(); // 다음 Tick 이 왔다 — 이전 Tick 확정.
      }
      if (pending && tickSeq < pending.tickSeq) {
        return; // 확정 직전 Tick 보다 오래된 조각. 이미 늦었다.
      }
      if (!pending) {
        pending = { tickSeq, fragments: [] };
        set({ pendingTickSeq: tickSeq });
      }
      pending.fragments.push(envelope);
      if (settleMs !== null) {
        clearSettle();
        settleTimer = setTimeout(commitPending, settleMs);
      }
    }

    return {
      ...INITIAL,

      receive(envelope) {
        if (envelope.tickSeq === null) {
          return;
        }
        if (get().baseTickSeq === null) {
          backlog.push(envelope); // 히스토리 전 — 쌓기만 한다.
          // 히스토리가 "아직 틱 없음" 을 준 뒤라면 이 봉투가 곧 개장 신호다. 재조회를 걸어
          // 기준을 세운다 — 안 걸면 이대로 영원히 쌓기만 하고 새로고침해야 보인다.
          if (get().awaitingFirstPublish && !get().needsResync) {
            set({ needsResync: true });
          }
          return;
        }
        stage(envelope);
      },

      observeEpoch(replayEpoch) {
        const known = get().replayEpoch;
        if (known === replayEpoch) {
          return;
        }
        if (known === null) {
          // 이 연결에서 처음 본 세대다. 되감긴 것이 아니라 기준을 알게 된 것뿐이다.
          set({ replayEpoch });
          return;
        }

        // 되감겼다. `tickSeq` 를 0 으로 내리지 않으면 새 세대의 1·2·3 번이 `stage` 에서
        // 전부 "이미 지난 번호" 로 버려진다.
        clearSettle();
        pending = null;
        backlog = [];
        set({
          replayEpoch,
          tickSeq: 0,
          baseTickSeq: null,
          awaitingFirstPublish: false,
          pendingTickSeq: null,
          needsResync: true,
          accountSynced: false,
        });
      },

      hydrate(prices, closes, account) {
        clearSettle();
        const queued = [...backlog, ...(pending?.fragments ?? [])];
        backlog = [];
        pending = null;

        // **최초 공개 전에는 기준을 세우지 않는다.** 조회가 준 `null` 을 그대로 넣으면
        // `baseTickSeq` 가 null 인 채로 굳어 이후 모든 방송이 버퍼로만 들어간다. 대신
        // 개장을 기다린다고 표시해 두고, 첫 봉투가 `receive` 에서 재조회를 건다.
        const published = prices.asOfTickSeq !== null;

        set((state) => ({
          ...state,
          tickSeq: prices.asOfTickSeq ?? state.tickSeq,
          gameDate: prices.asOfGameDate,
          usdKrwRate: prices.usdKrwRate,
          prices: indexPrices(prices.prices),
          series: closes ? toSeries(closes) : state.series,
          // 못 받은 조각은 들고 있던 값을 지우지 않는다. 재조회는 구멍을 메우러 온
          // 것이지 화면을 비우러 온 것이 아니다.
          valuation: account?.valuation ?? state.valuation,
          positions: account?.positions ?? state.positions,
          myRank: account?.myRank ?? state.myRank,
          heatmap: account?.heatmap ?? state.heatmap,
          baseTickSeq: prices.asOfTickSeq,
          awaitingFirstPublish: !published,
          pendingTickSeq: null,
          needsResync: false,
          // 값을 보존하는 것과 **성공했다고 말하는 것**은 다르다. 위의 `?? state.valuation` 이
          // 옛 값을 남기더라도 여기서는 이번 회차의 사실만 적는다.
          accountSynced: account?.valuation !== undefined,
        }));

        if (!published) {
          // 기준이 없으니 이어붙일 수도 없다. 쌓아 둔 것은 버리지 않고 되돌려 놓는다.
          //
          // **여기서 재조회를 걸지 않는다.** 조회가 방금 "아직 틱 없음" 이라고 답했으므로
          // 곧바로 다시 물으면 같은 답을 받고 그대로 맴돈다. 다음 봉투가 `receive` 에서
          // 건다 — 그것이 "정말로 틱이 나갔다" 는 유일한 증거다.
          backlog = queued;
          return;
        }

        // 기준보다 큰 것만 이어붙는다. 같거나 작은 것은 `stage` 가 버린다.
        for (const envelope of queued) {
          stage(envelope);
        }
      },

      setValuation(valuation) {
        // 서버에서 방금 읽어 온 값이다 — 조회 성공을 여기서 다시 세운다.
        set({ valuation, accountSynced: true });
      },

      setPositions(positions) {
        set({ positions });
      },

      setMyRank(myRank) {
        set({ myRank });
      },

      setSeries(closes) {
        set((state) => {
          const series = toSeries(closes);
          // 응답이 오는 사이 Tick 이 하나 지났으면 그 종가가 빠진다. 확정된 현재가로 채운다.
          const latest = state.prices[closes.instrumentId];
          if (
            latest &&
            !latest.marketClosed &&
            state.gameDate &&
            closes.asOfTickSeq !== null &&
            closes.asOfTickSeq < state.tickSeq &&
            (series.points.at(-1)?.gameDate ?? "") < state.gameDate
          ) {
            series.points.push({
              gameDate: state.gameDate,
              close: latest.close,
            });
            series.points = series.points.slice(-SERIES_LIMIT);
          }
          return { series };
        });
      },

      reset() {
        clearSettle();
        pending = null;
        backlog = [];
        // 세대도 잊는다. 끊겨 있는 동안 되감겼을 수 있고, 어차피 히스토리를 다시 받으므로
        // 기억해 둔 값으로 한 번 더 재조회를 걸 이유가 없다.
        set({
          baseTickSeq: null,
          awaitingFirstPublish: false,
          replayEpoch: null,
          pendingTickSeq: null,
          needsResync: false,
          // 값은 남기고 "맞다" 는 표시만 내린다. 재조회가 끝나면 다시 선다.
          accountSynced: false,
        });
      },

      clearPersonal() {
        // ponytail: `heatmap` 정리는 다음 틱까지만 유효하다. `social.heatmap` 은 공개 토픽의
        // 전체 브로드캐스트라 미인증 연결에도 계속 도착해 이 필드를 다시 채운다 — 조회
        // (`GET /social/heatmap`)는 `사용자` 인데 실시간은 `전체` 인 계약 불일치가 원인이고,
        // 익명 4버킷 집계라 유출은 아니다 (`soc.md`). 화면은 미인증에 히트맵을 그리지 않는다.
        // 계약을 맞추려면 발행을 개인 큐로 옮겨야 하고 그것은 이 작업의 범위 밖이다.
        set({
          valuation: null,
          positions: null,
          myRank: null,
          heatmap: null,
          fills: [],
          accountSynced: false,
        });
      },
    };
  });

  return store;
}

/** 조각들을 한 상태로 접는다. 여기서 만든 객체가 `set` 한 번에 들어간다. */
function applyFragments(
  state: TickState,
  tickSeq: number,
  fragments: Envelope[]
): Partial<TickState> {
  let next: Partial<TickState> = {};
  let series = state.series;
  let news = state.news;
  let fills = state.fills;

  for (const envelope of fragments) {
    switch (envelope.type) {
      case "market.prices": {
        const payload = envelope.payload as PriceSnapshot;
        next = {
          ...next,
          gameDate: payload.asOfGameDate,
          usdKrwRate: payload.usdKrwRate,
          prices: indexPrices(payload.prices),
        };
        if (series) {
          series = appendPoint(series, payload);
        }
        break;
      }
      case "account.valuation":
        next.valuation = envelope.payload as Valuation;
        break;
      case "account.positions":
        next.positions = envelope.payload as Positions;
        break;
      case "ranking.me":
        next.myRank = envelope.payload as RankingMe;
        break;
      case "social.heatmap":
        next.heatmap = envelope.payload as Heatmap;
        break;
      case "news.published":
        news = [...(envelope.payload as NewsItem[]), ...news].slice(
          0,
          NEWS_LIMIT
        );
        break;
      case "order.filled":
        fills = [
          { ...(envelope.payload as OrderFilled), tickSeq },
          ...fills,
        ].slice(0, FILLS_LIMIT);
        break;
      default:
        // ipo.competition 등 아직 화면이 없는 조각. 확정 리듬에는 참여하지만 저장하지 않는다.
        break;
    }
  }

  return { ...next, series, news, fills };
}

/** 휴장일은 새 종가가 없으므로 포인트를 추가하지 않는다 (`mkt.md`). 같은 날짜도 한 번만. */
function appendPoint(series: Series, snapshot: PriceSnapshot): Series {
  const entry = snapshot.prices.find(
    (price) => price.instrumentId === series.instrumentId
  );
  // 절단 기준이 없는 스냅샷(최초 공개 전)은 붙일 날짜가 없다. 방송 봉투에서는 나오지 않는다.
  if (!entry || entry.marketClosed || snapshot.asOfGameDate === null) {
    return series;
  }
  const asOfGameDate = snapshot.asOfGameDate;
  const last = series.points.at(-1);
  if (last && last.gameDate >= asOfGameDate) {
    return series;
  }
  return {
    ...series,
    points: [
      ...series.points,
      { gameDate: asOfGameDate, close: entry.close },
    ].slice(-SERIES_LIMIT),
  };
}

function toSeries(closes: ClosesResponse): Series {
  return {
    instrumentId: closes.instrumentId,
    currency: closes.currency,
    priceScale: closes.priceScale,
    points: closes.points.slice(-SERIES_LIMIT),
  };
}

function indexPrices(prices: PriceEntry[]): Record<number, PriceEntry> {
  return Object.fromEntries(prices.map((price) => [price.instrumentId, price]));
}

/** 앱이 쓰는 단일 인스턴스. 테스트는 `createTickStore` 로 자기 것을 만든다. */
export const tickStore = createTickStore();

/** 사용자 주문 직후 수량을 즉시 맞춘다. 주기적인 조회에는 사용하지 않는다. */
export async function reloadPositions(): Promise<void> {
  try {
    tickStore.getState().setPositions(await fetchPositions());
  } catch (error) {
    console.warn("보유 종목을 갱신하지 못했습니다", error);
  }
}

/**
 * 잔고를 서버에서 다시 읽어 스토어에 쓴다. **돈을 움직인 직후에 부른다** — 주문·환전·청약이
 * 끝난 뒤 다음 Tick 방송을 기다리면 그때까지 헤더와 주문 패널이 옛 현금을 말한다.
 *
 * 실패는 삼킨다. 이미 성공한 거래를 되돌릴 수 없고, 다음 방송이 어차피 값을 고쳐 준다 —
 * 여기서 토스트를 띄우면 "환전 완료" 바로 뒤에 붉은 문구가 따라붙는다.
 */
export async function reloadValuation(): Promise<void> {
  try {
    tickStore.getState().setValuation(await fetchAccount());
  } catch (error) {
    console.warn("계좌를 다시 읽지 못했습니다", error);
  }
}
