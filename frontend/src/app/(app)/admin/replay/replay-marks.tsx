"use client";

import { Fragment, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  MapPin,
  Rewind,
  RotateCcw,
} from "lucide-react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  DEFAULT_TICKS_BEFORE,
  gameTimeLabel,
  maxTicksBefore,
  type ReplayClock,
  type ReplayMark,
  type ReplayMarkKind,
  seekPreview,
  seekResetsSeason,
} from "@/lib/replay";

/**
 * 갈래별 표시 이름과 기본 노출 여부.
 *
 * **월 첫 Tick 은 기본으로 접는다.** 시즌당 80개라 목록의 3분의 2를 차지하는데, 그 자리에서
 * 확인할 것은 대부분 경계·공모다. 필요할 때 칩 하나로 켠다.
 */
const KINDS: { kind: ReplayMarkKind; label: string; on: boolean }[] = [
  { kind: "BOUNDARY", label: "일차 경계", on: true },
  { kind: "IPO", label: "공모", on: true },
  { kind: "LISTING", label: "상장", on: true },
  { kind: "DAY_LAST_TICK", label: "일차 마지막 틱", on: true },
  { kind: "MONTH", label: "월 첫 틱", on: false },
];

/** 시즌을 완전히 처음으로 되돌리는 지점. 1일차 브리핑에 그 자리로 선다. */
const SEASON_START_KEY = "day1.briefing";

/**
 * 이동 지점 표. **전 시즌을 한 표로** 그리고 타임라인 그래프를 만들지 않는다 — 필요한 것은
 * "지금 몇 번째 Tick 이고 다음에 무엇이 오는가"라는 정확한 값이다.
 *
 * 지점이 128개라 **갈래 필터와 일차 접기**로 줄여 본다. 기본은 현재 일차만 펼치고 월 첫 Tick
 * 은 접는다 — 기본 화면에서 한 화면에 들어오는 것이 목적이다.
 *
 * 뒤로 가는 이동에만 확인 대화상자를 둔다. 앞으로 가는 이동에는 확인 없이 처리량 미리보기를
 * 둔다 — 파괴적인 쪽에만 확인을 둬야 그 확인이 읽힌다.
 */
