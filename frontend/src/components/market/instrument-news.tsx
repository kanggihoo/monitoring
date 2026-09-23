"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useStore } from "zustand";

import { gameDay } from "@/lib/format";
import { fetchNews } from "@/lib/market-api";
import type { NewsItem } from "@/lib/market-types";
import { cn, FOCUS_RING } from "@/lib/utils";
import { tickStore } from "@/stores/tick-store";

/** 종목 상세에 거는 기사 수. 여기는 미리보기이고 전부 보는 곳은 뉴스 탭이다. */
const PREVIEW_SIZE = 4;

/**
 * 종목 상세의 뉴스 (기획 10.4.3).
 *
 * **전용 경로를 쓰지 않는다** — `GET /api/v1/news?instrumentId=1` 이다. 같은 컬렉션의
 * 필터이지 다른 리소스가 아니다 (`nws.md`).
 *
 * 목록에서 헤드라인을 누르면 뉴스 탭의 그 기사로 간다. 여기에 본문을 펴지 않는 이유는
 * 차트·주문·정보 카드가 이미 이 칸을 나눠 쓰고 있어서다.
 */
export function InstrumentNews({ instrumentId }: { instrumentId: number }) {
  const [items, setItems] = useState<NewsItem[] | null>(null);

  // 새 기사가 붙는 순간 이 종목 것이 섞여 있을 수 있다. 그 때 다시 읽는다.
  const latestPushedId = useStore(tickStore, (state) => state.news[0]?.newsId);
  const gameDate = useStore(tickStore, (state) => state.gameDate);

  useEffect(() => {
    // 절단 기준이 필수라 시계를 기다린다 (`nws.md`).
    if (!gameDate) return;
    let cancelled = false;
    fetchNews(gameDate, { instrumentId, size: PREVIEW_SIZE })
      .then((fresh) => {
        if (!cancelled) setItems(fresh.items);
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.warn("종목 뉴스를 불러오지 못했습니다", error);
        setItems([]);
      });
    return () => {
      cancelled = true;
    };
  }, [instrumentId, latestPushedId, gameDate]);

  // 못 불러왔을 때와 기사가 없을 때를 같은 빈 칸으로 두지 않는다 — 둘 다 조용히 사라지면
  // 이 종목에 소식이 없는 것인지 조회가 깨진 것인지 알 수 없다. 로딩 중에는 자리만 비운다.
  if (items === null) return null;

  return (
    <div className="flex flex-col gap-2">
      <span className="flex items-baseline justify-between gap-2">
        <span className="text-xs text-muted-foreground">이 종목 뉴스</span>
        <Link
          href={`/news?instrumentId=${instrumentId}`}
          className={cn(
            FOCUS_RING,
            "rounded-sm text-xs text-muted-foreground hover:underline"
          )}
        >
          모아 보기 ↗
        </Link>
      </span>

      {items.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          이 종목에 전해진 소식이 아직 없습니다.
        </p>
      ) : (
        items.map((item) => (
          <Link
            key={item.newsId}
            href={`/news?instrumentId=${instrumentId}&focus=${item.newsId}`}
            className={cn(
              FOCUS_RING,
              "flex items-baseline gap-2 rounded-sm hover:underline"
            )}
          >
            <span className="shrink-0 text-2xs text-muted-foreground tabular-nums">
              {gameDay(item.publishedGameDate)}
            </span>
            <span className="min-w-0 flex-1 truncate text-xs">
              {item.headline}
            </span>
          </Link>
        ))
      )}
    </div>
  );
}
