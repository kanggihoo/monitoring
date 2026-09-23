"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Newspaper } from "lucide-react";
import { useStore } from "zustand";

import { LoginPrompt } from "@/components/login-prompt";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { trackEvent } from "@/lib/analytics";
import { gameDay } from "@/lib/format";
import { fetchInstruments, fetchNews } from "@/lib/market-api";
import type { InstrumentMeta, NewsItem } from "@/lib/market-types";
import { cn, FOCUS_RING, TAB_LIST, TAB_TRIGGER } from "@/lib/utils";
import { tickStore } from "@/stores/tick-store";

/** 한 번에 받아 오는 기사 수. 바닥에 닿으면 커서로 이만큼씩 이어 붙인다. */
const PAGE_SIZE = 20;

/** 시안의 필터 칩. `전체` 는 파라미터를 안 보내는 것이다 — enum 에 `ALL` 을 두지 않는다. */
const FILTERS = [
  { key: "ALL", label: "전체" },
  { key: "US", label: "미국" },
  { key: "KR", label: "국내" },
  { key: "CRYPTO", label: "코인" },
] as const;

type FilterKey = (typeof FILTERS)[number]["key"];

type Mode = "FEED" | "TIMELINE";

const RELEVANCE_LABEL: Record<
  Exclude<NewsItem["relevance"], "MARKET">,
  string
> = {
  HOLDING: "보유",
  WATCHLIST: "관심",
};

/** 채널 이름은 종목 목록의 시장 이름과 같은 말을 쓴다 (`instrument-list.tsx`). */
const CHANNEL_LABEL: Record<NewsItem["channel"], string> = {
  US: "미국",
  KR: "국내",
  CRYPTO: "코인",
};

/** 지목 스크롤을 다시 맞추는 프레임 수. 약 0.2초다 — 그 사이 손을 대면 취소된다. */
const SETTLE_FRAMES = 12;

/** 지목한 기사가 켜지고 꺼지는 시점(ms). 짝수 번째가 켜짐이라 두 번 깜빡이고 끝난다. */
const BLINK_MS = [0, 300, 600, 900];

/** 지목해 들어온 기사를 찾는 앵커. 티커가 `newsId` 로 이 자리를 가리킨다 (`nws.md`). */
const articleId = (newsId: number) => `news-${newsId}`;

/**
 * 조회가 깨졌을 때. **원인과 다시 할 방법을 함께 낸다** — 스켈레톤만 돌려 두면 느린 것과
 * 깨진 것을 구별할 수 없다 (`docs/conventions/code.md` 8절).
 */
function FailureNotice({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div className="flex flex-col items-center gap-2 py-10 text-center">
      <p className="text-sm text-muted-foreground">{message}</p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        다시 시도
      </Button>
    </div>
  );
}

/** 종목 태그. 누르면 그 종목의 상세로 간다 (기획 10.4.1). */
function InstrumentTag({
  name,
  instrumentId,
}: {
  name: string;
  instrumentId: number;
}) {
  return (
    <Badge
      render={<Link href={`/?instrumentId=${instrumentId}`} />}
      // 뉴스가 실제로 매매로 이어지는지 보는 자리다. 이 수와 뒤따르는 `order_submit` 의
      // 비율이 뉴스 배치를 바꿀 근거가 된다. 기사 단위가 아니라 **종목 태그 단위**로 센다 —
      // 한 기사가 여러 종목에 걸려 있어(`NewsItem.instrumentIds`) 기사 하나로는 어느 종목이
      // 사람을 움직였는지 알 수 없다.
      onClick={() => trackEvent("news_click", { instrument_name: name })}
      variant="secondary"
    >
      {name} ↗
    </Badge>
  );
}

