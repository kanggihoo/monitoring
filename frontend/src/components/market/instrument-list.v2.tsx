"use client";

import { useState } from "react";
import { Star } from "lucide-react";
import { toast } from "sonner";

import { useLoginPrompt } from "@/components/login-prompt";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { apiErrorMessage, apiFetch } from "@/lib/api";
import { compactKrw, gameDay, localPrice } from "@/lib/format";
import type {
  InstrumentMeta,
  IpoOfferingStatus,
  PositionItem,
  PriceEntry,
} from "@/lib/market-types";
import { cn, FOCUS_RING, TAB_LIST, TAB_TRIGGER } from "@/lib/utils";

import { instrumentTags } from "./labels";
import { PriceChange } from "./price-change";

type Category = InstrumentMeta["category"] | "ALL";
type Filter = "ALL" | "WATCHED" | "ETF" | "HELD";
type SortKey = "CHANGE" | "VOLUME" | "NAME";
type Sort = "DEFAULT" | SortKey;

const CATEGORIES: { value: Category; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "NASDAQ", label: "미국" },
  { value: "KRX", label: "국내" },
  { value: "CRYPTO", label: "코인" },
];

/** 별과 관심 필터가 같은 제약을 말한다. 문구가 갈리면 같은 제약이 둘로 보인다. */
const WATCHLIST_LOGIN_MESSAGE = "관심 종목은 로그인 후에 담을 수 있습니다";

/** 관심종목은 계좌가 아니라 사람에 붙으므로 경로가 사용자 아래다 (`unv.md`). */
const WATCHLIST_PATH = "/api/v1/users/me/watchlist";

/** 서버가 이유를 말하지 않았을 때의 기본 안내. */
const WATCHLIST_FAILED = "관심 종목을 바꾸지 못했습니다";

/**
 * 한 종목의 관심 여부를 정해진 방향으로 놓는 갱신 함수.
 *
 * **토글이 아니라 방향을 받는다** — 낙관적 갱신과 그 되돌리기가 같은 함수를 쓰는데, 토글이면
 * 두 번 뒤집는 사이에 다른 클릭이 끼었을 때 되돌리기가 엉뚱한 값을 만든다.
 */
const flip =
  (instrumentId: number, present: boolean) => (current: Set<number>) => {
    const next = new Set(current);
    if (present) {
      next.add(instrumentId);
    } else {
      next.delete(instrumentId);
    }
    return next;
  };

/** 아직 상장 전이라 그날 값이 하나도 없는 종목. 줄에는 `상장 예정` 이라고 뜬다. */
function upcoming(instrument: InstrumentMeta) {
  return instrument.listingStatus === "UPCOMING" ? 1 : 0;
}

/**
 * 그날 등락을 말할 수 없는 종목. `휴장` 배지가 뜨는 쪽이다.
 *
 * **거래대금에는 쓰지 않는다** — 휴장이어도 그날 오간 돈은 그 자체로 값이라 다른 종목과
 * 견줄 수 있다. 등락률만 비교할 것이 없다.
 */
function resting(
  prices: Record<number, PriceEntry>,
  instrument: InstrumentMeta
) {
  return prices[instrument.instrumentId]?.marketClosed ? 1 : 0;
}

/** 정렬용 거래대금. 이유는 v1 과 같다 (`instrument-list.tsx`). */
function tradedAmount(
  prices: Record<number, PriceEntry>,
  instrument: InstrumentMeta
) {
  const amount = prices[instrument.instrumentId]?.tradedAmountKrw;
  return amount === undefined ? -1 : Number(amount);
}

/** 청약을 지금 넣을 수 있는 유일한 국면. 이 라벨만 강조 배지를 쓴다. */
const IPO_OPEN_LABEL = "청약 중";

