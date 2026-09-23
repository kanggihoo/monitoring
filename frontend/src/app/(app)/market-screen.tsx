"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import { useSearchParams } from "next/navigation";
import { LineChart } from "lucide-react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { ChatPanel } from "@/components/market/chat-panel";
import { InstrumentInfoCard } from "@/components/market/instrument-info-card";
import { InstrumentListV2 } from "@/components/market/instrument-list.v2";
import { InstrumentNews } from "@/components/market/instrument-news";
import { instrumentTags } from "@/components/market/labels";
import { Leaderboard } from "@/components/market/leaderboard";
import { NewsTicker } from "@/components/market/news-ticker";
import {
  type OrderDraft,
  type OrderSubmit,
  OrderTicket,
} from "@/components/market/order-ticket";
import { PriceChange } from "@/components/market/price-change";
import {
  CHART_INTERVALS,
  CHART_RANGES,
  type ChartInterval,
  type ChartRange,
} from "@/components/market/price-chart";
import { ShortfallDialog } from "@/components/market/shortfall-dialog";
import { MarketTour } from "@/components/tour/market-tour";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useGameClock } from "@/hooks/use-game-clock";
import { useLiveQuery } from "@/hooks/use-live-query";
import { usePositions } from "@/hooks/use-positions";
import { trackEvent } from "@/lib/analytics";
import { krw, localPrice, shares } from "@/lib/format";
import { updateInstruments, updateOfferings } from "@/lib/live-query-reducers";
import {
  fetchCloses,
  fetchInstrumentInfo,
  fetchInstruments,
  fetchIpoOfferings,
  fetchLeaderboard,
  fetchSignalCriteria,
} from "@/lib/market-api";
import type {
  InstrumentInfo,
  InstrumentMeta,
  IpoOfferingStatus,
  Leaderboard as LeaderboardData,
  SignalCriterion,
} from "@/lib/market-types";
import {
  fetchOrderPreview,
  type OrderPreview,
  placeOrder,
  type ShortfallExchange,
  submitExchange,
} from "@/lib/order-api";
import { sessionStore } from "@/stores/session-store";
import { reloadValuation, tickStore } from "@/stores/tick-store";

// 캔버스 라이브러리는 브라우저에서만 그린다.
const PriceChart = dynamic(
  () => import("@/components/market/price-chart").then((m) => m.PriceChart),
  { ssr: false, loading: () => <Skeleton className="h-full w-full" /> }
);

/**
 * **기간과 봉 주기는 차트가 정의한다.** 예전에는 이 화면이 기간 목록을 들고 시리즈를
 * 잘라 넘겼는데, 자르는 쪽과 그리는 쪽이 갈려 있으면 "1개월인데 그 앞은 왜 없나" 같은
 * 것을 두 파일에서 맞춰 봐야 한다. 지금은 화면이 고른 값을 그대로 건네고, 무엇을
 * 보여 줄지는 차트가 창으로 결정한다.
 */
const RANGES = CHART_RANGES;

/**
 * 미리보기를 부르기 전에 기다리는 시간. 수량은 한 글자씩 들어오는데 계약이 "입력마다
 * 호출"이라 그대로 두면 `1`·`12`·`120` 이 각각 한 번씩 나간다. 타이핑이 멎은 뒤에만 묻는다.
 */
const PREVIEW_DEBOUNCE_MS = 300;

/**
 * 주문을 보내도 되는 상태인지 **지금** 확인한다. 아니면 이유를 띄우고 `false`.
 *
 * 렌더 시점의 판단과 따로 두는 이유는 그 사이의 변화다 — 버튼을 누르는 순간 연결이 끊겼거나
 * 되감기가 들어왔을 수 있고, 그때 화면은 아직 이전 렌더의 값을 보여 준다.
 *
 * **서버와의 실제 일치를 보장하지는 않는다** — 통신하지 않는다. 마지막 렌더 이후 바뀐 로컬
 * 상태를 잡을 뿐이고, 그 밖의 불일치는 서버가 주문을 거절해 알린다.
 */
function assertSynced(): boolean {
  const tick = tickStore.getState();
  if (
    sessionStore.getState().connected &&
    tick.baseTickSeq !== null &&
    tick.accountSynced
  ) {
    return true;
  }
  toast.error(
    "실시간 시세와 잔고를 다시 맞추는 중입니다. 잠시 뒤 다시 시도해 주세요."
  );
  return false;
}

