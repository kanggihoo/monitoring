"use client";

import { useState } from "react";
import { Star } from "lucide-react";
import { toast } from "sonner";

import { useLoginPrompt } from "@/components/login-prompt";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { apiErrorMessage, apiFetch } from "@/lib/api";
import { gameDay, localPrice } from "@/lib/format";
import type {
  InstrumentMeta,
  IpoOfferingStatus,
  PriceEntry,
} from "@/lib/market-types";
import { cn, FOCUS_RING, TAB_LIST, TAB_TRIGGER } from "@/lib/utils";

import { instrumentTags } from "./labels";
import { PriceChange } from "./price-change";

type Category = InstrumentMeta["category"] | "ALL";
type Filter = "ALL" | "WATCHED" | "ETF";
type Sort = "DEFAULT" | "CHANGE" | "VOLUME" | "NAME";

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

/**
 * 거래대금을 정렬용 수로. **금액은 문자열로 온다** (`_common.md §6.1`) — 원화라 자릿수가
 * 커서 문자열끼리 비교하면 `"9"` 가 `"48251000000"` 보다 크다.
 *
 * 아직 못 받은 종목은 `-1` 이다. 체결이 없는 종목은 서버가 `"0"` 을 채워 보내므로, 그
 * 둘을 갈라 "값이 없음" 이 "거래가 없음" 뒤로 간다.
 */
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
 * 정렬 기준. `거래대금` 은 서버가 주는 `tradedAmountKrw` 를 그대로 쓴다
 * (`S15P21A605-271`) — 참가자들이 그 종목에 주고받은 돈이며 실제 시장 거래량이 아니다.
 */
const SORTS: { value: Sort; label: string }[] = [
  { value: "DEFAULT", label: "기본" },
  { value: "CHANGE", label: "등락률" },
  { value: "VOLUME", label: "거래대금" },
  { value: "NAME", label: "이름" },
];

/**
 * 유니버스 28종 목록. 시세는 Tick 마다 한 번에 바뀌고, 메타는 서버 컴포넌트가 준다.
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
export function InstrumentList({
  instruments,
  prices,
  selectedId,
  onSelect,
  isLoggedIn = false,
  ipoStatus,
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
  className?: string;
}) {
  const promptLogin = useLoginPrompt();
  const [category, setCategory] = useState<Category>("ALL");
  const [filter, setFilter] = useState<Filter>("ALL");
  const [sort, setSort] = useState<Sort>("DEFAULT");
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

  const visible = instruments
    .filter((i) => category === "ALL" || i.category === category)
    .filter((i) =>
      filter === "ALL"
        ? true
        : filter === "WATCHED"
          ? watched.has(i.instrumentId)
          : i.assetType === "ETF" || i.assetType === "INVERSE_ETF"
    )
    .sort((a, b) => {
      if (sort === "NAME") return a.name.localeCompare(b.name, "ko");
      if (sort === "CHANGE") {
        return (
          (prices[b.instrumentId]?.changeRate ?? -Infinity) -
          (prices[a.instrumentId]?.changeRate ?? -Infinity)
        );
      }
      if (sort === "VOLUME") {
        // 개장 직후에는 전 종목이 0 이라 동점이 무더기로 난다. 남는 순서를 배열이 들어온
        // 순서에 맡기면 틱마다 줄이 흔들리므로 기본 순서로 갈라 준다.
        const gap = tradedAmount(prices, b) - tradedAmount(prices, a);
        return gap !== 0 ? gap : a.instrumentId - b.instrumentId;
      }
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
        <Chip active={filter === "ETF"} onClick={() => setFilter("ETF")}>
          ETF·인버스
        </Chip>
      </div>

      <Select
        items={SORTS}
        value={sort}
        onValueChange={(value) => setSort(value as Sort)}
      >
        <SelectTrigger size="sm" className="w-full" aria-label="정렬">
          <SelectValue />
        </SelectTrigger>
        {/* 트리거 아래로 편다. 기본값(`alignItemWithTrigger`)은 네이티브 select 처럼
            고른 항목을 트리거 위에 겹쳐 놓는 방식이라, 목록이 트리거와 위쪽 필터 칩을
            덮고 가로로도 어긋난다. */}
        <SelectContent align="start" alignItemWithTrigger={false}>
          {SORTS.map((item) => (
            <SelectItem key={item.value} value={item.value}>
              {item.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

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
                    <span
                      className={cn(
                        "truncate text-2xs",
                        selected
                          ? "text-foreground-subtle"
                          : "text-muted-foreground"
                      )}
                    >
                      {instrumentTags(instrument)}
                    </span>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-0.5">
                    {upcoming ? (
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
