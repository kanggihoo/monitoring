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
import { gameMonth, krw } from "@/lib/format";
import type { AbsenceSummary as Summary } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { DialogRow } from "./dialog-row";
import { PriceChange } from "./price-change";

/**
 * 부재 중 요약 (기획 7.5). 11~17시에 계속 붙어 있지 못해도 게임에서 지지 않는다는 것을
 * 재접속 첫 화면에서 보여 준다.
 *
 * **나가는 길이 하나다.** 버튼이든 Esc 든 닫으면 모달이 보여준 `toTickSeq` 를 확인으로
 * 돌려보낸다.
 *
 * 계약(`acc.md`)은 닫기와 확인을 다른 동작으로 두라고 적는다 — 확인하지 않고 나가면 다음
 * 접속에 다시 뜨는 것이 그쪽의 의도다. 여기서는 **화면 결정으로 둘을 합쳤다**: 브리핑과 같이
 * 버튼이 하나이므로 "닫았지만 확인하지 않은" 상태를 사용자가 구분할 방법이 없다.
 * 되살리려면 `onOpenChange` 에서 확인을 떼면 된다.
 *
 * 그리는 값은 **응답에 있는 것뿐이다.** 실제 부재 시간(분)·이벤트 종류별 내역·알림 센터는
 * 시안에 있지만 계약에 없다 — 채우려면 계약부터 바꿔야 한다.
 */
export function AbsenceSummaryDialog({
  summary,
  open,
  onOpenChange,
  onAcknowledge,
}: {
  /** 아직 못 받았거나 부재가 아니면 그리지 않는다. */
  summary: Extract<Summary, { absent: true }> | null;
  open: boolean;
  /** 닫힘. 버튼과 Esc 가 같은 길이라 확인도 여기서 함께 일어난다. */
  onOpenChange: (open: boolean) => void;
  /** 확인. 모달이 보여준 틱을 그대로 넘긴다. */
  onAcknowledge: (ackedTickSeq: number) => void;
}) {
  if (!summary) {
    return null;
  }

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="data-[size=default]:sm:max-w-lg">
        <AlertDialogHeader>
          {/* 헤더 기본 간격(gap-1.5)보다 한 칸 더 띄운다 — 배지 줄은 제목의 곁말이라
              제목에 붙어 있으면 두 줄이 한 덩어리로 읽힌다. */}
          <div className="flex flex-wrap items-center gap-2 pb-1.5">
            <Badge variant="secondary">부재 요약</Badge>
            {/* 브리핑이 배지 옆에 시즌·일차를 두는 자리다. 부재 요약에서 같은 자리를 갖는
                것은 놓친 구간이다 — 아래 표의 숫자들이 "언제의 값인지" 를 말한다.
                계약은 거래일까지 주지만 화면에는 월까지만 낸다 (`format.ts`). */}
            <span className="text-xs text-muted-foreground tabular-nums">
              {gameMonth(summary.fromGameDate)} →{" "}
              {gameMonth(summary.toGameDate)}
            </span>
          </div>

          <AlertDialogTitle className="text-2xl">
            자리를 비운 사이에 일어난 일
          </AlertDialogTitle>
        </AlertDialogHeader>

        {/* 평가액 변동이 이 모달의 답이다 — "비운 사이 얼마가 되었나". 표의 한 줄로 두면
            `예약 체결`·`주요 이벤트` 와 같은 무게가 되어, 가장 궁금한 값을 세 줄 중에서
            찾아 읽어야 했다. 줄에서 꺼내 판 위에 크게 세운다.

            **복원할 수 없으면 응답에서 두 값이 함께 빠진다** (`acc.md`) — 그때는 판째로
            그리지 않는다. 0 이나 `—` 로 채우면 "변동이 없었다" 로 읽히고, 숫자로 만들어
            두면 `NaN원 — NaN%` 가 된다. */}
        {summary.valuationChangeKrw != null && summary.returnChange != null && (
          <ValuationChange
            amountKrw={summary.valuationChangeKrw}
            returnChange={summary.returnChange}
          />
        )}

        <dl className="flex flex-col text-sm">
          <DialogRow label="예약 체결">{summary.filledOrderCount}건</DialogRow>
        </dl>

        <AlertDialogFooter>
          {/* 브리핑과 같이 버튼 하나다. 닫는 모든 길이 확인이다 (위 주석). */}
          <AlertDialogAction
            className="w-full"
            onClick={() => onAcknowledge(summary.toTickSeq)}
          >
            확인
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * 부재 구간의 평가액 변동. **금액이 주인공이고 비율은 곁말이다** — 비율만 크게 두면
 * "몇 % 빠졌는데 그게 얼마인가" 를 계좌 화면에 가서 다시 봐야 한다.
 *
 * 색으로만 방향을 말하지 않는다 (DESIGN.md): `▲`·`+` 부호·낱말이 함께 선다. 낭독기에는
 * 기호 대신 말로 준다 — `▲` 는 읽히지 않는다.
 */
function ValuationChange({
  amountKrw,
  returnChange,
}: {
  amountKrw: string;
  returnChange: number;
}) {
  const amount = Number(amountKrw);
  const direction = amount > 0 ? "up" : amount < 0 ? "down" : "flat";
  const mark = { up: "▲", down: "▼", flat: "—" }[direction];
  const spoken = { up: "늘었습니다", down: "줄었습니다", flat: "그대로입니다" }[
    direction
  ];
  // 빼기 기호는 하이픈이 아니라 U+2212 다 (`format.ts` 의 `signed` 와 같은 이유) —
  // 하이픈은 폭이 좁아 tabular-nums 옆에서 자릿수가 어긋나 보인다.
  const sign = amount > 0 ? "+" : amount < 0 ? "−" : "";

  return (
    <div
      className={cn(
        "flex flex-col gap-1 rounded-lg px-4 py-4",
        direction === "up" && "bg-price-up-surface",
        direction === "down" && "bg-price-down-surface",
        direction === "flat" && "bg-muted"
      )}
    >
      {/* 라벨을 두지 않는다. 제목이 이미 "자리를 비운 사이에 일어난 일" 이고 그 바로
          아래의 등락 색 판이라, `자리를 비운 사이 평가액` 은 제목을 한 번 더 읽게 할
          뿐이었다. 낭독기에는 금액의 `aria-label` 이 무엇의 값인지 말한다. */}
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span
          className={cn(
            "text-3xl font-semibold whitespace-nowrap tabular-nums",
            direction === "up" && "text-price-up",
            direction === "down" && "text-price-down",
            direction === "flat" && "text-muted-foreground"
          )}
          aria-label={`평가액이 ${krw(Math.abs(amount))} ${spoken}`}
        >
          <span aria-hidden className="text-[0.6em] leading-none">
            {mark}
          </span>{" "}
          <span aria-hidden>
            {sign}
            {krw(Math.abs(amount))}
          </span>
        </span>
        <PriceChange value={returnChange} variant="badge" />
      </div>
    </div>
  );
}
