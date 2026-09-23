"use client";

import { useEffect, useState } from "react";

import { BenchmarkBars } from "@/components/market/benchmark-bars";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchDailyReport, fetchSeasonReport } from "@/lib/market-api";
import type {
  DailyReportEntry,
  InstrumentMeta,
  PatternTag,
  ReportInstrument,
  SeasonReport,
} from "@/lib/market-types";

import { LockedNotice } from "./review-screen";
import { Column, MetricTiles, SectionLabel, TradeReplayCard } from "./sections";
import { WhatIfCard } from "./what-if";

/**
 * 시즌 전체의 매매 지점을 일차 리포트들에서 모은다.
 *
 * **시즌 리포트 응답에는 종목·마커가 없다** — 계약이 그것을 일차 리포트에만 두었기 때문이다
 * (`rpt.md`). 일차마다 구간이 이어지므로 종목별로 이어 붙이면 시즌 전체가 된다. 새 엔드포인트를
 * 만들지 않고 이미 있는 조회를 합치는 쪽을 골랐다.
 *
 * @param days 일차 순서대로 온 리포트. 순서가 곧 시간 순서다
 */
function mergeSeasonInstruments(
  days: Awaited<ReturnType<typeof fetchDailyReport>>[]
): ReportInstrument[] {
  const merged = new Map<number, ReportInstrument>();

  for (const day of days) {
    for (const item of day?.instruments ?? []) {
      const found = merged.get(item.instrumentId);
      if (!found) {
        merged.set(item.instrumentId, { ...item });
        continue;
      }
      // 일차 경계의 거래일이 겹칠 수 있다. 종가는 같은 날 하나만 남긴다.
      const seen = new Set(found.points.map((point) => point.gameDate));
      found.points = found.points.concat(
        item.points.filter((point) => !seen.has(point.gameDate))
      );
      found.markers = found.markers.concat(item.markers);
    }
  }

  return [...merged.values()];
}

/**
 * 시즌 리포트 (기획 13.3). 3일차 정산 발표 전에는 계약이 409 로 답하므로 실패가 아니라
 * '열람 대기' 로 그린다 (`rpt.md`).
 *
 * 시안이 두 탭에 같은 3열 골격을 쓰므로 조각은 `sections.tsx` 를 공유한다.
 */
export function SeasonReportPanel({
  days,
  instruments,
}: {
  /** 일차 목록. 열린 일차의 리포트를 모아 시즌 전체의 매매 지점을 만든다. */
  days: DailyReportEntry[];
  instruments: InstrumentMeta[];
}) {
  const [report, setReport] = useState<SeasonReport | null>(null);
  const [replay, setReplay] = useState<ReportInstrument[]>([]);
  const [replayTags, setReplayTags] = useState<PatternTag[]>([]);
  const [state, setState] = useState<"loading" | "locked" | "ready" | "failed">(
    "loading"
  );

  useEffect(() => {
    let cancelled = false;
    fetchSeasonReport()
      .then((fresh) => {
        if (cancelled) return;
        setReport(fresh);
        setState(fresh ? "ready" : "locked");
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.error("시즌 리포트를 불러오지 못했습니다", error);
        setState("failed");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 매매 지점은 시즌 리포트와 별개로 모은다 — 실패해도 지표는 보여야 한다.
  const openDays = days
    .filter((day) => day.available)
    .map((day) => day.seasonDay)
    .join(",");
  useEffect(() => {
    if (!openDays) return;
    let cancelled = false;
    Promise.all(openDays.split(",").map((day) => fetchDailyReport(Number(day))))
      .then((reports) => {
        if (cancelled) return;
        setReplay(mergeSeasonInstruments(reports));
        setReplayTags(reports.flatMap((day) => day?.patternTags ?? []));
      })
      .catch((error: unknown) =>
        console.warn("시즌 매매 지점을 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
  }, [openDays]);

  if (state === "locked") {
    return (
      <LockedNotice>
        시즌 리포트는 3일차 정산 발표(17:00)부터 열립니다.
      </LockedNotice>
    );
  }

  if (state === "failed") {
    return (
      <Card size="sm">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          시즌 리포트를 불러오지 못했습니다. 잠시 뒤 다시 열어 보세요.
        </CardContent>
      </Card>
    );
  }

  if (state === "loading" || !report) {
    return <Skeleton className="min-h-96 flex-1 rounded-lg" />;
  }

  const nameOf = (instrumentId: number) =>
    instruments.find((item) => item.instrumentId === instrumentId)?.name ??
    `종목 ${instrumentId}`;

  return (
    <>
      {/* ── 시즌 성적 ── '오늘의 정산 요약' 과 같은 자리다.
          `shrink-0` — 아래 3열이 내용만큼 자리를 요구하면 이 카드가 대신 눌려 타일이
          잘린다. 오늘의 복기와 같은 처방이다. */}
      <Card size="sm" className="shrink-0">
        <CardContent className="flex items-baseline justify-between gap-3">
          <SectionLabel>시즌 성적</SectionLabel>
          <span className="text-xs text-muted-foreground">
            시즌 종료 · 확정된 기록
          </span>
        </CardContent>
        <CardContent>
          <MetricTiles
            metrics={report.metrics}
            finalRank={report.finalRank}
            totalParticipants={report.totalParticipants}
          />
        </CardContent>
      </Card>

      {/* ── 3열 본문 (오늘의 복기와 같은 골격) ──
          **`min-h-0` 을 주지 않는다.** 주면 그리드가 내용보다 작아지면서 그만큼 카드
          안쪽이 잘린다 — 기준선·만약에·유형 카드가 그렇게 잘려 있었다. 넘치는 만큼은
          셸이 스크롤한다 (`app/(app)/layout.tsx` 의 `md:overflow-y-auto`). */}
      <div className="grid flex-1 gap-3 xl:grid-cols-2">
        <Column className="min-h-0">
          <TradeReplayCard
            instruments={replay}
            patternTags={replayTags}
            nameOf={nameOf}
            emptyNote="이번 시즌에 체결한 거래가 없다."
          />
        </Column>

        <Column>
          <Card size="sm">
            <CardContent className="flex flex-col gap-3">
              <SectionLabel>가만히 있었다면</SectionLabel>
              <BenchmarkBars benchmarks={report.benchmarks} />
              <p className="text-2xs text-muted-foreground">
                지수를 시즌 내내 들고만 있었을 때의 수익률이다. 수수료와 환전
                수수료를 반영하지 않는다.
              </p>
            </CardContent>
          </Card>

          {/* 위 카드의 짝이다 — 저쪽은 고정된 지수 기준선이고 이쪽은 28 종을 눌러 가며
              고르는 쪽이다 (기획 13.3.4). 같은 "들고만 있었다면" 질문이라 같은 열에 둔다. */}
          <WhatIfCard instruments={instruments} />
        </Column>
      </div>
    </>
  );
}
