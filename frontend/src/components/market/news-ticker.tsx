"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useStore } from "zustand";

import { gameDay } from "@/lib/format";
import { fetchNewsTicker } from "@/lib/market-api";
import type { NewsTicker as NewsTickerData } from "@/lib/market-types";
import { cn, FOCUS_RING } from "@/lib/utils";
import { tickStore } from "@/stores/tick-store";

/** 띠에 싣는 헤드라인 수. */
const TICKER_HEADLINES = 6;

/** 헤드라인 한 건이 지나가는 데 걸리는 시간. 눈으로 따라 읽을 수 있는 정도로 잡았다. */
const SECONDS_PER_HEADLINE = 7;

/**
 * 하단 뉴스 띠. 틱 스토어의 최신 기사 몇 건을 한 줄로 흘린다 — 시안의 NEWS 바.
 * 기사가 없으면 그 사실을 말한다.
 */
export function NewsTicker({
  className,
  ...props
}: React.ComponentProps<"div">) {
  const news = useStore(tickStore, (state) => state.news);
  const gameDate = useStore(tickStore, (state) => state.gameDate);
  /**
   * 틱으로 기사가 오기 전의 띠를 채운다 (`nws.md` 의 티커). **기사가 없는 틱에는 메시지가
   * 오지 않으므로**, 스토어만 보면 접속 직후부터 다음 기사까지 몇 분이고 빈 띠가 남는다.
   * 본문도 발행일도 없는 응답이다 — 티커는 헤드라인만 굴린다.
   */
  const [seed, setSeed] = useState<NewsTickerData["items"]>([]);
  const stripRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // 시계가 오기 전에는 부르지 않는다 — `asOfGameDate` 가 절단 기준이라 필수이고,
    // 빈 값으로 보내면 400 이다 (`nws.md`).
    if (news.length > 0 || !gameDate) return;
    let cancelled = false;
    fetchNewsTicker(gameDate)
      .then((fresh) => {
        if (!cancelled) setSeed(fresh.items);
      })
      .catch((error: unknown) =>
        console.warn("헤드라인을 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
  }, [news.length, gameDate]);

  /** 틱이 준 기사가 있으면 그것이 최신이다. 없을 때만 씨앗을 쓴다. */
  const headlines: {
    newsId: number;
    headline: string;
    relevance: NewsTickerData["items"][number]["relevance"];
    publishedGameDate?: string;
  }[] = news.length > 0 ? news : seed;

  /**
   * 실을 기사를 **고르는 일과 그리는 순서는 다르다.**
   *
   * 목록은 최신이 앞이므로 최신 몇 건을 고르는 것은 앞에서 자르는 것이다. 그리는 순서는
   * 거꾸로다 — 띠가 오른쪽에서 왼쪽으로 흐르니 줄 앞에 둔 항목이 가장 먼저 지나가고,
   * 최신을 거기 두면 새 기사가 이미 지나간 자리에 끼어든다. 뒤집어 놓으면 최신이 줄
   * 끝에 서서 오른쪽에서 흘러 들어온다.
   *
   * 뒤에서 자르면 안 된다 — 그러면 최신이 아니라 **가장 오래된** 몇 건을 싣게 된다.
   */
  const visible = headlines.slice(0, TICKER_HEADLINES).reverse();
  /** 실린 기사가 바뀌면 줄이 달라진다. 그때만 애니메이션을 다시 건다. */
  const stripKey = visible.map((item) => item.newsId).join();
  const visibleCount = visible.length;

  /**
   * 흐르는 띠. **키프레임이 아니라 Web Animations API 로 돌린다** — `globals.css` 는
   * DESIGN.md 에서 생성되는 산출물이라 `@keyframes` 를 넣을 자리가 없다.
   *
   * **너비를 재지 않는다.** 줄을 두 벌 두었으므로 `-50%` 가 곧 한 벌이다 — 재려 들면 폰트가
   * 적용되기 전의 값을 잡아 띠가 엉뚱한 속도로 돈다. 대신 속도는 실린 기사 수로 정한다.
   */
  useEffect(() => {
    const strip = stripRef.current;
    if (!strip || visibleCount === 0) return;

    // 움직임을 줄여 달라고 한 사람에게는 흐르지 않는다.
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    const animation = strip.animate(
      [{ transform: "translateX(0)" }, { transform: "translateX(-50%)" }],
      {
        duration: visibleCount * SECONDS_PER_HEADLINE * 1000,
        iterations: Infinity,
      }
    );

    // 세워야 누를 수 있다. 흐르는 링크를 정확히 맞히기는 어렵다.
    const pause = () => animation.pause();
    const play = () => animation.play();
    strip.addEventListener("pointerenter", pause);
    strip.addEventListener("pointerleave", play);
    strip.addEventListener("focusin", pause);
    strip.addEventListener("focusout", play);

    return () => {
      animation.cancel();
      strip.removeEventListener("pointerenter", pause);
      strip.removeEventListener("pointerleave", play);
      strip.removeEventListener("focusin", pause);
      strip.removeEventListener("focusout", play);
    };
  }, [stripKey, visibleCount]);

  return (
    <div
      className={cn(
        "flex h-10 shrink-0 items-center gap-4 overflow-hidden rounded-xl border border-border bg-card px-4 text-xs",
        className
      )}
      {...props}
    >
      <span className="flex shrink-0 items-center gap-1.5 tracking-wider text-muted-foreground uppercase">
        <span aria-hidden className="size-1.5 rounded-full bg-price-up" />
        News
      </span>
      <div className="flex min-w-0 flex-1 items-center overflow-hidden whitespace-nowrap">
        {headlines.length === 0 ? (
          <span className="text-muted-foreground">
            이 구간에는 전해진 소식이 없습니다
            {gameDate ? ` · ${gameDay(gameDate)} 기준` : ""}.
          </span>
        ) : (
          <div ref={stripRef} className="flex">
            {/* 같은 줄을 두 벌 둔다. 한 벌 너비만큼 왼쪽으로 밀면 두 번째 벌이 그 자리를
                이어받아 끊긴 데 없이 도는 것처럼 보인다. 읽는 사람에게는 한 줄이므로
                복사본은 스크린 리더에서 숨긴다. */}
            {[0, 1].map((copy) => (
              <div
                key={copy}
                aria-hidden={copy === 1}
                className="flex shrink-0 items-center gap-6 pr-6"
              >
                {visible.map((item) => (
                  // 누르면 피드의 그 기사로 간다 (기획 10.2.3). 티커에는 본문이 없으므로
                  // 이 링크가 기사를 읽는 유일한 길이다.
                  <Link
                    key={item.newsId}
                    href={`/news?focus=${item.newsId}`}
                    tabIndex={copy === 1 ? -1 : undefined}
                    className={cn(
                      FOCUS_RING,
                      "flex items-center gap-2 rounded-sm hover:underline",
                      // 보유·관심 기사를 강조한다 (기획 10.2.2). 꼬리표를 달지 않고
                      // 밝기로만 가른다 — 흐르는 줄에서 글자가 늘면 읽기가 더 어렵다.
                      item.relevance === "MARKET"
                        ? "text-muted-foreground"
                        : "text-foreground"
                    )}
                  >
                    {item.publishedGameDate && (
                      <span className="text-muted-foreground tabular-nums">
                        {item.publishedGameDate.slice(5).replace("-", "/")}
                      </span>
                    )}
                    <span>{item.headline}</span>
                  </Link>
                ))}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