/** 기사 한 건 — 시안의 `오늘의 뉴스`. */
function Article({
  item,
  nameOf,
  focused = false,
}: {
  item: NewsItem;
  nameOf: (instrumentId: number) => string | undefined;
  /** 티커나 종목 상세에서 지목해 들어온 기사. 어느 줄인지 눈에 띄어야 한다. */
  focused?: boolean;
}) {
  return (
    <article
      id={articleId(item.newsId)}
      className={cn(
        "flex flex-col gap-1.5 border-b border-border py-3.5 transition-colors duration-200",
        focused && "-mx-2 rounded-lg bg-accent px-2"
      )}
    >
      {/* 머리말·본문을 한 단 키운 만큼 메타도 함께 올린다 — 10px 로 두면 기사마다
          윗줄만 눈에 띄게 작아 줄이 어긋나 보인다. */}
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground tabular-nums">
          {gameDay(item.publishedGameDate)}
        </span>
        <span className="text-xs text-muted-foreground tabular-nums">
          {item.publishedTime.slice(0, 5)}
        </span>
        <Badge variant="secondary" className="h-4 px-1 text-2xs">
          {CHANNEL_LABEL[item.channel]}
        </Badge>
        {item.relevance !== "MARKET" && (
          <Badge variant="secondary" className="h-4 px-1 text-2xs">
            {RELEVANCE_LABEL[item.relevance]}
          </Badge>
        )}
      </div>

      {/* 목록 글자를 한 단씩 키웠다. 1440px 화면에서 머리말 12px · 본문 11px 은
          기사 목록이라기보다 각주로 읽힌다. */}
      <p
        className={cn(
          "text-base",
          item.major ? "font-medium text-foreground" : "text-foreground-subtle"
        )}
      >
        {item.headline}
      </p>
      <p className="text-sm leading-relaxed text-muted-foreground">
        {item.body}
      </p>

      {(item.instrumentIds.length > 0 || item.tags.length > 0) && (
        <div className="flex flex-wrap items-center gap-1.5 pt-1">
          {item.instrumentIds.map((instrumentId) => {
            const name = nameOf(instrumentId);
            // 아직 상장 전이라 유니버스에 없는 id 는 태그를 만들지 않는다 — 누르면
            // 존재하지 않는 종목으로 간다.
            return name ? (
              <InstrumentTag
                key={instrumentId}
                name={name}
                instrumentId={instrumentId}
              />
            ) : null;
          })}
          {item.tags.map((tag) => (
            // 분류 태그는 이동하지 않는다 (`nws.md`). 종목 태그와 생김새를 달리해
            // 누를 수 있는 것과 아닌 것을 구별한다.
            <Badge key={tag} variant="secondary" className="text-2xs">
              {tag}
            </Badge>
          ))}
        </div>
      )}
    </article>
  );
}

/** 한 줄짜리 기사 — 시안의 `전체 타임라인`. 본문 없이 흐름만 훑는 화면이다. */
function TimelineRow({
  item,
  focused = false,
}: {
  item: NewsItem;
  focused?: boolean;
}) {
  return (
    <div
      id={articleId(item.newsId)}
      className={cn(
        "flex items-baseline gap-3.5 border-b border-border py-2.5 transition-colors duration-200",
        focused && "-mx-2 rounded-lg bg-accent px-2"
      )}
    >
      {/* 글자 크기는 `오늘의 뉴스` 의 기사 줄과 같다 — 같은 기사를 두 방식으로 보는
          화면이라 탭을 옮겼을 때 글자가 커지거나 작아지면 다른 목록처럼 읽힌다. */}
      <span className="w-28 shrink-0 text-xs text-muted-foreground tabular-nums">
        {gameDay(item.publishedGameDate)} {item.publishedTime.slice(0, 5)}
      </span>
      <span
        className={cn(
          "min-w-0 flex-1 truncate text-base",
          item.major ? "text-foreground" : "text-foreground-subtle"
        )}
      >
        {item.headline}
      </span>
      <Badge variant="secondary" className="shrink-0 text-xs">
        {CHANNEL_LABEL[item.channel]}
      </Badge>
    </div>
  );
}

