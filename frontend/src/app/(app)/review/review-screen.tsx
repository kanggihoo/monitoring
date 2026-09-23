"use client";

import { useState } from "react";

import { PriceChange } from "@/components/market/price-change";
import type { DailyReportEntry, InstrumentMeta } from "@/lib/market-types";
import { cn, FOCUS_RING, TAB_LIST } from "@/lib/utils";

import { DailyReportPanel } from "./daily-report";
import { levelOfAnswers, OnboardingPanel } from "./onboarding";
import { SeasonReportPanel } from "./season-report";
import { useSelfProfile } from "./self-profile";

/** 아직 열리지 않은 구역을 알리는 배너. 시안의 '열람 대기' 줄이다. */
export function LockedNotice({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-lg bg-destructive-surface px-3.5 py-2.5">
      <span className="shrink-0 text-xs font-semibold text-destructive">
        열람 대기
      </span>
      <span className="text-xs text-destructive">{children}</span>
    </div>
  );
}

/**
 * 복기 화면. **선택 줄 하나가 무엇을 볼지 정한다** — 일차 셋과 시즌 결산이 같은 줄에 앉는다.
 *
 * 예전에는 '오늘의 복기 / 시즌 리포트' 탭이 위에 한 줄 더 있었다. 리포트가 매일 나오고
 * 그때까지를 누적해서 보는 설계로 바뀌면서 두 줄이 같은 것을 두 번 묻게 됐다 — 일차를
 * 고르는 줄과 종류를 고르는 줄이 나란히 있으면 무엇이 무엇을 바꾸는지 읽히지 않는다.
 *
 * 조회는 각 패널이 한다. 시즌 결산을 열지 않으면 부르지 않는다 (3일차 발표 전에는 409 다).
 *
 * 열람 가능한 일차 중 **가장 나중 것**을 처음에 연다. 방금 끝난 하루를 보러 오는 화면이다.
 */
