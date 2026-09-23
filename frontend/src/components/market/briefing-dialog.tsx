"use client";

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

import { DialogRow } from "./dialog-row";

// 아래 상수·헬퍼는 `briefing-dialog.v2.tsx` 도 쓴다. 두 시안이 같은 문구·같은 계산을
// 보아야 형태 차이만 남는다 — 각자 적어 두면 한쪽만 고쳐져 비교가 깨진다. (`MARKETS` 만
// v2 가 제 것을 쓴다 — 그쪽은 갈래 이름을 칩으로 빼서 규모만 남기므로 짝만 맞춘다.)
// 시안이 정해지면 진 쪽과 함께 이 `export` 도 되돌린다.

/**
 * 재생 페이스. 계약이 주지 않는 **규칙**이라 화면 상수다 (기능명세서 0장) — 매일 같은 값이고
 * 응답에 실으면 바뀌지 않는 것을 매번 내려보내게 된다.
 *
 * **하루가 몇 개월인지는 적지 않는다.** 시작 시점과 함께 보면 종료 시점이 그대로 나오고,
 * 그것은 기획서 3.5.2 가 금지한 "당일 재생의 종료 지점"이다.
 */
export const PACE = "실제 12분 = 게임 1개월 · 종가 틱 약 35초";

/**
 * 유니버스 규모. 종목 목록 조회가 아니라 규칙을 적는다 — 브리핑이 답할 질문은 "오늘 무엇을
 * 할 수 있나"이지 "지금 몇 종이 상장돼 있나"가 아니다.
 */
export const MARKETS =
  "미국·국내·코인 20종 (1일차 동시 오픈) · IPO 상장 시 최대 28종";

/** 실제 시각의 시·분. 일정은 사람이 시계를 보고 맞추는 값이라 24시간제로 고정한다. */
const HHMM = new Intl.DateTimeFormat("ko-KR", {
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "Asia/Seoul",
});

export const at = (isoString: string) => HHMM.format(new Date(isoString));

export const minutesBetween = (from: string, to: string) =>
  Math.round((new Date(to).getTime() - new Date(from).getTime()) / 60_000);

/**
 * 개장 브리핑 (기획 3.5). 브리핑 구간(11:00~11:05)에 열린다.
 *
 * **재생 종료 지점·구간 내용·청약 종목명은 응답에 아예 없다** (기획서 3.5.2). 없는 것을
 * 다른 조회에서 끌어와 채우지 않는다 — 셋 중 무엇이든 화면에 놓으려면 계약부터 바꿔야 한다.
 */
export function BriefingDialog({
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

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="data-[size=default]:sm:max-w-lg">
        <AlertDialogHeader>
          <div className="flex flex-wrap items-center gap-2">
            {/* 시안은 배지에 브리핑 시각(11:00)을 적었지만 응답에 그 값이 없다 —
                일정이 주는 것은 재생 시작(11:05)부터이고, 그 시각은 아래 `오늘 일정`
                이 이미 말한다. 같은 숫자를 두 번 적는 대신 배지는 이름만 든다. */}
            <Badge variant="secondary">개장 브리핑</Badge>
            <span className="text-xs text-muted-foreground tabular-nums">
              시즌 01 · DAY {briefing.seasonDay}
            </span>
          </div>

          <AlertDialogTitle className="text-2xl">
            오늘은 {gameMonth(briefing.replayStartGameMonth)}에서 시작합니다
          </AlertDialogTitle>
        </AlertDialogHeader>

        {/* 재생 시작 시점은 제목이 말한다. 표에 한 줄 더 두면 같은 값을 두 번 읽게 된다. */}
        <dl className="flex flex-col text-sm">
          <DialogRow label="재생 페이스">{PACE}</DialogRow>
          <DialogRow label="오늘 일정">
            {at(schedule.tradingStartsAt)} 재생 · {at(schedule.feeFreeStartsAt)}{" "}
            무료 타임 {feeFreeMinutes}분 · {at(schedule.blackoutStartsAt)} 암전
            · {at(schedule.closesAt)} 폐장 · {at(schedule.settlementAt)} 정산
            발표
          </DialogRow>
          <DialogRow label="열린 시장">{MARKETS}</DialogRow>
          {/* 청약은 **시각만** 안내한다. 종목은 공시 시점에 공개된다 (`ipo.md`). */}
          {ipoWindows.length > 0 && (
            <DialogRow label="오늘 청약">
              {ipoWindows.map((window) => at(window.disclosureAt)).join(" · ")}{" "}
              공시 · 각{" "}
              {minutesBetween(
                ipoWindows[0]!.disclosureAt,
                ipoWindows[0]!.subscriptionClosesAt
              )}
              분간 접수
            </DialogRow>
          )}
        </dl>

        <AlertDialogFooter>
          {/* 닫기(X)를 따로 두지 않는다. 브리핑은 확인하고 들어가는 문이라 나가는 길이 하나다. */}
          <AlertDialogAction className="w-full" onClick={onAcknowledge}>
            확인하고 입장
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
