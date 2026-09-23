"use client";

import { useEffect, useRef } from "react";
import { Newspaper } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { gameDay } from "@/lib/format";
import type { NewsItem } from "@/lib/market-types";
import { cn } from "@/lib/utils";

/** 뉴스 채널. 값은 계약 enum 그대로다 (`nws.md`). */
const CHANNEL_LABEL: Record<NewsItem["channel"], string> = {
  US: "미국",
  KR: "국내",
  CRYPTO: "코인",
};

/** 보유 > 관심 > 일반 순으로 노출한다 (기획 10장 우선 노출). */
const RELEVANCE_LABEL: Record<
  Exclude<NewsItem["relevance"], "MARKET">,
  string
> = {
  HOLDING: "보유",
  WATCHLIST: "관심",
};

const ORDER: Record<NewsItem["relevance"], number> = {
  HOLDING: 0,
  WATCHLIST: 1,
  MARKET: 2,
};

/** 바닥에 닿기 전에 미리 부른다 — 닿고 나서 부르면 빈 화면을 잠깐 보게 된다. */
const PREFETCH_PX = 240;

function NewsFeed({
  items,
  hasNext = false,
  onReachEnd,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  items: NewsItem[];
  /** 더 펼 페이지가 남았는지. 바닥 안내 문구를 가른다. */
  hasNext?: boolean;
  /** 목록 바닥이 가까워지면 부른다. 주지 않으면 더 불러오지 않는다. */
  onReachEnd?: () => void;
}) {
  const listRef = useRef<HTMLDivElement>(null);

  // 서버가 준 순서를 그대로 쓴다 — 우선 노출(보유 > 관심 > 일반)은 서버가 한다 (`nws.md`).
  // 페이지를 이어 붙이는 목록이라 화면에서 다시 정렬하면, 뒤늦게 온 장에 보유 기사가 섞일 때
  // 이미 읽고 있던 줄이 위로 튄다.
  const sorted = onReachEnd
    ? items
    : [...items].sort((a, b) => ORDER[a.relevance] - ORDER[b.relevance]);

  /**
   * 과거 방향 무한 스크롤 (기획 10.3.2). 듣는 대상은 브라우저 창이 아니라 **목록이 실제로
   * 스크롤하는 상자**다 — 창은 움직이지 않으므로 창에 붙이면 영원히 불리지 않는다
   * (뉴스 탭과 같은 방식).
   */
  useEffect(() => {
    const viewport = listRef.current?.closest(
      "[data-slot='scroll-area-viewport']"
    );
    if (!viewport || !onReachEnd || !hasNext) return;

    const onScroll = () => {
      const remaining =
        viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
      if (remaining < PREFETCH_PX) onReachEnd();
    };

    onScroll(); // 첫 장이 상자를 못 채우면 스크롤이 없어 영원히 안 불린다.
    viewport.addEventListener("scroll", onScroll, { passive: true });
    return () => viewport.removeEventListener("scroll", onScroll);
  }, [hasNext, onReachEnd, items.length]);

  return (
    <Card className={cn("flex w-full max-w-md flex-col", className)} {...props}>
      <CardHeader className="shrink-0">
        <CardTitle>뉴스</CardTitle>
      </CardHeader>

      {sorted.length === 0 ? (
        <CardContent className="flex flex-col items-center justify-center gap-2 py-8 text-center">
          <Newspaper className="size-8 text-muted-foreground/40" />
          <p className="text-sm font-medium text-muted-foreground">
            이 구간에는 전해진 소식이 없습니다
          </p>
        </CardContent>
      ) : (
        <ScrollArea className="min-h-0 flex-1">
          <CardContent className="flex flex-col" ref={listRef}>
            {sorted.map((item, i) => (
              <div key={item.newsId}>
                {i > 0 && <Separator className="my-2.5" />}
                <article className="flex flex-col gap-1">
                  <div className="flex items-center gap-1.5">
                    <span className="text-2xs text-muted-foreground tabular-nums">
                      {gameDay(item.publishedGameDate)}
                    </span>
                    <Badge variant="ghost" className="h-4 px-1 text-2xs">
                      {CHANNEL_LABEL[item.channel]}
                    </Badge>
                    {item.relevance !== "MARKET" && (
                      <Badge variant="secondary" className="h-4 px-1 text-2xs">
                        {RELEVANCE_LABEL[item.relevance]}
                      </Badge>
                    )}
                  </div>
                  <p className="text-sm text-foreground-subtle">
                    {item.headline}
                  </p>
                </article>
              </div>
            ))}
          </CardContent>
        </ScrollArea>
      )}
    </Card>
  );
}

export { NewsFeed };