export function MarketScreen({
  instruments: seeded,
  isLoggedIn = false,
  nickname = null,
}: {
  /**
   * 서버 컴포넌트가 그린 첫 값. **첫 페인트 전용이다** — 아래에서 틱마다 다시 읽는다.
   */
  instruments: InstrumentMeta[];
  isLoggedIn?: boolean;
  /** 로그인한 사람의 닉네임. 리더보드의 내 줄이 이 이름으로 선다. */
  nickname?: string | null;
}) {
  /**
   * 종목 목록. **틱마다 다시 읽는다** — `listingStatus`·`isNew`·`asOfGameDate` 는 저장값이
   * 아니라 게임 시계에서 파생되는데(`unv.md`), 방송에는 실리지 않는다. 서버 컴포넌트가 준
   * 값에 머물면 공모주가 상장되고 차트가 그려지는 동안에도 목록만 `상장 예정` 으로 남고,
   * 되감기로 상장이 취소돼도 상장된 채로 남는다 — 둘 다 새로고침해야 풀렸다.
   *
   * 공개 조회라 미인증에도 부른다. 포지션(`useTickQuery`)과 같은 이유·같은 방식이다.
   */
  const { data: universe } = useLiveQuery(fetchInstruments, updateInstruments);
  const instruments = universe?.instruments ?? seeded;
  // 뉴스의 종목 태그가 이 화면으로 온다 (기획 10.4.1). 없거나 모르는 id 면 첫 종목이다.
  const linked = Number(useSearchParams().get("instrumentId"));
  const [selectedId, setSelectedId] = useState<number>(
    instruments.some((item) => item.instrumentId === linked)
      ? linked
      : (instruments[0]?.instrumentId ?? 0)
  );

  /**
   * 고른 종목을 주소에 남긴다. **화면 상태를 주소로 옮기는 것이 이 화면의 복귀 조건이다** —
   * 로그인 복귀 경로는 `pathname + search` 를 담으므로, 주소에 없는 선택은 로그인 뒤 사라진다
   * (수용 기준: "특정 종목을 보다가 로그인해도 그 종목으로 돌아온다").
   *
   * `router.replace` 가 아니라 네이티브 history API 를 쓴다 — 서버 컴포넌트를 다시 부르지
   * 않으면서 주소만 바꾸는 방법이고 Next 라우터가 그것을 `useSearchParams` 와 동기화한다
   * (`node_modules/next/dist/docs` §Native History API). 종목을 누를 때마다 서버를 다시
   * 부를 이유가 없다.
   */
  /**
   * 주소가 가리키는 종목으로 따라간다. **이미 이 화면에 있을 때를 위한 것이다** — 위 초기값은
   * 마운트에 한 번만 계산되므로, 같은 라우트에서 `?instrumentId=` 만 바뀌면(신규 상장 알림을
   * 마켓에서 누른 경우) 주소만 바뀌고 보던 종목이 그대로 남았다. 다른 화면에서 들어오는
   * 경로는 컴포넌트가 새로 서므로 원래부터 동작했다.
   *
   * **effect 가 아니라 렌더 중에 조정한다** — 주소는 외부 시스템이 아니라 이 컴포넌트가
   * `selectInstrument` 로 직접 쓰는 값이라, effect 로 미루면 한 프레임 낡은 종목이 먼저
   * 그려진다 (React 의 "props 가 바뀔 때 state 조정" 패턴).
   *
   * 목록에 없는 식별자면 움직이지 않는다 — 초기값의 판정과 같다. 그래도 본 값은 갱신해야
   * 사용자가 그 뒤에 고른 종목을 주소가 도로 덮지 않는다.
   */
  const [seenLinked, setSeenLinked] = useState(linked);
  if (linked !== seenLinked) {
    setSeenLinked(linked);
    if (instruments.some((item) => item.instrumentId === linked)) {
      setSelectedId(linked);
    }
  }

  const selectInstrument = (instrumentId: number) => {
    setSelectedId(instrumentId);
    const params = new URLSearchParams(window.location.search);
    params.set("instrumentId", String(instrumentId));
    window.history.replaceState(null, "", `?${params}`);
  };
  const [range, setRange] = useState<ChartRange>("3M");
  /**
   * 봉 주기. **기본은 일봉이다** — 이 게임의 한 점이 거래일 하나의 종가이므로 일봉이
   * 원본이고, 주·월봉은 거기서 묶은 것이다.
   */
  const [interval, setInterval] = useState<ChartInterval>("D");
  const [board, setBoard] = useState<LeaderboardData | null>(null);
  const [draft, setDraft] = useState<OrderDraft | null>(null);
  /** 미리보기는 **어느 입력에 대한 답인지**와 함께 든다. 아래 참고. */
  const [answered, setAnswered] = useState<{
    draft: OrderDraft;
    instrumentId: number;
    data: OrderPreview;
  } | null>(null);
  const [exchanging, setExchanging] = useState(false);
  /** 주문 왕복 중. 같은 주문이 두 번 나가는 것을 막는다. */
  const [placing, setPlacing] = useState(false);
  /**
   * 부족분 확인 모달이 붙들고 있는 매수. 매수를 누른 뒤에만 열리고, 환전이 끝나면 **이
   * 주문을 그대로 이어서 보낸다.**
   *
   * 환전액은 부족분을 딱 채우는 최소 금액이라 여유분이 없는데(`ExchangeQuote.minimumSendAmount`)
   * 환율은 게임 거래일마다, 즉 **틱마다 바뀐다**(`GameTick.usdKrw`). 사용자가 다시 누르게
   * 두면 그 사이 틱 하나에 다시 모자라 환전 확인이 또 뜬다.
   */
  const [askExchange, setAskExchange] = useState<OrderSubmit | null>(null);
  /** 환전이 끝나면 올린다. 잔고가 바뀌었으니 같은 입력이라도 미리보기를 다시 물어야 한다. */
  const [previewRevision, setPreviewRevision] = useState(0);
  const clock = useGameClock();
  const prices = useStore(tickStore, (state) => state.prices);
  /**
   * 시세와 **같은 틱의** 환율. 계좌 요약(`valuation`)도 환율을 들고 있지만 그쪽은 갱신
   * 시점이 달라 한 틱 뒤처질 수 있고, 주문을 받는 서버는 시세 쪽 환율로 값을 매긴다 —
   * `최대 가능` 을 계좌 쪽 환율로 구하면 그 차이만큼 살 수 없는 수량이 나온다.
   */
  const tickUsdKrwRate = useStore(tickStore, (state) => state.usdKrwRate);
  const series = useStore(tickStore, (state) => state.series);
  const valuation = useStore(tickStore, (state) => state.valuation);
  const myRank = useStore(tickStore, (state) => state.myRank);
  const hydrated = useStore(tickStore, (state) => state.baseTickSeq !== null);
  const accountSynced = useStore(tickStore, (state) => state.accountSynced);
  /**
   * 화면이 들고 있는 값이 지금 서버와 맞는가. **주문 패널의 개폐 조건이다.**
   *
   * 셋을 함께 본다: 연결 종료가 확인됐는가(`connected`), 이 연결의 시세 기준이 섰는가
   * (`hydrated`), 이번 회차의 계좌 조회가 성공했는가(`accountSynced`). 어느 하나가
   * 나머지를 대신하지 못한다 — 끊겨도 가격은 남고, 가격이 서도 계좌 조회는 따로 실패한다.
   */
  const synced = clock.connected && hydrated && accountSynced;

  /**
   * 보유 포지션. **매도 가능 수량의 유일한 출처다** — 이것이 없으면 주문 패널의 매도가
   * 보유 0 으로 판단해 영구히 잠긴다. 방송에 실리지 않아 틱마다 다시 읽는다 (`acc.md`).
   */
  const { data: positions, reload: reloadPositions } = usePositions(isLoggedIn);

  const selected = instruments.find((i) => i.instrumentId === selectedId);
  const price = prices[selectedId];

  /**
   * 어떤 종목의 상세를 봤는지 남긴다. 클릭 핸들러가 아니라 선택 결과를 보는 이유는 **주소로
   * 들어온 경우까지 담기 위해서다** — 뉴스의 종목 태그가 `?instrumentId=` 로 이 화면을 열고
   * (기획 10.4.1), 그 경로가 우리가 가장 알고 싶은 유입이다. 클릭에만 걸면 그것이 빠진다.
   */
  const selectedName = selected?.name;
  const selectedAssetType = selected?.assetType;
  useEffect(() => {
    if (!selectedName || !selectedAssetType) return;
    trackEvent("instrument_view", {
      instrument_name: selectedName,
      asset_type: selectedAssetType,
    });
  }, [selectedName, selectedAssetType]);

  // 종목을 고르면 그 종가 시계열을 받아 스토어에 놓는다. 이후 Tick 은 스토어가 이어붙인다.
  useEffect(() => {
    if (!hydrated || !selectedId) return;
    let cancelled = false;
    fetchCloses(selectedId)
      .then((closes) => {
        if (!cancelled) tickStore.getState().setSeries(closes);
      })
      .catch((error: unknown) => {
        console.error("종가 시계열을 불러오지 못했습니다", error);
        toast.error("차트를 불러오지 못했습니다. 잠시 뒤 다시 시도하세요.");
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId, hydrated]);

  /**
   * 종목 정보 카드 (`info.md`, 기획 9장).
   *
   * **틱에 묶지 않는다.** 지표는 분기 공시일에만 바뀌는 저빈도 데이터라(시즌 전체 8~9회)
   * 계약이 WS 토픽을 두지 않았고, 틱마다 다시 읽으면 28종에 대해 같은 답을 수천 번 받는다.
   * 종목을 고를 때만 읽는다.
   *
   * **미인증에는 부르지 않는다** — 이 경로는 서버의 미인증 개방 목록에 없어 401 이 나간다
   * (ADR-0005). 공모 국면 조회가 같은 이유로 같게 판단한다.
   *
   * 차트(`fetchCloses`)와 달리 `hydrated` 를 기다리지 않는다. 이쪽은 틱 스토어에 쓰지
   * 않으므로 구독 전에 읽어도 사라질 Tick 이 없다.
   */
  const [answeredInfo, setAnsweredInfo] = useState<{
    instrumentId: number;
    /** 조회가 실패하면 null 이다. 어느 종목에 대한 실패인지는 위 식별자가 말한다. */
    info: InstrumentInfo | null;
  } | null>(null);
  useEffect(() => {
    if (!isLoggedIn || !selectedId) return;
    let cancelled = false;
    fetchInstrumentInfo(selectedId)
      .then((fresh) => {
        if (!cancelled)
          setAnsweredInfo({ instrumentId: selectedId, info: fresh });
      })
      // 정보 카드만 비운다. 차트·뉴스·주문은 이것과 무관하게 동작해야 한다.
      .catch((error: unknown) => {
        if (cancelled) return;
        console.warn("종목 정보를 불러오지 못했습니다", error);
        setAnsweredInfo({ instrumentId: selectedId, info: null });
      });
    return () => {
      cancelled = true;
    };
  }, [isLoggedIn, selectedId]);

  /**
   * 신호등 판정 기준. 전 종목 공통이라 종목이 아니라 화면에 묶인다 — 한 번 읽고 끝이다.
   * 정보 카드와 같은 경계라 로그인 전에는 부르지 않는다. 못 읽어도 신호등은 그대로 뜨고
   * 토글의 기준 줄만 빈다.
   */
  const [criteria, setCriteria] = useState<SignalCriterion[] | null>(null);
  useEffect(() => {
    if (!isLoggedIn) return;
    let cancelled = false;
    fetchSignalCriteria()
      .then((fresh) => {
        if (!cancelled) setCriteria(fresh.criteria);
      })
      .catch((error: unknown) => {
        if (!cancelled)
          console.warn("신호등 판정 기준을 불러오지 못했습니다", error);
      });
    return () => {
      cancelled = true;
    };
  }, [isLoggedIn]);

  /**
   * 지금 화면에 쓸 정보 카드. **답이 온 종목과 고른 종목이 같을 때만** 쓴다 — 주문
   * 미리보기와 같은 판단이다. 종목을 바꾼 직후에 이전 답이 남아 있으면 새 종목의 이름
   * 아래에 이전 종목의 지표가 보인다.
   */
  const answeredForSelected =
    answeredInfo?.instrumentId === selectedId ? answeredInfo : null;

  // 리더보드 전체는 브로드캐스트되지 않는다 — 내 순위(`ranking.me`)가 올 때 목록을 다시 읽는다 (`rank.md`).
  const rankAsOf = myRank?.asOfTickSeq;
  useEffect(() => {
    if (!hydrated) return;
    let cancelled = false;
    fetchLeaderboard()
      .then((fresh) => {
        if (!cancelled) setBoard(fresh);
      })
      .catch((error: unknown) =>
        console.warn("리더보드를 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
  }, [hydrated, rankAsOf]);

  /**
   * 공모 국면. **상장 전 종목이 있을 때만 부른다** — 편성 8건이 전부 끝난 뒤에도 틱마다
   * 조회하면 늘 같은 답을 받는다.
   *
   * 틱마다 다시 읽는다. 국면이 마감 틱·상장 틱에 바뀌므로 그 경계를 놓치면 목록이 `청약 중`
   * 을 계속 말한다 — 눌러도 안 되는 것을 된다고 말하는 쪽이 가장 나쁘다 (`ipo.md` 도 경쟁률을
   * 틱에 묶는다).
   *
   * **미인증에는 부르지 않는다** — `사용자` 권한이라 401 이 나가고, 그 화면은 `상장 예정`
   * 까지만 말하면 된다 (ADR-0005).
   */
  const hasUpcoming = instruments.some(
    (instrument) => instrument.listingStatus === "UPCOMING"
  );
  const { data: ipoOfferings } = useLiveQuery(
    fetchIpoOfferings,
    updateOfferings,
    isLoggedIn && hasUpcoming
  );
  const ipoStatus: Record<number, IpoOfferingStatus> | undefined = ipoOfferings
    ? Object.fromEntries(
        ipoOfferings.offerings.map((offering) => [
          offering.instrumentId,
          offering.status,
        ])
      )
    : undefined;

  // 주문 미리보기 — 금액·수수료·부족분의 진실은 서버다 (`ord.md`). 화면의 곱셈은 이것을
  // 못 받았을 때의 어림치로만 남는다.
  //
  // **고른 방향으로 묻는다.** 방향이 탭이라 무엇을 물을지가 분명하다 — 예전에는 두 버튼이
  // 나란해 하나를 골라야 했고, 매수로 고정한 탓에 매도 화면이 수수료 0.15% 가 빠지지 않은
  // 금액을 보여 주고 있었다.
  const trading = clock.phase === "TRADING";
  useEffect(() => {
    if (!trading || !selectedId || !draft || draft.quantity <= 0) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      fetchOrderPreview({
        orderType: draft.kind === "reserved" ? "LIMIT" : "MARKET",
        side: draft.side === "sell" ? "SELL" : "BUY",
        instrumentId: selectedId,
        quantity: draft.quantity,
        limitPrice: draft.limitPrice,
      })
        .then((data) => {
          if (!cancelled)
            setAnswered({ draft, instrumentId: selectedId, data });
        })
        // 미리보기는 표시용이라 실패해도 주문 패널을 잠그지 않는다. 어림치로 되돌아간다.
        .catch((error: unknown) => {
          console.warn("주문 미리보기를 불러오지 못했습니다", error);
        });
    }, PREVIEW_DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [trading, selectedId, draft, previewRevision, price?.close]);

  /**
   * 지금 화면에 쓸 미리보기. **답이 온 입력과 지금 입력이 같을 때만** 쓴다 — 수량을 고치는
   * 동안 이전 수량의 금액이 남아 있으면 화면이 조용히 거짓말을 한다. 다음 답이 올 때까지는
   * 어림치로 돌아가는 편이 낫다.
   */
  const preview =
    answered &&
    draft &&
    answered.instrumentId === selectedId &&
    answered.draft.side === draft.side &&
    answered.draft.kind === draft.kind &&
    answered.draft.quantity === draft.quantity &&
    answered.draft.limitPrice === draft.limitPrice &&
    (draft.kind !== "market" ||
      Number(answered.data.referencePrice) === Number(price?.close))
      ? answered.data
      : null;

  /**
   * 환전으로 **보낼 수 있는** 현금. 방향은 결제 통화가 정한다 — 달러가 모자라면 원화를
   * 보내고, 국내 종목에서 원화가 모자라면 달러를 보낸다. 잠긴 몫은 뺀다 (`acc.md`).
   */
  const sendAvailable =
    preview?.currency === "USD"
      ? Number(valuation?.krwCash ?? 0) - Number(valuation?.lockedKrw ?? 0)
      : Number(valuation?.usdCash ?? 0) - Number(valuation?.lockedUsd ?? 0);

  /**
   * 잔고를 다시 읽는다. **주문·환전 직후에 부른다** — 다음 Tick 방송을 기다리면 그때까지
   * 헤더와 주문 패널이 옛 현금을 말한다.
   */

  /**
   * 주문 전송. 미리보기가 이미 금액·수수료·부족분을 판정했으므로 **여기서 다시 계산하지
   * 않는다** — 수량과 유형만 계약의 이름으로 옮긴다 (`ord.md`).
   *
   * 시장가는 즉시 전량 체결되고 예약은 미체결로 남는다. 응답의 `orderType` 이 어느 쪽인지
   * 결정론적으로 말하므로 그것으로 문구를 가른다.
   */
  /** 주문이 접수된 횟수. 주문 패널이 이 값이 바뀔 때 수량 칸을 비운다. */
  const [placedRevision, setPlacedRevision] = useState(0);

  const submitOrder = useCallback(
    async (order: OrderSubmit) => {
      if (!selectedId || !assertSynced()) return;

      // 계약의 이름으로 한 번만 옮기고 분석도 같은 값을 쓴다. 여기서 따로 만들면 화면이
      // 보내는 것과 보고서가 말하는 것이 갈릴 수 있다.
      const orderType = order.kind === "reserved" ? "LIMIT" : "MARKET";
      const side = order.side === "buy" ? "BUY" : "SELL";
      // 누른 순간이다. `order_result` 와의 차이가 곧 실패율이고, 그 둘과 `instrument_view`
      // 의 비율이 "상세까지 왔는데 주문은 안 한" 사람을 드러낸다.
      trackEvent("order_submit", {
        side,
        order_type: orderType,
        instrument_name: selected?.name ?? "",
        asset_type: selected?.assetType ?? "",
      });

      setPlacing(true);
      try {
        const created = await placeOrder({
          orderType,
          side,
          instrumentId: selectedId,
          quantity: order.quantity,
          limitPrice: order.limitPrice,
        });
        trackEvent("order_result", {
          side,
          order_type: orderType,
          asset_type: selected?.assetType ?? "",
          status: "SUCCESS",
        });

        toast.success(
          created.orderType === "MARKET"
            ? `${order.side === "buy" ? "매수" : "매도"} ${shares(order.quantity)} 체결됐습니다.`
            : `${order.side === "buy" ? "매수" : "매도"} 예약 ${shares(order.quantity)}를 등록했습니다. 폐장까지 유효합니다.`
        );

        // 현금·보유가 방금 바뀌었다. 미리보기도 낡았으므로 같은 입력이라도 다시 묻는다.
        setPreviewRevision((revision) => revision + 1);
        // 접수된 주문의 수량은 칸에 남기지 않는다 (`OrderTicket` 의 `placedRevision`).
        // **성공했을 때만** 올린다 — 실패한 주문은 고쳐서 다시 낼 값이다.
        setPlacedRevision((revision) => revision + 1);
        reloadPositions();
        await reloadValuation();
      } catch (error: unknown) {
        // 잔고 부족·거래 시간 외가 409 로 온다 (`lib/order-api.ts`). 사유는 싣지 않는다 —
        // 서버 문구를 파싱해야 하고, 문구가 바뀌면 보고서가 조용히 갈라진다.
        trackEvent("order_result", {
          side,
          order_type: orderType,
          asset_type: selected?.assetType ?? "",
          status: "FAILED",
        });
        toast.error(
          error instanceof Error ? error.message : "주문에 실패했습니다."
        );
      } finally {
        setPlacing(false);
      }
    },
    // `selected?.name` 이 아니라 객체째 담는다 — 두 속성을 읽는 순간 React Compiler 가
    // `selected` 를 추론해 손으로 적은 목록과 어긋나고, 그러면 이 화면 전체의 최적화가
    // 통째로 꺼진다 (react-hooks/preserve-manual-memoization).
    [selectedId, selected, reloadPositions]
  );

  /**
   * 부족분 환전. 미리보기가 준 요청을 그대로 보낸다 — 여기서 금액을 만들지 않는다.
   *
   * **성공하면 붙들고 있던 매수를 바로 보낸다.** 환전과 주문을 두 번 누르게 두면 그 사이에
   * 틱이 하나 지나 환율·기준가가 바뀌고, 딱 맞춰 바꾼 달러가 다시 모자라진다.
   */
  const exchange = useCallback(
    async (request: ShortfallExchange, andBuy: OrderSubmit | null) => {
      setExchanging(true);
      try {
        await submitExchange(request);
        setAskExchange(null);
        setPreviewRevision((revision) => revision + 1);
        await reloadValuation();
      } catch (error: unknown) {
        toast.error(
          error instanceof Error ? error.message : "환전에 실패했습니다."
        );
        return;
      } finally {
        setExchanging(false);
      }

      // 환전 성공 토스트를 따로 띄우지 않는다 — 곧바로 주문 결과가 뜨므로 토스트 둘이
      // 겹친다. 환전만 하고 끝나는 길은 `마이페이지 > 환전`이다.
      if (andBuy) {
        await submitOrder(andBuy);
        return;
      }
      toast.success("환전했습니다.");
    },
    [submitOrder]
  );

  /**
   * 매수. **부족분이면 주문이 아니라 환전 확인으로 보낸다** — 여기서 막지 않으면 반드시
   * 실패할 주문을 서버까지 보내고 오류로 되돌아온다. 주문 생성의 409 는 청약과 달리 환전
   * 요청을 담지 않으므로(`ord.md`) 그 길로는 확인 모달을 열 수 없다.
   *
   * **미리보기가 아직 없으면 그 자리에서 한 번 묻는다.** 디바운스(300ms) 안에 눌렀거나 앞선
   * 조회가 실패했을 수 있는데, 그때 주문 패널이 매수를 잠가 두면 달러가 0 인 사람에게
   * 환전으로 가는 길이 통째로 사라진다 (`order-ticket.tsx` 의 `canBuy` 와 같은 판단).
   */
  const submitBuy = useCallback(
    async (order: OrderSubmit) => {
      // 여기서도 본다. `submitOrder` 가 마지막 방어선이지만, 그 앞의 미리보기 왕복과 환전
      // 확인 모달까지 태운 뒤에 막으면 사용자가 헛걸음을 두 번 한다.
      if (!selectedId || !assertSynced()) return;
      if (preview) {
        if (preview.shortfallExchange) {
          setAskExchange(order);
          return;
        }
        void submitOrder(order);
        return;
      }

      setPlacing(true);
      try {
        const fresh = await fetchOrderPreview({
          orderType: order.kind === "reserved" ? "LIMIT" : "MARKET",
          side: order.side === "sell" ? "SELL" : "BUY",
          instrumentId: selectedId,
          quantity: order.quantity,
          limitPrice: order.limitPrice,
        });
        // 지금 입력으로 답을 받았으므로 화면의 미리보기도 이것으로 세운다 — 확인 모달이
        // 읽는 것이 이 값이다.
        setAnswered({
          draft: {
            side: order.side,
            kind: order.kind,
            quantity: order.quantity,
            limitPrice: order.limitPrice,
          },
          instrumentId: selectedId,
          data: fresh,
        });
        if (fresh.shortfallExchange) {
          setAskExchange(order);
          return;
        }
      } catch (error: unknown) {
        // 미리보기는 표시용이다. 못 받아도 주문을 막지 않는다 — 판정은 어차피 서버가 한다.
        console.warn("주문 미리보기를 불러오지 못했습니다", error);
      } finally {
        setPlacing(false);
      }

      void submitOrder(order);
    },
    [selectedId, preview, submitOrder]
  );

  /**
   * **자르지 않는다.** 서버 응답은 재생 시작부터 전체이고 (`mkt.md`), 그 전체를 차트에
   * 그대로 준다. 기간 탭은 보이는 창만 옮기므로, `3개월` 에서도 왼쪽으로 끌면 그 앞이
   * 이어진다 — 예전에는 여기서 잘라 넘기는 바람에 잘린 구간이 아예 존재하지 않았다.
   */
  const chartSeries = useMemo(
    () => (series && series.instrumentId === selectedId ? series : null),
    [series, selectedId]
  );

  /**
   * 선택한 종목의 보유 평단가. 미보유면 `null` 이라 차트에 기준선이 서지 않는다.
   * 로그인 전에는 `positions` 자체가 없다 (ADR-0005).
   */
  const avgPrice = useMemo(
    () =>
      positions?.positions.find(
        (position) => position.instrumentId === selectedId
      )?.avgPrice ?? null,
    [positions, selectedId]
  );
  return (
    <div className="flex min-h-0 flex-col gap-3 lg:flex-1">
      {/* **좁은 화면의 한 열을 명시한다.** 적지 않으면 암시적 열이 `auto` 로 잡혀 항목의
          최소 콘텐츠 폭까지 자란다. 넓은 창을 줄였을 때가 문제인데, 브라우저가 이미 잡아 둔
          최소 폭(넓은 화면의 카드 폭)을 다시 계산하지 않아 열이 684px 에 굳는다 — 화면을
          떠났다 오면 멀쩡해지는 탓에 찾기 어렵다. `minmax(0,1fr)` 은 0 까지 줄어도 좋다고
          말해 그 바닥을 없앤다. */}
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)] gap-3 lg:grid-cols-[19rem_minmax(0,1fr)_20rem] lg:grid-rows-[minmax(0,1fr)]">
        {/* ── 종목 목록 ── */}
        <Card
          size="sm"
          data-tour="instrument-list"
          className="min-h-0 max-lg:h-[32rem]"
        >
          <CardContent className="flex min-h-0 flex-1 flex-col">
            <InstrumentListV2
              // 로그인 상태가 바뀌면 로컬 선택(관심 종목·필터)을 통째로 새로 시작한다 —
              // 이전 사용자의 선택이 다음 사람에게 남지 않아야 한다 (ADR-0005).
              key={isLoggedIn ? "user" : "guest"}
              instruments={instruments}
              prices={prices}
              selectedId={selectedId}
              onSelect={selectInstrument}
              isLoggedIn={isLoggedIn}
              ipoStatus={ipoStatus}
              // `보유` 필터의 재료다. **없으면 그 필터가 나오지 않는다** — 미인증이거나
              // 보유가 비면 v1 과 같은 목록이다 (`instrument-list.v2.tsx`).
              positions={positions?.positions}
              className="min-h-0 flex-1"
            />
          </CardContent>
        </Card>

        {/* ── 상세 + 주문 ── */}
        <div className="flex min-h-0 flex-col gap-3">
          {/* 스크롤 모양은 종목 목록·리더보드와 같은 `ScrollArea` 다. 네이티브
              `overflow-y-auto` 를 쓰면 이 카드만 브라우저 기본 스크롤바가 나온다.
              카드가 주던 세로 간격은 뷰포트 안으로 들어가므로 여기서 다시 준다. */}
          {/* **좌우 여백만 한 단 넓다.** 이 카드는 화면에서 가장 큰 면인데 옆 카드와 같은
              12px 를 쓰면 종목명·차트·종목 정보가 전부 테두리에 붙는다. 세로 리듬은
              `size="sm"` 그대로 두어 옆 카드들과 줄이 맞는다. */}
          <Card
            size="sm"
            data-tour="instrument-detail"
            className="min-h-0 flex-1 [&_[data-slot=card-content]]:px-[22px]"
          >
            {selected ? (
              <ScrollArea className="min-h-0 flex-1">
                <div className="flex flex-col gap-(--card-spacing)">
                  <CardContent className="flex flex-wrap items-start justify-between gap-3">
                    <div className="flex flex-col gap-1">
                      <h1 className="text-2xl font-semibold tracking-tight">
                        {selected.name}
                      </h1>
                      <span className="text-sm text-muted-foreground">
                        {instrumentTags(selected)}
                      </span>
                    </div>
                    <div className="flex flex-col items-end gap-0.5">
                      <span
                        className="text-3xl font-semibold tabular-nums"
                        data-testid="selected-price"
                      >
                        {price
                          ? localPrice(
                              price.close,
                              selected.currency,
                              selected.priceScale
                            )
                          : "—"}
                      </span>
                      {price &&
                        (price.changeRate === null ? (
                          <Badge variant="outline">휴장</Badge>
                        ) : (
                          <PriceChange value={price.changeRate} />
                        ))}
                      {price && selected.currency === "USD" && (
                        <span className="text-xs text-muted-foreground tabular-nums">
                          ≈ {krw(price.closeKrw)}
                        </span>
                      )}
                    </div>
                  </CardContent>

                  <CardContent className="flex flex-wrap items-center gap-2">
                    {/* 봉 주기와 기간은 다른 축이다 — 앞은 며칠을 한 점으로 묶는가,
                        뒤는 그 점들을 얼마만큼 보는가. 그래서 탭도 따로 둔다. */}
                    <Tabs
                      value={interval}
                      onValueChange={(value) =>
                        setInterval(value as ChartInterval)
                      }
                      aria-label="봉 주기"
                    >
                      <TabsList variant="line">
                        {CHART_INTERVALS.map((item) => (
                          <TabsTrigger key={item.value} value={item.value}>
                            {item.label}
                          </TabsTrigger>
                        ))}
                      </TabsList>
                    </Tabs>
                    <Tabs
                      value={range}
                      onValueChange={(value) => setRange(value as ChartRange)}
                      aria-label="차트 기간"
                      className="ml-auto"
                    >
                      <TabsList variant="line">
                        {RANGES.map((item) => (
                          <TabsTrigger key={item.value} value={item.value}>
                            {item.label}
                          </TabsTrigger>
                        ))}
                      </TabsList>
                    </Tabs>
                  </CardContent>

                  {/* 스크롤 영역 안이라 높이를 `flex-1` 로 받을 수 없다 — 뷰포트 안의
                      래퍼는 높이가 auto 라 나눠 줄 높이가 없고, 차트의 `h-full` 이 0 이
                      되어 캔버스가 사라진다. 그래서 여기서 높이를 못박는다. */}
                  <CardContent className="h-56">
                    {chartSeries ? (
                      <PriceChart
                        series={chartSeries}
                        changeRate={price?.changeRate}
                        avgPrice={avgPrice}
                        range={range}
                        interval={interval}
                        className="h-full"
                      />
                    ) : !hydrated ? (
                      <div className="flex h-full w-full flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-border p-4 text-center">
                        <LineChart className="size-8 text-muted-foreground/40" />
                        <p className="text-sm font-medium text-muted-foreground">
                          1일차 개장 준비 중입니다
                        </p>
                        <span className="text-xs text-muted-foreground">
                          개장 후 실시간 시세 차트가 표시됩니다
                        </span>
                      </div>
                    ) : (
                      <Skeleton className="h-full w-full" />
                    )}
                  </CardContent>

                  <CardContent className="flex flex-col gap-2 border-t border-border pt-3">
                    <InstrumentInfoCard
                      info={answeredForSelected?.info ?? null}
                      loading={answeredForSelected === null}
                      failed={
                        answeredForSelected !== null &&
                        answeredForSelected.info === null
                      }
                      isLoggedIn={isLoggedIn}
                      criteria={criteria}
                    />
                  </CardContent>

                  <CardContent className="border-t border-border pt-3">
                    <InstrumentNews instrumentId={selected.instrumentId} />
                  </CardContent>
                </div>
              </ScrollArea>
            ) : (
              <CardContent className="py-10 text-center text-sm text-muted-foreground">
                종목을 고르면 여기 차트가 뜬다.
              </CardContent>
            )}
          </Card>

          {selected && price ? (
            <OrderTicket
              key={selected.instrumentId}
              data-tour="order-ticket"
              lastPrice={Number(price.close)}
              currency={selected.currency}
              priceScale={selected.priceScale}
              // **잠긴 몫을 뺀 주문 가능액이다.** `krwCash`·`usdCash` 는 예약·청약이 잡고
              // 있는 것까지 더한 총 현금이라(`AccountSummaryResponse`), 그대로 넘기면
              // 미리보기가 오기 전의 `최대 가능` 이 잠긴 돈까지 쓸 수 있다고 말한다 —
              // 서버의 `availableCash` 는 이미 뺀 값이다.
              cash={
                valuation
                  ? selected.currency === "KRW"
                    ? Number(valuation.krwCash) - Number(valuation.lockedKrw)
                    : Number(valuation.usdCash) - Number(valuation.lockedUsd)
                  : 0
              }
              // 부족분을 채울 **반대쪽** 통화. `krwCash` 는 잠긴 금액을 포함한 총 현금이라
              // (`AccountSummaryResponse`), 예약·청약이 점유한 몫을 빼야 환전에 쓸 수 있는
              // 금액이 된다.
              exchangeableCash={
                valuation
                  ? selected.currency === "KRW"
                    ? Number(valuation.usdCash) - Number(valuation.lockedUsd)
                    : Number(valuation.krwCash) - Number(valuation.lockedKrw)
                  : 0
              }
              usdKrwRate={
                tickUsdKrwRate != null ? Number(tickUsdKrwRate) : null
              }
              holdingQuantity={
                positions?.positions.find(
                  (position) => position.instrumentId === selectedId
                )?.quantity ?? 0
              }
              feeRate={clock.feeFree ? 0 : undefined}
              feeFreeSeconds={clock.feeFreeSeconds}
              marketOpen={!price.marketClosed && trading}
              tradingOpen={trading}
              synced={synced}
              isLoggedIn={isLoggedIn}
              preview={preview}
              placedRevision={placedRevision}
              onDraftChange={setDraft}
              submitting={placing}
              onSubmit={(order) => {
                // 매수는 부족분을 먼저 판정해야 한다 (`submitBuy`). 매도는 현금을 쓰지
                // 않으므로 부족분이 생길 수 없다 (`ord.md`).
                if (order.side === "buy") {
                  void submitBuy(order);
                  return;
                }
                void submitOrder(order);
              }}
            />
          ) : (
            <Skeleton data-tour="order-ticket" className="h-40 rounded-lg" />
          )}
        </div>

        {/* ── 리더보드 + 채팅 ── */}
        <div className="flex min-h-0 flex-col gap-3">
          <Leaderboard
            board={board}
            me={myRank}
            myNickname={nickname}
            data-tour="leaderboard"
            className="max-h-[19rem] min-h-0 flex-1"
          />
          <Card size="sm" className="flex min-h-48 flex-1 flex-col">
            <CardContent className="flex min-h-0 flex-1 flex-col">
              <ChatPanel isLoggedIn={isLoggedIn} className="min-h-0 flex-1" />
            </CardContent>
          </Card>
        </div>
      </div>

      {/* 잔고와 환율은 여기 있고 주문 패널에는 없다. 그래서 모달도 여기서 그린다. */}
      <ShortfallDialog
        preview={preview}
        open={askExchange !== null}
        onOpenChange={(next) => !next && setAskExchange(null)}
        rate={
          valuation?.usdKrwRate != null ? Number(valuation.usdKrwRate) : null
        }
        sendAvailable={sendAvailable}
        pending={exchanging}
        onConfirm={() =>
          preview?.shortfallExchange &&
          void exchange(preview.shortfallExchange, askExchange)
        }
      />

      <NewsTicker data-tour="news-ticker" />

      <MarketTour isLoggedIn={isLoggedIn} />
    </div>
  );
}
