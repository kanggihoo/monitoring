"use client";

import {
  useCallback,
  useEffect,
  useEffectEvent,
  useMemo,
  useRef,
  useState,
} from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { useStore } from "zustand";

import { LoginPrompt } from "@/components/login-prompt";
import { FeeEventBanner } from "@/components/market/fee-event-banner";
import { MarketClock } from "@/components/market/market-clock";
import { MoversBoard } from "@/components/market/movers-board";
import { NewsFeed } from "@/components/market/news-feed";
import { PortfolioSummary } from "@/components/market/portfolio-summary";
import { PositionHeatmap } from "@/components/market/position-heatmap";
import { PriceChange } from "@/components/market/price-change";
import { Stat } from "@/components/market/stat";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { useGameClock } from "@/hooks/use-game-clock";
import { useLiveQuery } from "@/hooks/use-live-query";
import { krw } from "@/lib/format";
import { updateExecutions } from "@/lib/live-query-reducers";
import { fetchNews } from "@/lib/market-api";
import type { InstrumentMeta, NewsItem } from "@/lib/market-types";
import { fetchExecutions } from "@/lib/order-api";
import { liveQueryStore } from "@/stores/live-query-store";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

/** 한 번에 펴는 기사 수. 카드가 좁아 뉴스 탭(20)보다 작게 잡는다. */
const PAGE_SIZE = 12;

/**
 * 오늘 이 계좌에서 벌어진 일. 오늘 일차의 체결만 세므로 계약의 `seasonDay` 필터를 쓴다
 * (`ord.md`).
 *
 * **한 페이지만 읽는다.** 하루 체결이 100건을 넘으면 그 뒤는 세지 않는데, 그렇게 거래한
 * 사람에게 "몇 회 매매했나"는 이미 요약이 아니다 — 정확한 합이 필요해지면 서버가 집계를
 * 내려주는 편이 맞다.
 *
 * @param seasonDay 오늘 일차. 시계가 아직 오지 않았으면 `null` 이고 조회하지 않는다
 */
function useTodayTrading(seasonDay: number | null, usdKrwRate: string | null) {
  const read = useCallback(
    () => fetchExecutions(0, 100, seasonDay ?? undefined),
    [seasonDay]
  );
  const { data } = useLiveQuery(read, updateExecutions, seasonDay !== null);
  const rate = Number(usdKrwRate ?? 0);

  const filled = data?.content ?? [];
  return {
    count: filled.length,
    feeKrw: Math.round(
      filled.reduce(
        (sum, execution) =>
          sum +
          Number(execution.fee) * (execution.currency === "USD" ? rate : 1),
        0
      )
    ),
    // 무료 타임 체결은 요율이 0 인 **매도**다. 매수는 언제나 0 이라 세면 전부 걸린다 (`ord.md`).
    feeFreeCount: filled.filter(
      (execution) => execution.side === "SELL" && execution.appliedFeeRate === 0
    ).length,
  };
}

/**
 * 대시보드 뉴스 카드의 목록. 두 갈래가 한 목록으로 합쳐진다 — 매 Tick 개인 큐로 밀려오는
 * 새 기사(틱 스토어)와, 스크롤로 펴는 과거 기사(`GET /news` 커서 페이지)다.
 *
 * 실시간 쪽이 항상 더 새로우므로 앞에 놓고, 같은 기사는 `newsId` 로 한 번만 남긴다 —
 * 첫 페이지와 실시간 기사는 겹칠 수 있다.
 */
