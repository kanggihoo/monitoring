"use client";

import {
  Bitcoin,
  EyeOff,
  Gauge,
  Globe,
  Landmark,
  Moon,
  Play,
  Ticket,
  Trophy,
  Zap,
} from "lucide-react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { gameMonth } from "@/lib/format";
import type { Briefing } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { at, minutesBetween, PACE } from "./briefing-dialog";
import { CATEGORY_LABEL } from "./labels";

/**
 * 유니버스 **규모**만. v1 의 `MARKETS` 는 "갈래 이름 + 규모"가 한 문장이지만
 * (`나스닥·국내·코인 20종 …`) v2 는 이름을 칩으로 빼므로 규모만 남는다 — 칩이 `코스피` 라
 * 부르는 갈래를 같은 화면에서 `국내` 라고 또 부르면 같은 것을 둘로 알게 된다.
 *
 * **v1 의 `MARKETS` 와 짝이다.** 한쪽 숫자를 고치면 다른 쪽도 고친다. 시안이 정해지면 진
 * 쪽과 함께 지운다.
 */
const MARKET_SCALE = "1일차 20종 동시 오픈 · IPO 상장 시 최대 28종";

/**
 * 개장 브리핑 시안 (v2). **담는 내용은 v1 과 같고 배치만 다르다** — 기획서 3.5.2 가 금지한
 * 셋(재생 종료 지점·구간 내용·청약 종목명)은 여기서도 없다. 계약에 없는 값을 다른 조회에서
 * 끌어오지도 않는다.
 *
 * v1 이 네 줄짜리 정의 목록이라면 이쪽은 **오늘 일정을 세로 타임라인으로 편다.** v1 의
 * `오늘 일정` 한 줄에는 시각이 다섯 개 들어 있어 (`11:05 재생 · 12:00 무료 10분 · 16:00
 * 암전 · 16:40 폐장 · 17:00 정산`) 한 문장으로 읽히지 않는다 — 브리핑을 읽는 5분 동안
 * 사람이 하는 일은 "몇 시에 무엇"을 눈으로 집어내는 것이라, 줄을 나누고 시각을 왼쪽에
 * 세로로 맞추면 훑기가 훨씬 빠르다.
 *
 * **아이콘은 장식이 아니라 줄을 가르는 표식이다.** 전부 `aria-hidden` 이고 뜻은 옆의 글자가
 * 진다 — 아이콘만 보고 `암전` 과 `폐장` 을 가를 수 있어야 하는 것이 아니라, 다섯 줄이
 * 시각적으로 서로 다른 줄이라는 것만 알면 된다.
 *
 * **색은 쓰지 않는다.** 무료 타임만 `accent` 무채색 면으로 한 칸 띄운다 —
 * `fee-event-banner.tsx` 가 같은 이유로 같은 선택을 하고 있다 (초록은 신호등 한 곳,
 * DESIGN.md).
 */