export function ReviewScreen({
  days,
  instruments,
}: {
  days: DailyReportEntry[];
  instruments: InstrumentMeta[];
}) {
  // 시즌 결산은 마지막 일차가 발표된 뒤에 열린다. 그 전에는 조회 자체가 409 라 눌리지 않는다.
  const seasonOpen = days.length > 0 && (days.at(-1)?.available ?? false);
  // **처음 보이는 것은 '나의 투자 성향' 이다.** 선언이 있어야 결과지의 ③⑦ 이 생기므로,
  // 복기에 처음 들어온 사람이 그 자리를 먼저 보게 둔다. 일차 리포트는 알약으로 바로 간다.
  const [view, setView] = useState<number | "season" | "onboarding">(
    "onboarding"
  );
  // 이미 답했는지는 서버가 안다 (`GET /api/v1/onboarding/self-profile`).
  const profile = useSelfProfile();
  const selfLevel = levelOfAnswers(profile.answers);

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      {/* 일차와 시즌 결산을 한 줄에 둔다. 아직 도달하지 않은 일차는 누를 수 없다 —
          미래 차단이다. 일차마다 그날 수익률을 함께 달아 세 버튼이 구별되게 한다.

          **좁은 화면에서는 줄을 옆으로 굴린다.** 그냥 두면 알약 다섯이 눌려서 '나의 투자
          성향' 이 한 글자씩 세로로 쌓이고, 375px 에서 줄 높이가 129px 까지 뛴다. 넘치는 만큼
          가로로 미는 편이 낫다 — 랭킹·뉴스 화면의 탭 줄이 쓰는 방법과 같다. */}
      <div
        role="group"
        aria-label="복기 대상 선택"
        className={cn(
          TAB_LIST,
          // `shrink-0` 이 없으면 **줄이 짜부라진다.** `overflow-x-auto` 를 주는 순간 이
          // 줄은 스크롤 컨테이너가 되고, 스크롤 컨테이너는 `min-height:auto` 의 보호를
          // 잃어 세로 flex 부모가 높이를 빼앗아 간다 — 높이가 빠듯한 데서 줄이 8px 로
          // 눌리고 27px 짜리 알약이 잘려 나갔다 (스토리북에서 그렇게 보였다).
          "flex w-fit max-w-full shrink-0 items-center gap-1 overflow-x-auto"
        )}
      >
        {/* 설문은 일차와 성격이 다르다 — 리포트를 고르는 것이 아니라 **내가 답하는**
            자리다. 그래서 맨 왼쪽에 두고 구분선으로 떼어 둔다. 이미 답했으면 그 단계를
            곁들여, 다시 답하러 들어갈 이유가 있는지 알 수 있게 한다. */}
        <button
          type="button"
          aria-pressed={view === "onboarding"}
          onClick={() => setView("onboarding")}
          className={cn(PILL, pillTone(view === "onboarding", true))}
        >
          나의 투자 성향
          <span
            className={cn(
              "text-2xs",
              view === "onboarding"
                ? "text-primary-foreground/80"
                : "text-muted-foreground"
            )}
          >
            {/* 답하지 않아도 리포트는 그대로 나온다 (AI 쪽이 ③⑦ 만 비운다). '아직' 으로
                적으면 남은 숙제처럼 읽혀서 '선택 사항' 이라고 적는다.

                **불러오는 중에는 비워 둔다.** 아직 모르는 것을 '선택 사항' 이라고 적으면,
                이미 답한 사람에게 그 글자가 한 번 번쩍이고 단계로 바뀐다. */}
            {profile.status === "loading"
              ? ""
              : selfLevel === null
                ? "선택 사항"
                : `${selfLevel}단계`}
          </span>
        </button>
        <span aria-hidden className="mx-0.5 h-4 w-px bg-border" />

        {days.map((day) => {
          const selected = view === day.seasonDay;
          return (
            <button
              key={day.seasonDay}
              type="button"
              disabled={!day.available}
              aria-pressed={selected}
              onClick={() => setView(day.seasonDay)}
              className={cn(PILL, pillTone(selected, day.available))}
            >
              DAY {day.seasonDay}
              {day.available && day.dailyReturn !== null ? (
                // 색만으로 방향을 말하지 않는다 — 부호와 ▲▼ 를 같이 내는 `PriceChange` 를
                // 쓴다 (DESIGN.md). 고른 알약 위에서는 배경이 어두워 색을 글자색으로 덮고
                // 부호·기호로 방향을 남긴다.
                <PriceChange
                  value={day.dailyReturn}
                  digits={1}
                  className={cn(
                    "text-2xs",
                    selected && "text-primary-foreground/80"
                  )}
                />
              ) : (
                !day.available && <span className="text-2xs">미도래</span>
              )}
            </button>
          );
        })}

        {/* 일차와 성격이 다른 선택이라 구분선을 둔다 — 하루가 아니라 시즌 전체의 성적이다. */}
        <span aria-hidden className="mx-0.5 h-4 w-px bg-border" />
        <button
          type="button"
          disabled={!seasonOpen}
          aria-pressed={view === "season"}
          onClick={() => setView("season")}
          className={cn(PILL, pillTone(view === "season", seasonOpen))}
        >
          시즌 결산
          {!seasonOpen && <span className="text-2xs">마지막 날 공개</span>}
        </button>
      </div>

      {view === "onboarding" ? (
        <OnboardingPanel />
      ) : view === "season" ? (
        <SeasonReportPanel days={days} instruments={instruments} />
      ) : (
        <DailyReportPanel
          key={view}
          seasonDay={view}
          entry={days.find((day) => day.seasonDay === view)}
          instruments={instruments}
          onStartOnboarding={() => setView("onboarding")}
        />
      )}
    </div>
  );
}

/** 선택 줄의 알약 하나. 위 두 줄이 한 줄로 합쳐지면서 모양도 하나로 모았다. */
const PILL =
  "flex shrink-0 items-center gap-1.5 rounded-[10px] px-2.5 py-1 text-[12.5px] whitespace-nowrap transition-colors";

/** 고른 것 · 누를 수 있는 것 · 아직 못 누르는 것. 셋이 한눈에 갈려야 한다. */
function pillTone(selected: boolean, enabled: boolean) {
  if (selected) return "bg-primary font-semibold text-primary-foreground";
  if (enabled) return `${FOCUS_RING} text-foreground/70 hover:bg-muted`;
  return "cursor-not-allowed text-muted-foreground/60";
}