/** 우측 열이 한 번에 받아 오는 기사 수. 바닥에 닿으면 커서로 이만큼씩 이어 붙인다. */
const ASIDE_PAGE_SIZE = 20;

/**
 * 뉴스 화면의 우측 열 — 내 보유·관심 종목 소식.
 *
 * **왼쪽 목록을 걸러 쓰지 않고 제 목록을 따로 읽는다.** 걸러 쓰면 왼쪽이 `코스피` 로
 * 필터돼 있을 때 오른쪽도 함께 줄고, 왼쪽이 아직 첫 페이지만 읽었으면 오른쪽도 그만큼만
 * 보인다 — 이 칸이 답해야 하는 것은 "내 종목에 무슨 일이 있었나" 지 "지금 보고 있는
 * 목록 안에서" 가 아니다. 서버가 `relevance=PERSONAL` 로 같은 판정을 이미 한다.
 */
/**
 * 서버가 내려 준 목록을 화면이 **한 번 더 거른다.**
 *
 * `relevance=PERSONAL` 은 계약에 있지만 **Spring 이 아직 그 파라미터를 받지 않는다**
 * (`NewsController.getFeed` 는 `channel`·`instrumentId`·`cursor`·`size` 만 받고, 모든
 * 기사의 `relevance` 를 `MARKET` 으로 고정해 내려준다 — 보유·관심 판정은 포지션·워치리스트가
 * 붙으면 서버가 계산한다고 `NewsApi` 에 적혀 있다).
 *
 * 그래서 이 가드가 없으면 **전체 기사가 `내 종목 소식` 이라는 이름으로 뜬다.** 거르면
 * 서버가 판정하기 전까지는 빈 상태로 남고, 서버가 판정을 시작하면 그대로 채워진다.
 */
const personalOnly = (items: NewsItem[]) =>
  items.filter((item) => item.relevance !== "MARKET");