/**
 * 공모 국면을 목록 배지 문구로 바꾼다.
 *
 * **모르면 `상장 예정` 이다.** 국면은 `사용자` 권한(`ipo.md`)이라 미인증에게는 오지 않고,
 * 로그인해도 조회가 실패할 수 있다. 그때 `청약 중` 이라고 적으면 눌러도 안 되는 것을
 * 된다고 말하게 되므로, **모른다는 사실을 지어내지 않는 쪽**으로 떨어뜨린다.
 *
 * `DRAWING` 은 수 밀리초짜리 국면이라 화면에 잡히는 일이 드물지만 문구는 둔다 — 없으면
 * 그 순간에 배지가 사라진다.
 */
function ipoStatusLabel(status: IpoOfferingStatus | undefined) {
  switch (status) {
    case "OPEN":
      return IPO_OPEN_LABEL;
    case "DRAWING":
      return "추첨 중";
    case "ANNOUNCED":
      return "청약 마감";
    default:
      return "상장 예정";
  }
}

/**
 * 두 칸이 무엇인지 말하는 머리글. **같은 자리에 같은 모양(`▲ +13.34%`)으로 서는 두 값이
 * 서로 다른 값이라서 둔다** — 전체 목록의 등락은 전일 대비고, 보유 목록의 등락은 내
 * 평단 대비 수익률이다. 줄마다 이름표를 달면 목록이 두꺼워지므로, 목록 밖에 한 줄만 둔다
 * (업비트도 탭마다 머리글을 갈아 끼운다).
 */
const HEADINGS: Record<Filter, [string, string]> = {
  ALL: ["종목", "현재가 · 전일대비"],
  WATCHED: ["종목", "현재가 · 전일대비"],
  ETF: ["종목", "현재가 · 전일대비"],
  HELD: ["종목 · 평단", "평가금 · 수익률"],
};

/**
 * 누를 수 있는 정렬 기준. `거래대금` 이 무엇을 세는 값인지는 v1 과 같다
 * (`instrument-list.tsx`).
 */
const SORT_KEYS: { value: SortKey; label: string }[] = [
  { value: "NAME", label: "이름" },
  { value: "CHANGE", label: "등락률" },
  { value: "VOLUME", label: "거래대금" },
];

/**
 * 처음 눌렀을 때의 방향. **기준마다 다르다** — 이름은 가나다순이 먼저지만, 등락률과
 * 거래대금은 큰 값을 보려고 누른다. 전부 오름차순으로 시작하면 첫 클릭이 언제나 헛걸음이
 * 된다.
 */
const SORT_DESC_FIRST: Record<SortKey, boolean> = {
  NAME: false,
  CHANGE: true,
  VOLUME: true,
};

/**
 * 유니버스 28종 목록 시안 (v2). v1 과 같고 **`보유` 필터가 하나 붙는다** — 그 안에서는
 * 같은 두 칸이 시세 대신 평단·평가금·수익률을 말한다. 계좌로 건너가지 않고 내 자리를
 * 확인하려는 것이지, 자산 현황을 여기로 옮기려는 것이 아니다 (수량·손익·비중은 없다).
 *
 * **줄 높이는 v1 과 같다.** 보유 줄만 두꺼우면 목록이 세로로 들쭉날쭉해지고, 좁은 기둥에서
 * 그 한 줄이 화면의 3분의 1을 먹는다. 그래서 칸을 더하지 않고 같은 칸의 내용을 바꾼다.
 *
 * 시세는 Tick 마다 한 번에 바뀌고, 메타는 서버 컴포넌트가 준다.
 *
 * **미인증은 별을 누를 수 없고 로그인 안내를 받는다** (ADR-0005). 아무 일도 일어나지 않으면
 * 고장으로 오해한다.
 *
 * <b>로그아웃하면 담아 둔 선택도 사라져야 한다</b> — 다음 사람에게 이전 사용자의 관심 종목이
 * 남지 않아야 한다. 그 정리는 여기서 하지 않고 <b>부모가 로그인 상태를 `key` 로 주어</b>
 * 로컬 상태를 통째로 새로 시작한다 (`market-screen.tsx`). 이펙트에서 지우면 값이 바뀔 때마다
 * 연쇄 렌더가 된다.
 *
 * <b>관심 종목은 서버에 남는다</b> (`PUT/DELETE /users/me/watchlist/{id}`, `unv.md`). 초기
 * 상태는 종목 목록 응답의 `watched` 이므로 이 화면이 워치리스트를 따로 부르지 않는다.
 */