export function ReplayMarks({
  marks,
  clock,
  position,
  pending,
  onSeek,
}: {
  marks: ReplayMark[];
  clock: ReplayClock;
  /** 로컬에서 메운 현재 게임 위치. 표시선을 여기에 놓는다. */
  position: number;
  pending: boolean;
  onSeek: (mark: ReplayMark, ticksBefore: number) => void;
}) {
  /** 지점마다 따로 정한다 — 예약 체결은 한 칸 전이 좋고 청약은 여러 칸 전이 좋다. */
  const [ticksBefore, setTicksBefore] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState<ReplayMark | null>(null);
  const [shown, setShown] = useState<ReplayMarkKind[]>(
    KINDS.filter((entry) => entry.on).map((entry) => entry.kind)
  );
  const [hidePassed, setHidePassed] = useState(false);
  const [collapsed, setCollapsed] = useState<number[]>(() =>
    // 현재 일차만 펼친다. 지나온 일차는 대부분 다시 볼 일이 없다.
    [...new Set(marks.map((mark) => mark.seasonDay))].filter(
      (day) => day !== clock.seasonDay
    )
  );

  /** 계약 위반이 되지 않는 값으로만 보낸다. 서버가 400 으로 거절할 요청을 만들지 않는다. */
  const backOf = (mark: ReplayMark) => {
    const ceiling = maxTicksBefore(marks, mark, clock.firstTickSeq);
    const raw = ticksBefore[mark.key];
    const value =
      raw === undefined || raw.trim() === ""
        ? DEFAULT_TICKS_BEFORE
        : Number(raw);
    if (!Number.isInteger(value) || value < 0) {
      return Math.min(DEFAULT_TICKS_BEFORE, ceiling);
    }
    return Math.min(value, ceiling);
  };

  function go(mark: ReplayMark) {
    if (seekResetsSeason(marks, mark, backOf(mark), clock, position)) {
      setConfirming(mark);
      return;
    }
    onSeek(mark, backOf(mark));
  }

  const seasonStart = marks.find((mark) => mark.key === SEASON_START_KEY);
  const visible = marks.filter(
    (mark) => shown.includes(mark.kind) && !(hidePassed && mark.passed)
  );
  const days = [...new Set(visible.map((mark) => mark.seasonDay))].sort(
    (a, b) => a - b
  );

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <CardTitle className="flex items-center gap-1.5">
            <MapPin className="size-4 text-muted-foreground" aria-hidden />
            이동 지점
          </CardTitle>
          {seasonStart && (
            <Button
              variant="outline"
              size="sm"
              disabled={pending}
              onClick={() => setConfirming(seasonStart)}
            >
              <RotateCcw aria-hidden /> 시즌 처음으로
            </Button>
          )}
        </div>
        <CardDescription className="text-xs">
          <span className="block">
            앞으로 = 경유 구간의 Tick·시간 경계를 전부 처리하며 이동한다.
            체결·공모 추첨·상장·정산이 실제로 일어나고 계좌는 유지된다.
          </span>
          <span className="block">
            뒤로 = 되감기가 아니다. 시즌 진행 결과를 초기화하고 1일차 브리핑부터
            목표까지 다시 진행한다.
          </span>
        </CardDescription>
      </CardHeader>

      <CardContent className="flex flex-wrap items-center gap-1.5">
        {KINDS.map(({ kind, label }) => {
          const on = shown.includes(kind);
          const count = marks.filter((mark) => mark.kind === kind).length;
          return (
            <Button
              key={kind}
              size="xs"
              variant={on ? "default" : "outline"}
              aria-pressed={on}
              onClick={() =>
                setShown((current) =>
                  on
                    ? current.filter((entry) => entry !== kind)
                    : [...current, kind]
                )
              }
            >
              {label}
              <Badge variant="secondary" className="ml-1 tabular-nums">
                {count}
              </Badge>
            </Button>
          );
        })}
        <Button
          size="xs"
          variant={hidePassed ? "default" : "outline"}
          aria-pressed={hidePassed}
          onClick={() => setHidePassed((current) => !current)}
        >
          지나간 항목 숨기기
        </Button>
        <span className="ml-auto text-xs text-muted-foreground tabular-nums">
          {visible.length} / {marks.length}
        </span>
      </CardContent>

      <CardContent className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>지점</TableHead>
              <TableHead>게임 속 시각</TableHead>
              <TableHead className="text-right">Tick</TableHead>
              <TableHead className="text-right">처리량</TableHead>
              <TableHead className="text-right">Tick 전</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {days.map((day) => (
              <DayRows
                key={day}
                day={day}
                rows={visible.filter((mark) => mark.seasonDay === day)}
                marks={marks}
                clock={clock}
                position={position}
                pending={pending}
                open={!collapsed.includes(day)}
                onToggle={() =>
                  setCollapsed((current) =>
                    current.includes(day)
                      ? current.filter((entry) => entry !== day)
                      : [...current, day]
                  )
                }
                backOf={backOf}
                ticksBefore={ticksBefore}
                onTicksBefore={(key, value) =>
                  setTicksBefore((current) => ({ ...current, [key]: value }))
                }
                onGo={go}
              />
            ))}
            {visible.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="text-muted-foreground">
                  {marks.length === 0
                    ? "이동 지점이 없다. 시즌 편성이 아직 적재되지 않았다."
                    : "고른 갈래에 해당하는 지점이 없다."}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </CardContent>

      {/* 뒤로 가는 이동만 파괴적이다. 앞으로 가는 이동에는 확인을 두지 않는다 — 하루에
          수십 번 누르는 조작이기 때문이다. */}
      <AlertDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              이 지점으로 되돌리면 시즌이 초기화됩니다
            </AlertDialogTitle>
            <AlertDialogDescription className="space-y-2 text-sm">
              <span className="block">
                이동할 지점: <strong>{confirming?.label}</strong>. 뒤로 가는
                재생은 없습니다 — 시즌 진행 결과를 지우고 1일차 브리핑부터 이
                지점까지 다시 진행합니다.
              </span>
              <span className="block">
                <strong>지워지는 것</strong>: 주문·체결·보유 종목·환전·청약·일차
                정산·리포트·알림·채팅. 계좌의 현금과 확인한 Tick 은 시작 상태로
                돌아갑니다.
              </span>
              <span className="block">
                <strong>남는 것</strong>: 계좌 자체(재로그인이 필요하지
                않습니다) · 시즌 편성(종목·종가·뉴스·공모 일정) ·
                관심종목·문의·감사 로그.
              </span>
              <span className="block">
                접속 중인 다른 사람에게도 즉시 적용됩니다. 진행 구간에 따라 수
                초 ~ 수십 초가 걸립니다.
              </span>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>그대로 두기</AlertDialogCancel>
            <AlertDialogAction
              disabled={pending}
              onClick={(event) => {
                event.preventDefault();
                if (confirming) {
                  onSeek(confirming, backOf(confirming));
                  setConfirming(null);
                }
              }}
            >
              <Rewind aria-hidden /> 초기화하고 이동
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

function DayRows({
  day,
  rows,
  marks,
  clock,
  position,
  pending,
  open,
  onToggle,
  ticksBefore,
  backOf,
  onTicksBefore,
  onGo,
}: {
  day: number;
  /** 필터를 통과한 이 일차의 지점. */
  rows: ReplayMark[];
  /** 전체 목록. 처리량 미리보기와 초기화 판정이 다른 일차의 지점도 본다. */
  marks: ReplayMark[];
  clock: ReplayClock;
  position: number;
  pending: boolean;
  open: boolean;
  onToggle: () => void;
  ticksBefore: Record<string, string>;
  backOf: (mark: ReplayMark) => number;
  onTicksBefore: (key: string, value: string) => void;
  onGo: (mark: ReplayMark) => void;
}) {
  const here = day === clock.seasonDay;

  return (
    <>
      <TableRow className="bg-muted/40">
        <TableCell colSpan={6} className="p-0">
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={open}
            className="flex w-full items-center gap-1.5 px-2 py-2 text-left font-medium hover:bg-muted/60"
          >
            {open ? (
              <ChevronDown className="size-4" aria-hidden />
            ) : (
              <ChevronRight className="size-4" aria-hidden />
            )}
            {day}일차
            {here && (
              <Badge variant="secondary" className="ml-1">
                현재
              </Badge>
            )}
            <span className="ml-auto text-xs text-muted-foreground tabular-nums">
              {rows.length}개
            </span>
          </button>
        </TableCell>
      </TableRow>
      {open &&
        rows.map((mark, index) => {
          const back = backOf(mark);
          const preview = seekPreview(marks, mark, back, clock);
          const backward = seekResetsSeason(marks, mark, back, clock, position);
          // 표시선은 현재 위치가 이 지점과 앞 지점 사이일 때 이 행 위에 온다.
          const atHere =
            here &&
            position <= mark.offsetSec &&
            (index === 0 || position > (rows[index - 1]?.offsetSec ?? 0));

          return (
            <Fragment key={mark.key}>
              {atHere && (
                <TableRow>
                  <TableCell colSpan={6} className="py-0">
                    <div className="flex items-center gap-2 text-xs text-primary">
                      <span className="h-px flex-1 bg-primary" />
                      현재 위치 {gameTimeLabel(position)}
                      <span className="h-px flex-1 bg-primary" />
                    </div>
                  </TableCell>
                </TableRow>
              )}
              <MarkRow
                mark={mark}
                marks={marks}
                firstTickSeq={clock.firstTickSeq}
                back={back}
                backward={backward}
                preview={preview}
                pending={pending}
                value={ticksBefore[mark.key] ?? ""}
                onTicksBefore={onTicksBefore}
                onGo={onGo}
              />
            </Fragment>
          );
        })}
    </>
  );
}

function MarkRow({
  mark,
  marks,
  firstTickSeq,
  back,
  backward,
  preview,
  pending,
  value,
  onTicksBefore,
  onGo,
}: {
  mark: ReplayMark;
  marks: ReplayMark[];
  /** 시즌 첫 Tick 의 순번. 물러설 수 있는 최대 Tick 수를 이 값에서 센다. */
  firstTickSeq: number | null;
  back: number;
  backward: boolean;
  preview: { ticks: number | null; events: number; exact: boolean };
  pending: boolean;
  value: string;
  onTicksBefore: (key: string, value: string) => void;
  onGo: (mark: ReplayMark) => void;
}) {
  const ceiling = maxTicksBefore(marks, mark, firstTickSeq);

  return (
    <TableRow className={mark.passed ? "opacity-45" : undefined}>
      <TableCell>{mark.label}</TableCell>
      <TableCell className="tabular-nums">
        {gameTimeLabel(mark.offsetSec)}
      </TableCell>
      <TableCell className="text-right tabular-nums">
        {mark.tickSeq ?? "—"}
      </TableCell>
      <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
        {backward ? (
          "초기화 후 재진행"
        ) : (
          <>
            {preview.ticks === null
              ? "틱 —"
              : `틱 ${preview.exact ? "" : "약 "}${preview.ticks}건`}
            {" · "}
            이벤트 {preview.events}건
          </>
        )}
      </TableCell>
      <TableCell className="text-right">
        <input
          type="number"
          min={0}
          max={ceiling}
          disabled={ceiling === 0}
          aria-label={`${mark.label} 앞 Tick 수`}
          // 앞선 Tick 이 없는 지점(시즌 첫 Tick 이전)은 그 자리로만 갈 수 있다.
          title={
            ceiling === 0
              ? "이 지점 앞에는 Tick 이 없다. 지점 자체로만 이동한다."
              : `최대 ${ceiling}`
          }
          placeholder={String(Math.min(DEFAULT_TICKS_BEFORE, ceiling))}
          value={ceiling === 0 ? "" : value}
          onChange={(event) => onTicksBefore(mark.key, event.target.value)}
          className="h-7 w-16 rounded-md border border-input bg-muted px-2 text-right text-sm tabular-nums outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-40"
        />
      </TableCell>
      <TableCell className="text-right">
        <Button
          size="sm"
          variant={backward ? "outline" : "default"}
          disabled={pending}
          onClick={() => onGo(mark)}
        >
          여기로{back > 0 && ` (−${back})`}
        </Button>
      </TableCell>
    </TableRow>
  );
}
