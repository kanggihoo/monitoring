"use client";

import { useEffect, useState } from "react";

import { PriceChange } from "@/components/market/price-change";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { gameMonth, krw, signed } from "@/lib/format";
import { fetchDailyReport } from "@/lib/market-api";
import type {
  DailyReport,
  DailyReportEntry,
  InstrumentMeta,
} from "@/lib/market-types";

import { ReviewNarrativeCard, ReviewProfileCard } from "./review-card";
import { LockedNotice } from "./review-screen";
import {
  ClosingAssetsDialog,
  Column,
  InvestorTypeCard,
  SectionLabel,
  Tile,
  TradeReplayCard,
} from "./sections";

/**
 * 오늘의 복기 (기획 13.1, 시안 복기 탭).
 *
 * 정산 발표 전 조회는 계약이 409 로 정한 정상 상태이므로 실패가 아니라 '열람 대기' 로
 * 그린다 — 비어 있는 것과 못 불러온 것을 구별할 수 없게 만들지 않는다 (`rpt.md`).
 */
export function DailyReportPanel({
  seasonDay,
  entry,
  instruments,
  onStartOnboarding,
}: {
  seasonDay: number;
  entry: DailyReportEntry | undefined;
  instruments: InstrumentMeta[];
  /** 결과지에 선언이 없을 때 설문으로 보낼 자리. 화면 전환은 선택 줄이 쥐고 있다. */
  onStartOnboarding?: () => void;
}) {
  const available = entry?.available ?? false;
  const [report, setReport] = useState<DailyReport | null>(null);
  // 부르는 쪽이 일차마다 `key` 로 다시 마운트하므로 첫 상태를 그 일차의 열람 여부로 잡는다.
  const [state, setState] = useState<"loading" | "locked" | "ready" | "failed">(
    available ? "loading" : "locked"
  );

  useEffect(() => {
    if (!available) return;
    let cancelled = false;
    fetchDailyReport(seasonDay)
      .then((fresh) => {
        if (cancelled) return;
        setReport(fresh);
        setState(fresh ? "ready" : "locked");
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.error("일일 복기를 불러오지 못했습니다", error);
        setState("failed");
      });
    return () => {
      cancelled = true;
    };
  }, [seasonDay, available]);

  const nameOf = (instrumentId: number) =>
    instruments.find((item) => item.instrumentId === instrumentId)?.name ??
    `종목 ${instrumentId}`;

  if (state === "locked") {
    return (
      <LockedNotice>
        {seasonDay}일차 복기는 정산 발표(17:00)부터 열립니다. 지난 일차 리포트는
        지금도 볼 수 있습니다.
      </LockedNotice>
    );
  }

  if (state === "failed") {
    return (
      <Card size="sm">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          복기를 불러오지 못했습니다. 잠시 뒤 다시 열어 보세요.
        </CardContent>
      </Card>
    );
  }

  if (state === "loading" || !report) {
    return <Skeleton className="min-h-96 flex-1 rounded-lg" />;
  }

  // **손실이 난 거래일 때만 '최악' 이 성립한다.** 다 벌고 끝난 날의 두 번째로 잘한 거래를
  // 최악이라고 부르면 없는 실수를 만든다 — 그때는 그 줄을 그리지 않는다.
  const worstLoss =
    report.worstTrade && report.worstTrade.returnRate < 0
      ? report.worstTrade
      : null;

  return (
    <>
      {/* ── 오늘의 정산 요약 ──
          `shrink-0` — 아래 3열이 내용만큼 자리를 요구하면 이 카드가 대신 눌려서 타일이
          잘린다. 눌리는 카드를 만들지 않고, 넘치는 만큼 페이지가 스크롤되게 둔다. */}
      <Card size="sm" className="shrink-0">
        <CardContent className="flex items-center justify-between gap-3">
          <SectionLabel>오늘의 정산 요약</SectionLabel>
          <span className="flex items-center gap-3">
            {/* 매번 읽는 값이 아니라 확인할 때만 여는 상세라 열을 내주지 않는다.
                구성이 없는 옛 일차는 버튼째 그려지지 않는다. */}
            <ClosingAssetsDialog
              seasonDay={report.seasonDay}
              closing={report.closing}
              holdings={report.closingHoldings}
              nameOf={nameOf}
            />
            <span className="text-xs text-muted-foreground">
              재생 구간 {gameMonth(report.fromGameMonth)} ~{" "}
              {gameMonth(report.toGameMonth)}
            </span>
          </span>
        </CardContent>
        {/* AI 가 만든 그날의 문구 (`rpt.md`). **없으면 자리를 비운다** — 외부 호출이
            실패해도 복기는 열려야 한다. 장면 별칭이 빈 배열인 것은 실패가 아니라
            "조용한 하루" 라, 머리말만 그리고 칩 줄을 생략한다. */}
        {report.commentary && (
          <CardContent className="flex flex-col gap-2">
            <p className="text-lg leading-snug font-medium">
              {report.commentary.headline}
            </p>
            {report.commentary.sceneAliases.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {report.commentary.sceneAliases.map((alias) => (
                  <Badge key={alias} variant="secondary">
                    {alias}
                  </Badge>
                ))}
              </div>
            )}
          </CardContent>
        )}
        <CardContent className="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
          <Tile
            label="일일 수익률"
            value={<PriceChange value={report.summary.dailyReturn} />}
          />
          <Tile label="실현 손익" value={krw(report.summary.realizedPnlKrw)} />
          <Tile
            label="체결"
            value={`${report.summary.executionCount.toLocaleString("ko-KR")}회`}
            hint={`수수료 ${krw(report.summary.totalFeeKrw)}`}
          />
          {/* 두 거래를 한 칸에 담으므로 **양쪽 다 어느 종목인지 적는다.** 위는 종목명이
              없고 아래만 있으면, 아래 숫자가 위 숫자의 부연인지 다른 거래인지 갈리지 않는다.

              아래 줄에는 '최악' 이라고 쓰지 않고 **▼** 를 쓴다 — 위 줄의 ▲ 와 짝이 되어
              두 줄이 서로 다른 방향의 거래라는 것이 한눈에 읽힌다. */}
          <Tile
            label={worstLoss ? "최고 · 최악 거래" : "최고 거래"}
            value={
              report.bestTrade ? (
                <span className="flex items-baseline gap-1.5">
                  <PriceChange value={report.bestTrade.returnRate} />
                  <span className="text-xs text-muted-foreground">
                    {nameOf(report.bestTrade.instrumentId)}
                  </span>
                </span>
              ) : (
                "—"
              )
            }
            hint={
              worstLoss ? (
                <span className="inline-flex items-baseline gap-1 text-price-down">
                  <span aria-hidden>▼</span>
                  <span className="text-muted-foreground">
                    {nameOf(worstLoss.instrumentId)}
                  </span>
                  <span className="tabular-nums">
                    {signed(worstLoss.returnRate, 1)}%
                  </span>
                </span>
              ) : report.bestTrade ? undefined : (
                "매도 체결이 없다"
              )
            }
          />
        </CardContent>
      </Card>

      {/* ── 3열 본문 (시안 골격) ──
          **결과지가 오면 열을 다시 나눈다.** 결과지를 이 아래에 형제로 붙였더니 1440×900
          에서 화면이 600px 넘쳤다 — 이 그리드가 `flex-1` 로 남은 높이를 채워 한 화면에
          맞추는 구조라, 아래에 더하면 그 설계가 깨진다.

          결과지가 없을 때(백엔드가 아직 안 채운 지금)는 **배포된 그대로** 셋을 그린다. */}
      <div className="grid flex-1 gap-3 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_22.5rem]">
        <Column className="min-h-0">
          <TradeReplayCard
            instruments={report.instruments}
            patternTags={report.patternTags}
            nameOf={nameOf}
            emptyNote="이 날 체결한 거래가 없다."
          />
        </Column>

        {/* 결과지가 없으면 이 열은 비운다 — 빈 카드를 두 개 그리면 무엇을 기다리는
            중인지가 더 흐려진다. 자리는 오른쪽 유형 카드가 지킨다. */}
        <Column>
          {report.review && (
            <ReviewNarrativeCard
              review={report.review}
              onStartOnboarding={onStartOnboarding}
            />
          )}
        </Column>

        <Column>
          {report.review ? (
            <ReviewProfileCard review={report.review} />
          ) : (
            // 결과지가 없으면 유형 카드가 자리만 지킨다 (배포된 모양).
            <InvestorTypeCard investorType={null} />
          )}
        </Column>
      </div>
    </>
  );
}