export function InstrumentListV2({
  instruments,
  prices,
  selectedId,
  onSelect,
  isLoggedIn = false,
  ipoStatus,
  positions = [],
  opening,
  className,
}: {
  instruments: InstrumentMeta[];
  prices: Record<number, PriceEntry>;
  selectedId: number | null;
  onSelect: (instrumentId: number) => void;
  isLoggedIn?: boolean;
  /**
   * 종목별 공모 국면. **없어도 목록은 그려진다** — 미인증이거나 조회가 실패하면 상장 전
   * 종목이 `상장 예정` 까지만 말한다 (`ipoStatusLabel`).
   */
  ipoStatus?: Record<number, IpoOfferingStatus>;
  /**
   * 보유 포지션 (`GET /api/v1/accounts/me/positions`). **없으면 v1 과 같은 목록이다** —
   * 미인증이거나 보유가 비면 `보유` 필터가 나오지 않는다.
   */
  positions?: PositionItem[];
  /**
   * 개장 직후 값 자리를 설명하는 한 줄 (`opening-notice.v2.tsx` 의 `strip`).
   *
   * **`보유` 필터와는 별개 시안이다.** 둘이 같은 파일에 있을 뿐 서로를 필요로 하지 않는다 —
   * 넘기지 않으면 아무것도 그리지 않으므로, 한쪽만 채택하고 다른 쪽을 버려도 된다.
   */
  opening?: React.ReactNode;
  className?: string;
}) {
  const promptLogin = useLoginPrompt();
  const held = new Map(
    positions.map((position) => [position.instrumentId, position])
  );
  const [category, setCategory] = useState<Category>("ALL");
  const [filter, setFilter] = useState<Filter>("ALL");
  const [sort, setSort] = useState<Sort>("DEFAULT");
  /** 지금 기준의 방향. `sort` 가 `DEFAULT` 면 쓰이지 않는다. */
  const [desc, setDesc] = useState(false);

  /**
   * 머리글을 한 번 누를 때 일어나는 일. 같은 기준을 다시 누르면 **오름↔내림**만 뒤집는다.
   *
   * 유니버스 순서(`DEFAULT`)는 아무것도 누르지 않은 처음뿐이다 — 한 번 정렬한 뒤로는
   * 돌아가지 않는다.
   */
  const pressSort = (key: SortKey) => {
    if (sort !== key) {
      setSort(key);
      setDesc(SORT_DESC_FIRST[key]);
      return;
    }
    setDesc(!desc);
  };
  /**
   * 이름 검색어. **분류·필터와 곱해진다** — 검색이 다른 조건을 무시하고 전체를 뒤지면,
   * `코인` 탭에서 찾은 종목을 눌렀을 때 목록이 말한 갈래와 다른 것이 열린다.
   */
  const [query, setQuery] = useState("");
  // 초기 상태는 목록 응답이 실어 온 값이다 — 워치리스트를 따로 부르지 않는다.
  const [watched, setWatched] = useState<Set<number>>(
    () =>
      new Set(
        instruments
          .filter((instrument) => instrument.watched)
          .map((instrument) => instrument.instrumentId)
      )
  );

  /**
   * 별 하나를 뒤집는다. **화면을 먼저 바꾸고 서버에 보낸다** — 별은 누른 즉시 반응해야 하고,
   * 응답을 기다리면 틱마다 다시 그려지는 목록에서 눌린 별이 한 박자 늦게 채워진다.
   *
   * 실패하면 되돌린다. 눌린 채로 두면 다음 조회에서 조용히 풀려, 담았다고 믿은 종목이
   * 사라진 것처럼 보인다. 서버가 멱등하므로(`unv.md`) 더블 클릭이나 재시도가 상태를
   * 뒤집지 않는다 — 마지막으로 누른 방향이 그대로 남는다.
   */
  const toggleWatch = async (instrumentId: number) => {
    if (!isLoggedIn) {
      promptLogin(WATCHLIST_LOGIN_MESSAGE);
      return;
    }

    const adding = !watched.has(instrumentId);
    setWatched(flip(instrumentId, adding));

    try {
      const response = await apiFetch(`${WATCHLIST_PATH}/${instrumentId}`, {
        method: adding ? "PUT" : "DELETE",
      });
      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, WATCHLIST_FAILED));
      }
    } catch (error) {
      setWatched(flip(instrumentId, !adding));
      toast.error(error instanceof Error ? error.message : WATCHLIST_FAILED);
    }
  };

  /**
   * 등락 칸이 지금 말하는 값. **필터를 따라 갈린다** — 전체 목록에서는 전일 대비지만,
   * `보유` 안에서는 내 평단 대비 수익률이다 (`HEADINGS`). 화면이 수익률을 적어 두고
   * 전일 대비로 줄을 세우면, 정렬한 뒤의 순서가 보이는 숫자와 어긋난다.
   */
  const changeRate = (instrument: InstrumentMeta) =>
    filter === "HELD"
      ? (held.get(instrument.instrumentId)?.returnRate ?? -Infinity)
      : (prices[instrument.instrumentId]?.changeRate ?? -Infinity);

  /**
   * 검색어. **앞뒤 공백을 떼고 소문자로 맞춘다** — 붙여넣기에 공백이 섞이고, 종목명에
   * 라틴 문자가 섞여 있어 대소문자를 구별하면 `qqq` 로 `QQQ` 를 찾지 못한다.
   */
  const keyword = query.trim().toLowerCase();

  const visible = instruments
    .filter((i) => category === "ALL" || i.category === category)
    .filter((i) => keyword === "" || i.name.toLowerCase().includes(keyword))
    .filter((i) =>
      filter === "ALL"
        ? true
        : filter === "WATCHED"
          ? watched.has(i.instrumentId)
          : filter === "HELD"
            ? held.has(i.instrumentId)
            : i.assetType === "ETF" || i.assetType === "INVERSE_ETF"
    )
    .sort((a, b) => {
      // **값이 없는 종목만 바닥에 둔다.** 방향을 뒤집어도 따라 올라오지 않는다 — 견줄
      // 값이 없는 줄이 오름차순에서 맨 위를 차지하면 정작 보려던 종목이 그 아래로 밀린다.
      //
      // 무엇이 "값이 없는" 것인지는 기준마다 다르다. 상장 전은 셋 다 없지만, 휴장은
      // 등락만 없고 그날 오간 돈은 있다. 이름은 어느 쪽에도 그대로 있다.
      if (sort === "CHANGE" || sort === "VOLUME") {
        const later = upcoming(a) - upcoming(b);
        if (later !== 0) return later;
      }
      // 보유 목록의 등락 칸은 전일 대비가 아니라 내 평단 대비 수익률이다. 그 값은 휴장
      // 종목에도 있으므로 여기서는 내리지 않는다.
      if (sort === "CHANGE" && filter !== "HELD") {
        const rest = resting(prices, a) - resting(prices, b);
        if (rest !== 0) return rest;
      }

      // **오름차순 한 벌만 적고 방향은 마지막에 뒤집는다.** 기준마다 두 벌을 적으면
      // 한쪽만 고치는 일이 생긴다.
      const gap =
        sort === "NAME"
          ? a.name.localeCompare(b.name, "ko")
          : sort === "CHANGE"
            ? changeRate(a) - changeRate(b)
            : sort === "VOLUME"
              ? // 문자열 금액을 수로 바꾸는 이유는 v1 과 같다 (`instrument-list.tsx`).
                tradedAmount(prices, a) - tradedAmount(prices, b)
              : 0;

      // 동점은 유니버스 순서로 푼다. 방향을 뒤집어도 이 순서는 그대로다 — 같은 값끼리
      // 자리를 바꾸면 틱마다 줄이 흔들린다.
      if (gap !== 0) return desc ? -gap : gap;
      return a.instrumentId - b.instrumentId;
    });

  return (
    <div className={cn("flex min-h-0 flex-col gap-2", className)}>
      <Tabs
        value={category}
        onValueChange={(value) => setCategory(value as Category)}
        aria-label="종목 분류"
      >
        <TabsList
          className={cn(TAB_LIST, "w-full group-data-horizontal/tabs:h-[37px]")}
        >
          {CATEGORIES.map((item) => (
            <TabsTrigger
              key={item.value}
              value={item.value}
              className={TAB_TRIGGER}
            >
              {item.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {/* **검색은 분류 바로 아래다.** 목록을 좁히는 수단(분류 → 검색 → 필터 → 정렬)이
          위에서 아래로 한 줄기로 서야, 고른 것이 왜 이만큼 남았는지가 읽힌다.

          `type="search"` 다 — 브라우저가 지우기 버튼과 모바일 키보드의 `검색` 키를 준다.
          같은 것을 직접 만들면 플랫폼마다 다른 자리에 다른 모양으로 붙는다. */}
      <Input
        type="search"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder="종목 검색"
        aria-label="종목 검색"
        className="h-9"
      />

      <div className="flex items-center gap-1.5">
        <Chip active={filter === "ALL"} onClick={() => setFilter("ALL")}>
          전체
        </Chip>
        <Chip
          active={filter === "WATCHED"}
          onClick={() =>
            isLoggedIn
              ? setFilter("WATCHED")
              : promptLogin(WATCHLIST_LOGIN_MESSAGE)
          }
        >
          관심 {watched.size > 0 && watched.size}
        </Chip>
        {/* 보유가 없으면 누를 이유가 없는 칩이다. 미인증도 여기로 걸러진다. */}
        {held.size > 0 && (
          <Chip active={filter === "HELD"} onClick={() => setFilter("HELD")}>
            보유 {held.size}
          </Chip>
        )}
        <Chip active={filter === "ETF"} onClick={() => setFilter("ETF")}>
          ETF·인버스
        </Chip>
      </div>

      {/* 정렬 줄. **드롭다운을 대신한다** — 무엇으로 서 있는지 열고 확인하는 대신 줄 위에
          그대로 적힌다. 고른 기준에만 방향 표시를 두어, 나머지 둘이 지금 정렬이 아니라는
          것이 한눈에 보이게 한다. */}
      <div
        role="group"
        aria-label="정렬"
        // 줄 전체가 한 면이다. 버튼마다 면을 주면 셋이 따로 떠 위 칩 줄과 구별되지
        // 않는다 — 여기서 나누는 것은 세 기준이 아니라 "정렬 줄" 과 그 아래 목록이다.
        className="flex items-center rounded-md bg-muted px-1 py-0.5"
      >
        {SORT_KEYS.map((item) => {
          const active = sort === item.value;
          return (
            <button
              key={item.value}
              type="button"
              onClick={() => pressSort(item.value)}
              aria-label={
                active
                  ? `${item.label} ${desc ? "내림차순" : "오름차순"} 정렬`
                  : `${item.label} 정렬`
              }
              data-direction={active ? (desc ? "desc" : "asc") : "none"}
              className={cn(
                // 글자는 위 칩 줄과 같은 `text-xs` 다. 이 줄만 작으면 목록의 머리글이
                // 아니라 각주처럼 읽힌다.
                "inline-flex h-7 flex-1 items-center justify-center gap-1 text-xs transition-colors",
                FOCUS_RING,
                active ? "font-medium text-foreground" : "text-muted-foreground"
              )}
            >
              {item.label}
              {/* 두 삼각형을 늘 함께 세우고 **고른 쪽만 짙게 한다.** 방향 하나만 띄우면
                  표시가 생길 때 칸이 넓어져 옆 두 개가 밀리고, 누르면 무엇이 일어나는지도
                  누르기 전에는 보이지 않는다. */}
              <span
                aria-hidden
                className="flex flex-col text-[7px] leading-[1.1]"
              >
                <span
                  className={
                    active && !desc
                      ? "text-foreground"
                      : "text-muted-foreground/40"
                  }
                >
                  ▲
                </span>
                <span
                  className={
                    active && desc
                      ? "text-foreground"
                      : "text-muted-foreground/40"
                  }
                >
                  ▼
                </span>
              </span>
            </button>
          );
        })}
      </div>

      <div className="flex items-center justify-between px-2.5 text-xs text-muted-foreground">
        <span>{HEADINGS[filter][0]}</span>
        <span>{HEADINGS[filter][1]}</span>
      </div>

      {/* 개장 직후에만 뜬다. **목록을 덮지 않고 위에 한 줄만 얹는다** — 이름·갈래·관심
          별은 첫 종가와 무관하게 이미 제 값이라, 가리면 볼 수 있던 것을 못 보게 된다
          (`opening-notice.v2.tsx`). */}
      {opening}

      <ScrollArea className="min-h-0 flex-1">
        <ul aria-label="종목 목록" className="space-y-1 py-1 pr-3.5 pl-1">
          {visible.length === 0 && (
            <li className="py-8 text-center text-xs text-muted-foreground">
              해당하는 종목이 없습니다.
            </li>
          )}
          {visible.map((instrument) => {
            const price = prices[instrument.instrumentId];
            const selected = instrument.instrumentId === selectedId;
            const isWatched = watched.has(instrument.instrumentId);
            const upcoming = instrument.listingStatus === "UPCOMING";
            // **보유 정보는 `보유` 필터 안에서만 쓴다.** 전체 목록은 지금 얼마인지로
            // 고르는 자리라 시세가 그 칸의 주인이고, 두 벌을 한 줄에 같이 세우면
            // 다섯 수가 붙어 아무것도 안 읽힌다.
            const position =
              filter === "HELD" ? held.get(instrument.instrumentId) : undefined;
            const ipoLabel = ipoStatusLabel(
              ipoStatus?.[instrument.instrumentId]
            );
            return (
              <li
                key={instrument.instrumentId}
                className={cn(
                  "group flex items-center gap-2 rounded-lg px-2.5 py-2 transition-colors",
                  // 선택·호버는 무채색이다. 시안은 파란 면(#EAF2FB)을 쓰지만 이 프로젝트는
                  // 파랑을 하락에 쓰므로, 하락률이 같은 줄에 놓이는 이 목록에서는 혼동된다
                  // (DESIGN.md · docs/CONTEXT.md).
                  selected
                    ? "bg-accent text-accent-foreground"
                    : "text-foreground hover:bg-muted/70"
                )}
              >
                <button
                  type="button"
                  aria-pressed={isWatched}
                  aria-label={
                    !isLoggedIn
                      ? "관심 종목에 넣기 · 로그인 필요"
                      : isWatched
                        ? "관심 종목에서 빼기"
                        : "관심 종목에 넣기"
                  }
                  onClick={() => toggleWatch(instrument.instrumentId)}
                  className={cn(
                    FOCUS_RING,
                    "flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground/60 transition-colors hover:bg-background/80 hover:text-foreground"
                  )}
                >
                  <Star
                    className={cn(
                      "size-3.5",
                      isWatched && "fill-current text-foreground"
                    )}
                    aria-hidden
                  />
                </button>
                <button
                  type="button"
                  aria-pressed={selected}
                  onClick={() => onSelect(instrument.instrumentId)}
                  className={cn(
                    FOCUS_RING,
                    "flex min-w-0 flex-1 items-center gap-2 py-1 text-left"
                  )}
                >
                  <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="truncate text-sm font-medium text-foreground">
                      {instrument.name}
                    </span>
                    {/* **한 줄로 자른다.** 업종이 붙어 마디가 셋이 되면서
                        `국내 · 주식 · 커뮤니케이션 서비스` 처럼 길어졌고, 자르지 않으면
                        좁은 기둥에서 줄바꿈이 나 행 높이가 종목마다 달라진다. 위 종목명
                        줄이 같은 이유로 이미 잘린다. */}
                    <span
                      className={cn(
                        "truncate text-2xs tabular-nums",
                        selected
                          ? "text-foreground-subtle"
                          : "text-muted-foreground"
                      )}
                    >
                      {position
                        ? localPrice(
                            position.avgPrice,
                            position.currency,
                            instrument.priceScale
                          )
                        : instrumentTags(instrument)}
                    </span>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-0.5">
                    {position ? (
                      <>
                        {/* **접어서 낸다.** 이 칸은 `shrink-0` 이라 금액이 길어지면
                            그만큼을 왼쪽 이름 칸에서 가져간다 — `48,251,000,000원` 이면
                            360px 에서 종목명이 먼저 잘려, 정작 어느 종목인지가 안 보인다.
                            접은 값은 길이가 `482.5억원` 에서 멎으므로 칸이 이름을 밀어낼
                            수 없다.

                            훑어보는 자리라 접어도 되는 값이다 — 이 필터는 계좌로
                            건너가지 않고 내 자리를 확인하려는 것이고, 쉼표가 든 정확한
                            금액은 내 계좌가 그대로 보여준다 (`compactKrw`). */}
                        <span className="text-sm text-foreground tabular-nums">
                          {compactKrw(position.valuationKrw)}
                        </span>
                        <PriceChange
                          value={position.returnRate}
                          className={cn(
                            "text-xs",
                            selected &&
                              position.returnRate === 0 &&
                              "text-foreground-subtle"
                          )}
                        />
                      </>
                    ) : upcoming ? (
                      // **가격 자리에 `—` 를 두지 않는다.** 상장 전에는 종가가 없는 것이
                      // 정상인데, 다른 줄과 같은 자리에 같은 빗금이 서면 "값을 못 받았다"
                      // 로 읽힌다. 상태와 예정일이 그 자리를 대신한다.
                      <>
                        <Badge
                          variant={
                            ipoLabel === IPO_OPEN_LABEL ? "default" : "outline"
                          }
                          className="h-4 px-1 text-2xs"
                        >
                          {ipoLabel}
                        </Badge>
                        {instrument.listedGameDate && (
                          <span
                            className={cn(
                              "text-2xs tabular-nums",
                              selected
                                ? "text-foreground-subtle"
                                : "text-muted-foreground"
                            )}
                          >
                            {gameDay(instrument.listedGameDate)} 상장
                          </span>
                        )}
                      </>
                    ) : (
                      <>
                        <span className="text-sm text-foreground tabular-nums">
                          {price
                            ? localPrice(
                                price.close,
                                instrument.currency,
                                instrument.priceScale
                              )
                            : "—"}
                        </span>
                        {price ? (
                          price.changeRate === null ? (
                            <Badge
                              variant="outline"
                              className="h-4 px-1 text-2xs"
                            >
                              휴장
                            </Badge>
                          ) : (
                            <PriceChange
                              value={price.changeRate}
                              className={cn(
                                "text-xs",
                                selected &&
                                  price.changeRate === 0 &&
                                  "text-foreground-subtle"
                              )}
                            />
                          )
                        ) : null}
                      </>
                    )}
                  </div>
                </button>
              </li>
            );
          })}
        </ul>
      </ScrollArea>
    </div>
  );
}

function Chip({
  active,
  children,
  onClick,
}: {
  active: boolean;
  children: React.ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        FOCUS_RING,
        // 모서리는 위 분류 탭의 알약과 같은 값이다 (`TAB_TRIGGER` 의 10px) — 두 줄이
        // 나란히 서므로 한쪽만 완전한 원형이면 다른 줄에서 옮겨 온 것처럼 보인다.
        "h-7 rounded-[10px] border px-3 text-xs whitespace-nowrap",
        // **선택 표시가 위 분류 탭보다 약하다.** 칩은 탭이 고른 분류 **안에서** 다시
        // 거르는 줄이라 위계가 한 단 아래다. 둘 다 잉크로 채우면 검은 알약이 세로로
        // 겹쳐 서고, 같은 `전체` 가 두 번 검게 떠 무엇이 무엇을 거르는지 안 보인다.
        // 그래서 칩은 무채색 선택 면(`accent`)으로 내려앉는다 (DESIGN.md 의 선택 상태).
        active
          ? "border-transparent bg-accent font-medium text-accent-foreground"
          : "border-border text-muted-foreground hover:bg-muted hover:text-foreground"
      )}
    >
      {children}
    </button>
  );
}