export function BriefingDialogV2({
  briefing,
  open,
  onOpenChange,
  onAcknowledge,
}: {
  /** 아직 못 받았으면 null — 그동안은 아무것도 그리지 않는다. */
  briefing: Briefing | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAcknowledge: () => void;
}) {
  if (!briefing) {
    return null;
  }

  const { schedule, ipoWindows } = briefing;
  const feeFreeMinutes = minutesBetween(
    schedule.feeFreeStartsAt,
    schedule.feeFreeEndsAt
  );

  /**
   * 오늘의 다섯 지점. 순서는 시각 순이고 **무료 타임만 끝 시각을 함께 적는다** — 나머지는
   * 그 시점부터 상태가 바뀌어 끝이 다음 줄이지만, 무료는 구간이라 길이를 모르면 언제까지
   * 서둘러야 하는지 알 수 없다.
   */
  const timeline = [
    { icon: Play, at: schedule.tradingStartsAt, label: "재생 시작" },
    {
      icon: Zap,
      at: schedule.feeFreeStartsAt,
      label: "수수료 무료 타임",
      note: `${feeFreeMinutes}분간 · 거래 수수료 0%`,
      accent: true,
    },
    {
      icon: EyeOff,
      at: schedule.blackoutStartsAt,
      label: "랭킹 암전",
      note: "순위가 가려진다",
    },
    {
      icon: Moon,
      at: schedule.closesAt,
      label: "폐장",
      note: "미체결 예약 소멸",
    },
    { icon: Trophy, at: schedule.settlementAt, label: "정산 발표" },
  ];

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="data-[size=default]:sm:max-w-lg">
        <AlertDialogHeader>
          <div className="flex flex-wrap items-center gap-2">
            {/* 배지에 브리핑 시각(11:00)을 적지 않는 것은 v1 과 같다 — 응답에 그 값이 없다. */}
            <Badge variant="secondary">개장 브리핑</Badge>
            <span className="text-xs text-muted-foreground tabular-nums">
              시즌 01 · DAY {briefing.seasonDay}
            </span>
          </div>

          <AlertDialogTitle className="text-2xl">
            오늘은 {gameMonth(briefing.replayStartGameMonth)}에서 시작합니다
          </AlertDialogTitle>

          {/* 페이스는 제목 바로 아래 한 줄로 붙인다. 일정표의 한 항목이 아니라 오늘 하루
              전체에 걸리는 성질이라, 시각이 붙은 줄들과 같은 자리에 두면 섞인다. */}
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Gauge className="size-3.5 shrink-0" aria-hidden />
            {PACE}
          </p>
        </AlertDialogHeader>

        <div className="flex flex-col gap-4">
          <section className="flex flex-col gap-1">
            <SectionLabel>오늘 일정</SectionLabel>
            <ol className="flex flex-col">
              {timeline.map(({ icon: Icon, at: when, label, note, accent }) => (
                <li
                  key={label}
                  className={cn(
                    "flex items-center gap-3 rounded-md px-2 py-1.5",
                    accent && "bg-accent text-accent-foreground"
                  )}
                >
                  <Icon
                    className="size-4 shrink-0 text-muted-foreground"
                    aria-hidden
                  />
                  {/* 시각을 왼쪽에 세로로 맞춘다 — 훑을 때 눈이 따라가는 축이다. */}
                  <span className="w-11 shrink-0 text-sm font-medium tabular-nums">
                    {at(when)}
                  </span>
                  <span className="min-w-0 text-sm">{label}</span>
                  {note && (
                    <span className="ml-auto shrink-0 text-2xs text-muted-foreground">
                      {note}
                    </span>
                  )}
                </li>
              ))}
            </ol>
          </section>

          <section className="flex flex-col gap-1.5">
            <SectionLabel>열린 시장</SectionLabel>
            {/* 갈래 셋은 아이콘으로 가른다. 여기서는 아이콘이 이름과 1:1 이라 표식으로서
                제 몫을 한다 — 종목 수는 갈래가 아니라 규칙이므로 아래 한 줄로 뺀다.
                이름은 `CATEGORY_LABEL` 을 그대로 쓴다. 종목 목록·정보 카드가 부르는 이름과
                브리핑이 부르는 이름이 다르면 같은 갈래를 둘로 알게 된다. */}
            <div className="flex flex-wrap items-center gap-1.5">
              {(
                [
                  ["NASDAQ", Globe],
                  ["KRX", Landmark],
                  ["CRYPTO", Bitcoin],
                ] as const
              ).map(([category, Icon]) => (
                <span
                  key={category}
                  className="inline-flex items-center gap-1.5 rounded-md border border-border px-2 py-1 text-xs"
                >
                  <Icon
                    className="size-3.5 text-muted-foreground"
                    aria-hidden
                  />
                  {CATEGORY_LABEL[category]}
                </span>
              ))}
            </div>
            <p className="px-0.5 text-2xs text-muted-foreground">
              {MARKET_SCALE}
            </p>
          </section>

          {/* 청약은 **시각만** 안내한다. 종목은 공시 시점에 공개된다 (`ipo.md`). */}
          {ipoWindows.length > 0 && (
            <section className="flex flex-col gap-1.5">
              <SectionLabel>오늘 청약</SectionLabel>
              <div className="flex flex-wrap items-center gap-2">
                {ipoWindows.map((window) => (
                  <span
                    key={window.disclosureAt}
                    className="inline-flex items-center gap-1.5 rounded-md border border-border px-2 py-1 text-xs tabular-nums"
                  >
                    <Ticket
                      className="size-3.5 text-muted-foreground"
                      aria-hidden
                    />
                    {at(window.disclosureAt)} 공시
                  </span>
                ))}
                <span className="text-2xs text-muted-foreground">
                  각{" "}
                  {minutesBetween(
                    ipoWindows[0]!.disclosureAt,
                    ipoWindows[0]!.subscriptionClosesAt
                  )}
                  분간 접수
                </span>
              </div>
            </section>
          )}
        </div>

        <AlertDialogFooter>
          {/* 닫기(X)를 따로 두지 않는 것도 v1 과 같다 — 확인하고 들어가는 문이다. */}
          <AlertDialogAction className="w-full" onClick={onAcknowledge}>
            확인하고 입장
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <span className="px-0.5 text-2xs tracking-wider text-muted-foreground uppercase">
      {children}
    </span>
  );
}