function NewsAside({ isLoggedIn }: { isLoggedIn: boolean }) {
  const gameDate = useStore(tickStore, (state) => state.gameDate);
  const [items, setItems] = useState<NewsItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  /** 이미 부른 커서. 스크롤 이벤트가 연달아 들어와도 같은 장을 두 번 읽지 않는다. */
  const requested = useRef<string | null>(null);

  // 첫 장. 게임 날짜가 바뀌면(새 거래일) 앞에 붙는 기사가 있으므로 처음부터 다시 읽는다.
  useEffect(() => {
    if (!isLoggedIn || !gameDate) return;
    let cancelled = false;
    fetchNews(gameDate, { personalOnly: true, size: ASIDE_PAGE_SIZE })
      .then((page) => {
        if (cancelled) return;
        setItems(personalOnly(page.items));
        setCursor(page.nextCursor);
        setFailed(false);
        setLoaded(true);
        requested.current = null;
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.error("내 종목 소식을 불러오지 못했습니다", error);
        setFailed(true);
        setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [isLoggedIn, gameDate]);

  const loadMore = useCallback(() => {
    if (!gameDate || !cursor || requested.current === cursor) return;
    requested.current = cursor;
    fetchNews(gameDate, {
      personalOnly: true,
      cursor,
      size: ASIDE_PAGE_SIZE,
    })
      .then((next) => {
        setItems((current) => [...current, ...personalOnly(next.items)]);
        setCursor(next.nextCursor);
        setFailed(false);
      })
      .catch((error: unknown) => {
        console.error("다음 기사를 불러오지 못했습니다", error);
        setFailed(true);
      });
  }, [cursor, gameDate]);

  /**
   * 무한 스크롤. 왼쪽 목록과 같은 방식이다 — 듣는 대상은 브라우저 창이 아니라 **이 카드가
   * 실제로 스크롤하는 상자**다. 창은 움직이지 않으므로 창에 붙이면 영원히 불리지 않는다.
   */
  useEffect(() => {
    const viewport = bottomRef.current?.closest(
      "[data-slot='scroll-area-viewport']"
    );
    if (!viewport || !cursor || failed) return;

    const onScroll = () => {
      const remaining =
        viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
      // 바닥에 닿기 전에 미리 부른다 — 닿고 나서 부르면 빈 자리를 잠깐 보게 된다.
      if (remaining < 160) loadMore();
    };

    viewport.addEventListener("scroll", onScroll, { passive: true });
    return () => viewport.removeEventListener("scroll", onScroll);
  }, [cursor, failed, loadMore, items.length]);

  return (
    <div className="flex min-h-0 flex-col gap-3">
      {/* 남는 높이를 전부 가져간다. 목록은 카드 안에서만 스크롤한다. */}
      <Card size="sm" className="flex min-h-0 flex-1 flex-col">
        <CardHeader className="shrink-0">
          {/* 우측 열의 주인공이라 제목을 한 단 키운다 — `size="sm"` 카드의 기본 제목은
              12px 이고, 그 크기로는 아래 기사 줄과 무게가 같아 제목으로 읽히지 않는다. */}
          <CardTitle className="text-base">보유·관심 종목 소식</CardTitle>
        </CardHeader>

        {!isLoggedIn ? (
          <CardContent>
            <LoginPrompt message="내 종목 소식은 로그인 후에 보입니다" />
          </CardContent>
        ) : !gameDate ? (
          <CardContent className="flex flex-col items-center justify-center gap-2 py-12 text-center">
            <Newspaper className="size-8 text-muted-foreground/40" />
            <p className="text-sm font-medium text-muted-foreground">
              1일차 개장 후 내 종목 소식이 표시됩니다
            </p>
          </CardContent>
        ) : !loaded ? (
          <CardContent className="flex flex-col gap-2">
            <Skeleton className="h-8 rounded-md" />
            <Skeleton className="h-8 rounded-md" />
            <Skeleton className="h-8 rounded-md" />
          </CardContent>
        ) : failed && items.length === 0 ? (
          <CardContent className="flex flex-col items-center justify-center gap-2 py-12 text-center">
            <p className="text-sm font-medium text-muted-foreground">
              내 종목 소식을 불러오지 못했습니다.
            </p>
          </CardContent>
        ) : items.length === 0 ? (
          <CardContent className="flex flex-col items-center justify-center gap-2 py-12 text-center">
            <Newspaper className="size-8 text-muted-foreground/40" />
            <p className="text-sm font-medium text-muted-foreground">
              내 종목에 걸린 기사가 아직 없습니다
            </p>
          </CardContent>
        ) : (
          <ScrollArea className="min-h-0 flex-1">
            <CardContent className="flex flex-col pr-3">
              {items.map((item) => (
                // `focus` 는 이 화면이 이미 아는 파라미터다 (종목 상세의 `이 종목 뉴스` 와
                // 같은 경로) — 누르면 왼쪽 목록의 그 기사로 스크롤하며 깜빡인다.
                //
                // **보유·관심 배지는 붙이지 않는다.** 이 카드에 담긴 기사가 전부 그것이라
                // 줄마다 같은 말을 되풀이하게 된다 — 그 사실은 카드 제목이 이미 말한다.
                <Link
                  key={item.newsId}
                  href={`/news?focus=${item.newsId}`}
                  className={cn(
                    FOCUS_RING,
                    "flex flex-col gap-0.5 border-b border-border py-2 last:border-b-0 hover:text-foreground"
                  )}
                >
                  <span className="text-2xs text-muted-foreground tabular-nums">
                    {gameDay(item.publishedGameDate)}{" "}
                    {item.publishedTime.slice(0, 5)}
                  </span>
                  <span className="text-sm">{item.headline}</span>
                </Link>
              ))}

              <div ref={bottomRef} className="py-2 text-center">
                {failed ? (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setFailed(false);
                      requested.current = null;
                      loadMore();
                    }}
                  >
                    다시 시도
                  </Button>
                ) : (
                  cursor && (
                    <span className="text-2xs text-muted-foreground">
                      스크롤하면 이전 기사가 더 불러와집니다
                    </span>
                  )
                )}
              </div>
            </CardContent>
          </ScrollArea>
        )}
      </Card>
    </div>
  );
}

/**
 * 뉴스 피드 (기획 10장, 시안 뉴스 탭).
 *
 * **정렬은 서버가 준 순서를 그대로 쓴다.** 보유 > 관심 > 일반 우선 노출은 서버가 하고
 * 화면은 같은 `relevance` 로 강조만 한다 — 여기서 다시 정렬하면 서버 정렬과 어긋난 강조가
 * 생기고, 커서가 가리키는 자리와 화면의 자리가 달라진다 (`nws.md`).
 *
 * **미래 차단도 서버가 한다.** 게임 시각 이후 기사는 응답에 없다. 그래서 새 Tick 마다
 * 목록을 처음부터 다시 읽는다 — 앞에 붙는 기사가 있기 때문이다 (기획 10.1.3).
 */
export function NewsScreen({ isLoggedIn = false }: { isLoggedIn?: boolean }) {
  const [mode, setMode] = useState<Mode>("FEED");
  const [filter, setFilter] = useState<FilterKey>("ALL");
  const [items, setItems] = useState<NewsItem[]>([]);
  /** 이어 붙일 자리. 목록 끝의 커서다 — 첫 페이지를 다시 읽어도 이 값은 건드리지 않는다. */
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasNext, setHasNext] = useState(false);
  /**
   * 지금 목록에 담긴 필터. **불리언 `loaded` 를 두지 않는다** — 필터가 바뀐 순간 그것을
   * 끄려면 effect 안에서 `setState` 를 불러야 하고, 그게 곧 연쇄 렌더다. 담긴 필터와 고른
   * 필터를 견주면 "아직 안 읽었다"가 파생값이 된다.
   */
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [instruments, setInstruments] = useState<InstrumentMeta[]>([]);
  const [retry, setRetry] = useState(0);
  const bottomRef = useRef<HTMLDivElement>(null);
  /**
   * 이미 요청한 커서. **상태가 아니라 ref 다** — 한 번의 스크롤에 이벤트가 여러 번 들어오는데
   * `setState` 는 그 사이에 반영되지 않아, 상태로 막으면 같은 커서를 여러 번 불러 같은 기사가
   * 목록에 두 번 들어간다.
   */
  const requested = useRef<string | null>(null);

  // 새 기사는 매 Tick 앞에 붙는다 (기획 10.1.3). 그 순간 첫 페이지를 다시 읽는다.
  const latestPushedId = useStore(tickStore, (state) => state.news[0]?.newsId);

  /**
   * 시계가 왔는지만 본다. `asOfGameDate` 는 필수 파라미터이지만(`nws.md`) **게임 거래일은
   * 약 36초마다 바뀐다** — 그 값을 이펙트 의존성에 실으면 읽던 목록이 36초마다 처음으로
   * 되돌아간다. 목록의 신선도는 새 기사 도착(`latestPushedId`)이 맡고, 날짜는 부르는
   * 순간의 스토어에서 직접 읽는다.
   */
  const clockReady = useStore(tickStore, (state) => state.gameDate !== null);

  /**
   * 종목별 뉴스와 지목 기사는 주소로 들어온다 (기획 10.4.3 · 10.2.3).
   * 종목 필터는 **같은 컬렉션의 필터이지 다른 리소스가 아니다** — 경로를 따로 두지 않는다.
   */
  const params = useSearchParams();
  const instrumentId = Number(params.get("instrumentId")) || undefined;
  const focus = Number(params.get("focus")) || undefined;

  const query = {
    ...(filter === "ALL" ? {} : { channel: filter }),
    ...(instrumentId ? { instrumentId } : {}),
  };

  /** 지금 목록이 담고 있는 조건. 이것이 고른 조건과 다르면 아직 안 읽은 것이다. */
  const key = `${filter}:${instrumentId ?? ""}`;
  const loaded = loadedKey === key;

  useEffect(() => {
    let cancelled = false;
    fetchInstruments()
      .then((fresh) => {
        if (!cancelled) setInstruments(fresh.instruments);
      })
      .catch((error: unknown) =>
        console.warn("종목 이름을 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
  }, []);

  /** 필터를 바꾸면 목록이 통째로 바뀐다 — 쌓아 둔 페이지도 커서도 버리고 처음부터 읽는다. */
  useEffect(() => {
    let cancelled = false;
    requested.current = null;

    const asOfGameDate = tickStore.getState().gameDate;
    if (!asOfGameDate) return;

    fetchNews(asOfGameDate, { ...query, size: PAGE_SIZE })
      .then((fresh) => {
        if (cancelled) return;
        setItems(fresh.items);
        setCursor(fresh.nextCursor);
        setHasNext(fresh.hasNext);
        setLoadedKey(key);
        setFailed(false);
        // 스크롤을 그대로 두면 새 목록의 한복판에 떨어져 무엇이 바뀌었는지 보이지 않는다.
        // 지목해 들어왔을 때는 건드리지 않는다 — 아래에서 그 기사로 맞출 자리다.
        const viewport = bottomRef.current?.closest(
          "[data-slot='scroll-area-viewport']"
        );
        if (viewport && !focus) viewport.scrollTop = 0;
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.error("뉴스를 불러오지 못했습니다", error);
        setFailed(true);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- query 는 매 렌더 새 객체다. 값인 key 를 본다.
  }, [key, retry, focus, clockReady]);

  /**
   * 새 기사가 왔을 때. **첫 페이지만 다시 읽어 앞에 없는 것만 얹는다** — 목록을 통째로
   * 갈아 끼우면 읽던 자리가 매 Tick 사라지고, 커서까지 되돌리면 스크롤로 편 페이지가
   * 전부 날아간다.
   */
  useEffect(() => {
    if (!latestPushedId || !loaded) return;
    let cancelled = false;

    const asOfGameDate = tickStore.getState().gameDate;
    if (!asOfGameDate) return;

    fetchNews(asOfGameDate, { ...query, size: PAGE_SIZE })
      .then((fresh) => {
        if (cancelled) return;
        setItems((current) => {
          const known = new Set(current.map((item) => item.newsId));
          const added = fresh.items.filter((item) => !known.has(item.newsId));
          return added.length > 0 ? [...added, ...current] : current;
        });
      })
      .catch((error: unknown) =>
        console.warn("새 기사를 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- query 는 매 렌더 새 객체다. 값인 key 를 본다.
  }, [latestPushedId, key]);

  /**
   * 다음 커서 한 장. 스크롤이 부르고, 실패했을 때 `다시 시도` 도 같은 것을 부른다 —
   * 실패했다고 목록을 처음부터 다시 읽으면 지금까지 편 페이지가 전부 날아간다.
   */
  const loadMore = useCallback(() => {
    const asOfGameDate = tickStore.getState().gameDate;
    if (!asOfGameDate || !cursor || requested.current === cursor) return;
    requested.current = cursor;

    fetchNews(asOfGameDate, { ...query, cursor, size: PAGE_SIZE })
      .then((next) => {
        setItems((current) => [...current, ...next.items]);
        setCursor(next.nextCursor);
        setHasNext(next.hasNext);
        setFailed(false);
      })
      .catch((error: unknown) => {
        console.error("다음 기사를 불러오지 못했습니다", error);
        setFailed(true);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- query 는 매 렌더 새 객체다. 값인 key 를 본다.
  }, [cursor, key]);

  /**
   * 과거 방향 무한 스크롤 (기획 10.3.2). 듣는 대상은 브라우저 창이 아니라 **목록이 실제로
   * 스크롤하는 상자**다 — 창은 움직이지 않으므로 창에 붙이면 영원히 불리지 않는다.
   */
  useEffect(() => {
    const viewport = bottomRef.current?.closest(
      "[data-slot='scroll-area-viewport']"
    );
    if (!viewport || !cursor || failed) return;

    const onScroll = () => {
      const remaining =
        viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
      // 바닥에 닿기 전에 미리 부른다 — 닿고 나서 부르면 빈 화면을 잠깐 보게 된다.
      if (remaining < 240) loadMore();
    };

    viewport.addEventListener("scroll", onScroll, { passive: true });
    return () => viewport.removeEventListener("scroll", onScroll);
  }, [cursor, failed, loadMore, items.length]);

  /**
   * 지목해 들어온 기사로 스크롤한다 (기획 10.2.3). 목록이 채워진 **뒤에야** 그 자리가
   * 생기므로 `items` 를 보고 다시 시도한다.
   *
   * **한 번 맞추면 그만둔다.** 스크롤로 다음 장을 펴면 `items` 가 다시 늘어나는데, 그때도
   * 맞추면 읽으려고 내려간 사람을 그 기사로 도로 끌어올린다.
   */
  const focused = useRef<number | null>(null);
  const [blink, setBlink] = useState(false);

  useEffect(() => {
    if (!focus || focused.current === focus) return;
    if (!document.getElementById(articleId(focus))) return;
    focused.current = focus;

    // **몇 프레임에 걸쳐 다시 맞춘다.** 줄이 그려진 직후에는 높이가 아직 확정되지 않아
    // 한 번만 맞추면 엉뚱한 자리에 선다 — 목록 끝으로 튀는 것이 그 증상이다.
    let frames = 0;
    let handle = requestAnimationFrame(function settle() {
      document
        .getElementById(articleId(focus))
        ?.scrollIntoView({ block: "center" });
      if (++frames < SETTLE_FRAMES) handle = requestAnimationFrame(settle);
    });
    return () => cancelAnimationFrame(handle);
  }, [focus, items, mode]);

  /**
   * 지목한 기사를 **두 번 깜빡이고 만다.** 배경을 계속 깔아 두면 다 읽은 뒤에도 그 줄만
   * 다른 상태인 것처럼 남는다 — 어느 줄로 왔는지만 알려 주면 되는 표시다.
   *
   * 위 스크롤과 **한 effect 에 두지 않는다.** 저쪽은 `items` 가 늘 때마다 다시 도는데,
   * 그 정리 단계가 아직 남은 깜빡임을 지워 한 번만 켜지고 끝난다.
   */
  useEffect(() => {
    if (!focus || !loaded) return;
    const timers = BLINK_MS.map((delay, step) =>
      setTimeout(() => setBlink(step % 2 === 0), delay)
    );
    return () => {
      for (const timer of timers) clearTimeout(timer);
      setBlink(false);
    };
  }, [focus, loaded]);

  const nameOf = (instrumentId: number) =>
    instruments.find((item) => item.instrumentId === instrumentId)?.name;

  const focusedName = instrumentId ? nameOf(instrumentId) : undefined;

  return (
    // 본문 + 우측 보조 열. 랭킹·복기·마이페이지가 쓰는 것과 같은 골격이다 — 뉴스만
    // 단독 카드라 혼자 달랐고, 기사 한 건이 70자 안팎이라 1300px 면에서는 오른쪽 절반이
    // 늘 비어 있었다.
    <div className="grid min-h-0 flex-1 gap-3 xl:grid-cols-[minmax(0,1fr)_22.5rem]">
      <Card size="sm" className="min-h-0">
        <CardContent className="flex shrink-0 flex-wrap items-center justify-between gap-3">
          <Tabs
            className="min-w-0"
            value={mode}
            onValueChange={(value) => setMode(value as Mode)}
            aria-label="뉴스 보기"
          >
            <TabsList
              className={cn(
                TAB_LIST,
                "w-[20.5rem] max-w-full overflow-x-auto group-data-horizontal/tabs:h-[37px]"
              )}
            >
              <TabsTrigger value="FEED" className={TAB_TRIGGER}>
                오늘의 뉴스
              </TabsTrigger>
              <TabsTrigger value="TIMELINE" className={TAB_TRIGGER}>
                전체 타임라인
              </TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="flex flex-wrap items-center gap-1">
            {/* 전부 공개 피드의 분류다 — 개인 데이터를 거르는 칩은 여기 없다.
                내 보유·관심 종목 기사는 우측 열(`NewsAside`)이 제 목록으로 맡는다. */}
            {FILTERS.map((item) => (
              <Button
                key={item.key}
                size="sm"
                variant={filter === item.key ? "default" : "outline"}
                className="h-7 rounded-full px-3 text-2xs"
                aria-pressed={filter === item.key}
                onClick={() => setFilter(item.key)}
              >
                {item.label}
              </Button>
            ))}
            {/* 종목별 뉴스로 들어왔을 때 (기획 10.4.3). 지금 무엇으로 좁혀져 있는지
                말해 주고, 누르면 전체로 돌아간다 — 안 그러면 빠져나갈 길이 없다. */}
            {instrumentId && (
              <Button
                size="sm"
                variant="default"
                className="h-7 rounded-full px-3 text-2xs"
                nativeButton={false}
                render={<Link href="/news" />}
              >
                {focusedName ?? `종목 ${instrumentId}`} ✕
              </Button>
            )}
          </div>
        </CardContent>

        {/* 목록만 스크롤한다. `Card` 는 `overflow-hidden` 이라 이걸 두지 않으면 카드
            높이를 넘는 기사가 잘려 아예 볼 수 없다. 스크롤 모양은 마켓의 종목 목록과
            같은 `ScrollArea` 다. */}
        <ScrollArea className="min-h-0 flex-1">
          <CardContent className="flex flex-col py-0 pr-3">
            {failed && items.length === 0 ? (
              <FailureNotice
                message="뉴스를 불러오지 못했습니다."
                onRetry={() => setRetry((current) => current + 1)}
              />
            ) : !clockReady ? (
              <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
                <Newspaper className="size-8 text-muted-foreground/40" />
                <p className="text-sm font-medium text-muted-foreground">
                  개장 준비 중입니다. 1일차 개장 후 뉴스가 전해집니다.
                </p>
              </div>
            ) : !loaded ? (
              <Skeleton className="my-3 h-64 rounded-lg" />
            ) : items.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
                <Newspaper className="size-8 text-muted-foreground/40" />
                <p className="text-sm font-medium text-muted-foreground">
                  이 구간에는 전해진 소식이 없습니다.
                </p>
              </div>
            ) : (
              <>
                {items.map((item) =>
                  mode === "TIMELINE" ? (
                    <TimelineRow
                      key={item.newsId}
                      item={item}
                      focused={blink && item.newsId === focus}
                    />
                  ) : (
                    <Article
                      key={item.newsId}
                      item={item}
                      nameOf={nameOf}
                      focused={blink && item.newsId === focus}
                    />
                  )
                )}

                {/* 바닥 표식. 여기가 목록 안으로 들어오면 다음 커서를 잇는다. */}
                <div ref={bottomRef} className="py-4 text-center">
                  {failed ? (
                    <FailureNotice
                      message="다음 기사를 불러오지 못했습니다."
                      onRetry={() => {
                        requested.current = null;
                        loadMore();
                      }}
                    />
                  ) : (
                    hasNext && (
                      <span className="text-xs text-muted-foreground">
                        스크롤하면 이전 기사가 더 불러와집니다
                      </span>
                    )
                  )}
                </div>
              </>
            )}
          </CardContent>
        </ScrollArea>
      </Card>

      <NewsAside isLoggedIn={isLoggedIn} />
    </div>
  );
}