function useNewsFeed(live: NewsItem[], asOfGameDate: string | null) {
  const generation = useStore(liveQueryStore, (state) => state.generation);
  const ready = asOfGameDate !== null;
  const [pages, setPages] = useState<NewsItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasNext, setHasNext] = useState(false);
  // 같은 커서로 두 번 부르지 않는다. 스크롤은 한 프레임에 여러 번 불린다.
  const requested = useRef<string | null>(null);

  const firstPage = useEffectEvent(() =>
    fetchNews(asOfGameDate!, { size: PAGE_SIZE })
  );
  useEffect(() => {
    // 절단 기준이 필수라 시계를 기다린다 (`nws.md`).
    if (!ready) return;
    let cancelled = false;
    firstPage()
      .then((page) => {
        if (cancelled) return;
        setPages(page.items);
        setCursor(page.nextCursor);
        setHasNext(page.hasNext);
      })
      .catch((error: unknown) =>
        console.warn("뉴스를 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
  }, [ready, generation]);

  const loadMore = useCallback(() => {
    if (!asOfGameDate || !cursor || requested.current === cursor) return;
    requested.current = cursor;

    fetchNews(asOfGameDate, { cursor, size: PAGE_SIZE })
      .then((page) => {
        setPages((current) => [...current, ...page.items]);
        setCursor(page.nextCursor);
        setHasNext(page.hasNext);
      })
      .catch((error: unknown) =>
        console.warn("다음 기사를 불러오지 못했습니다", error)
      );
  }, [cursor, asOfGameDate]);

  const feed = useMemo(() => {
    const seen = new Set<number>();
    return [...live, ...pages].filter((item) => {
      if (seen.has(item.newsId)) return false;
      seen.add(item.newsId);
      return true;
    });
  }, [live, pages]);

  return { feed, hasNext, loadMore };
}

export function HomeDashboard({
  instruments,
  isLoggedIn = false,
}: {
  instruments: InstrumentMeta[];
  isLoggedIn?: boolean;
}) {
  const clock = useGameClock();
  const prices = useStore(tickStore, (state) => state.prices);
  const valuation = useStore(tickStore, (state) => state.valuation);
  const myRank = useStore(tickStore, (state) => state.myRank);
  const heatmap = useStore(tickStore, (state) => state.heatmap);
  const news = useStore(tickStore, (state) => state.news);
  const usdKrwRate = useStore(tickStore, (state) => state.usdKrwRate);
  const { feed, hasNext, loadMore } = useNewsFeed(news, clock.gameDate);
  const trading = useTodayTrading(
    isLoggedIn ? clock.seasonDay : null,
    usdKrwRate
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      {clock.feeFree && (
        <FeeEventBanner remainingSeconds={clock.feeFreeSeconds} />
      )}

      {/* 좁은 화면의 한 열을 명시한다 — 암시적 열은 `auto` 라 항목의 최소 콘텐츠 폭까지
          자라고, 넓은 창을 줄였을 때 브라우저가 그 폭을 다시 계산하지 않아 굳는다
          (마켓 화면과 같은 원인). */}
      <div className="grid grid-cols-[minmax(0,1fr)] gap-3 lg:grid-cols-3">
        {clock.phase && clock.seasonDay !== null ? (
          <MarketClock
            gameMonth={clock.gameMonth}
            seasonDay={clock.seasonDay}
            transitionLabel={clock.transition.label}
            transitionSeconds={clock.transition.seconds}
            nextCloseSeconds={clock.nextCloseSeconds}
            action={
              // 브리핑은 11:00~11:05 에 저절로 열리고 닫으면 그날은 다시 안 열린다. 그런데
              // 거기 든 것은 오늘의 청약 시각과 무료 타임 구간이라 하루 내내 필요한 정보다
              // (기획 3.5.3 이 재열람을 요구하고 조회의 허용 phase 도 ALWAYS 다).
              <Button
                variant="outline"
                size="sm"
                onClick={() => sessionStore.getState().requestBriefing()}
              >
                오늘 브리핑
              </Button>
            }
          />
        ) : (
          <Skeleton className="h-44 rounded-lg" />
        )}

        {/* 미인증에는 빈 값이 아니라 로그인 안내다 — 데이터가 없는 것과 권한이 없는 것을
            구별할 수 있어야 한다 (ADR-0005). 스켈레톤은 "곧 온다" 는 뜻이라 여기 쓰면 거짓말이다. */}
        {!isLoggedIn ? (
          <Card size="sm" className="justify-center">
            <CardContent>
              <LoginPrompt message="내 자산은 로그인 후에 보입니다" />
            </CardContent>
          </Card>
        ) : valuation ? (
          <PortfolioSummary valuation={valuation} />
        ) : (
          <Skeleton className="h-44 rounded-lg" />
        )}

        <Card size="sm">
          <CardHeader>
            <CardTitle className="text-base font-semibold">
              오늘의 성적
            </CardTitle>
          </CardHeader>
          {!isLoggedIn ? (
            <CardContent>
              <LoginPrompt message="내 순위와 매매 기록은 로그인 후에 보입니다" />
            </CardContent>
          ) : (
            <>
              {/* 옆의 두 카드와 같은 높이다 (`portfolio-summary.tsx` 의 같은 주석). */}
              <CardContent className="grid min-h-15 grid-cols-2 gap-4">
                <Stat
                  label="시즌"
                  value={
                    myRank
                      ? myRank.seasonRank === null
                        ? "암전"
                        : `${myRank.seasonRank}위`
                      : "—"
                  }
                  hint={
                    myRank ? (
                      <>
                        {myRank.totalParticipants}명 중 ·{" "}
                        <PriceChange value={myRank.seasonReturnRate} />
                      </>
                    ) : undefined
                  }
                />
                <Stat
                  label="오늘"
                  value={
                    myRank
                      ? myRank.dailyRank === null
                        ? "암전"
                        : `${myRank.dailyRank}위`
                      : "—"
                  }
                  hint={
                    myRank ? (
                      <PriceChange value={myRank.dailyReturnRate} />
                    ) : undefined
                  }
                />
              </CardContent>

              <Separator />
              <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-3">
                <Stat label="매매" value={`${trading.count}회`} />
                <Stat label="수수료" value={krw(trading.feeKrw)} />
                <Stat label="무료 타임" value={`${trading.feeFreeCount}회`} />
              </CardContent>
            </>
          )}
        </Card>
      </div>

      {/* 둘째 줄이 남은 높이를 전부 가져간다. 뉴스는 그 안에서만 스크롤하므로 기사가 아무리
          쌓여도 대시보드 자체에는 스크롤이 생기지 않는다. */}
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)] gap-3 lg:grid-cols-3">
        <div className="flex min-h-0 flex-col gap-3 lg:col-span-2">
          <MoversBoard instruments={instruments} prices={prices} />
          <Link
            href="/"
            className={buttonVariants({
              variant: "outline",
              className: "self-start",
            })}
          >
            마켓에서 전체 종목 보기 <ArrowRight aria-hidden />
          </Link>
        </div>
        <div className="flex min-h-0 flex-col gap-3">
          {isLoggedIn ? (
            <PositionHeatmap heatmap={heatmap} className="shrink-0" />
          ) : (
            <Card size="sm" className="shrink-0">
              <CardContent>
                <LoginPrompt message="포지션 히트맵은 로그인 후에 보입니다" />
              </CardContent>
            </Card>
          )}
          <NewsFeed
            items={feed}
            hasNext={hasNext}
            onReachEnd={loadMore}
            className="min-h-0 max-w-none flex-1"
          />
        </div>
      </div>
    </div>
  );
}
