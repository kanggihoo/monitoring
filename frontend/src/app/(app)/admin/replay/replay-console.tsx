"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Gauge, Pause, Play, Radio } from "lucide-react";
import { toast } from "sonner";

import { PhaseBadge } from "@/components/market/phase-badge";
import { Stat } from "@/components/market/stat";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useNow } from "@/hooks/use-now";
import { apiErrorMessage, apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";
import { gameDay, gameMonth } from "@/lib/format";
import {
  clockOffsetMs,
  formatCountdown,
  remainingSeconds,
} from "@/lib/game-clock";
import { ReadFailedError, readJson } from "@/lib/market-api";
import {
  gameTimeLabel,
  livePosition,
  MARKS_PATH,
  parseSpeed,
  REPLAY_PATH,
  type ReplayClock,
  type ReplayMark,
  type ReplaySeekResponse,
  SEEK_PATH,
} from "@/lib/replay";

import { ReplayMarks } from "./replay-marks";
import { useReplaySocket } from "./use-replay-socket";

/** 미리 담아 둔 배속. 직접 입력은 0~100, 소수 둘째 자리까지다. */
const SPEEDS = [1, 10, 60] as const;

/** 상태를 다시 읽는 주기. 다른 탭·명령줄이 바꾼 것도 따라온다. */
const POLL_MS = 2000;

export function ReplayConsole({ initial }: { initial: ReplayClock }) {
  const [state, setState] = useState(initial);
  const [receivedAt, setReceivedAt] = useState(() => Date.now());
  const [pending, setPending] = useState(false);
  /** `null` 이면 이 환경에 지점 이동이 없다 — 고장이 아니라 미제공이다. */
  const [marks, setMarks] = useState<ReplayMark[] | null>([]);
  const [speedInput, setSpeedInput] = useState("");
  const now = useNow();

  /**
   * 2초 폴링·조작 응답·WS 알림 재조회가 겹친다. 가장 마지막에 **시작한** 요청의 응답만
   * 반영해 오래된 조회가 최신 조작 결과를 덮어쓰지 않게 한다.
   */
  const generation = useRef(0);

  const apply = useCallback((fresh: ReplayClock, forGeneration: number) => {
    if (forGeneration !== generation.current) {
      return;
    }
    setState(fresh);
    setReceivedAt(Date.now());
  }, []);

  const refresh = useCallback(() => {
    const mine = ++generation.current;
    readJson<ReplayClock>(REPLAY_PATH)
      .then((fresh) => apply(fresh, mine))
      .catch((error: unknown) =>
        console.warn("재생 상태를 갱신하지 못했습니다", error)
      );
    // 지나간 항목의 구분이 진행을 따라 바뀌므로 시계와 같은 주기로 다시 읽는다.
    readJson<ReplayMark[]>(MARKS_PATH)
      .then((fresh) => {
        if (mine === generation.current) setMarks(fresh);
      })
      .catch((error: unknown) => {
        // **404 만 미제공이다.** 네트워크 실패나 500 을 "기능이 없다"로 바꾸면 고장을
        // 정상으로 안내하게 된다 — 그 경우는 마지막 목록을 그대로 두고 다음 주기를 기다린다.
        if (
          mine === generation.current &&
          error instanceof ReadFailedError &&
          error.status === 404
        ) {
          setMarks(null);
        }
        console.warn("이동 지점 목록을 갱신하지 못했습니다", error);
      });
  }, [apply]);

  const socket = useReplaySocket(refresh);

  useEffect(() => {
    const timer = setInterval(refresh, POLL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  const offset = clockOffsetMs(state.serverTime, receivedAt);
  const transition = remainingSeconds(state.phaseEndsAt, offset, now);
  const nextTick = remainingSeconds(state.nextTickAt, offset, now);
  // 재조회 주기(2초)만큼 위치가 뭉텅이로 뛰지 않게 그 사이를 메운다. 다음 응답이 보정한다.
  const position = livePosition(state, (now - receivedAt) / 1000);
  const paused = state.speed === 0;
  const waiting = state.scheduledStartAt !== null;

  /** 상태 변경은 전부 `apiFetch` 를 거친다 — 위조 방지 토큰이 한 곳에서만 붙는다. */
  async function send(body: Record<string, unknown>) {
    setPending(true);
    const mine = ++generation.current;
    try {
      const response = await apiFetch(REPLAY_PATH, {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        toast.error(
          await apiErrorMessage(
            response,
            `요청이 거부되었습니다 (${response.status})`
          )
        );
        // 거절된 뒤의 진짜 상태를 다시 읽는다.
        refresh();
        return;
      }
      const fresh = ((await response.json()) as { data: ReplayClock }).data;
      apply(fresh, mine);
      // 응답은 커밋 시점의 상태다. 그 사이 폴러가 틱이나 경계를 진행했을 수 있으므로
      // 조작 직후 최신 상태를 한 번 더 읽는다.
      refresh();
      return fresh;
    } catch (error) {
      console.error("재생 제어 요청에 실패했습니다", error);
      toast.error("재생 제어 요청에 실패했습니다. 네트워크 연결을 확인하세요.");
    } finally {
      setPending(false);
    }
  }

  const setSpeed = (speed: number) =>
    send({ speed }).then(
      (fresh) =>
        fresh && toast.success(speed === 0 ? "정지했습니다" : `배속 ${speed}배`)
    );

  function applyCustomSpeed() {
    const speed = parseSpeed(speedInput);
    if (speed === null) {
      toast.error("배속은 0~100, 소수 둘째 자리까지입니다");
      return;
    }
    void setSpeed(speed);
  }

  /** 이동은 도착을 기다리지 않는다. 도착은 위의 2초 재조회가 관찰한다. */
  async function seek(mark: ReplayMark, ticksBefore: number) {
    setPending(true);
    const mine = ++generation.current;
    try {
      const response = await apiFetch(SEEK_PATH, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ markKey: mark.key, ticksBefore }),
      });
      if (!response.ok) {
        toast.error(
          response.status === 404
            ? "이 환경에는 지점 이동이 없습니다 (개발·테스트 전용)"
            : await apiErrorMessage(
                response,
                `이동이 거부되었습니다 (${response.status})`
              )
        );
        refresh();
        return;
      }
      const result = ((await response.json()) as { data: ReplaySeekResponse })
        .data;
      apply(result.clock, mine);
      refresh();
      toast.success(
        `이동 시작: ${mark.label}${ticksBefore > 0 ? ` ${ticksBefore} Tick 전` : ""}` +
          (result.reseeded ? " · 시즌을 초기화했습니다" : "")
      );
    } catch (error) {
      console.error("지점 이동 요청에 실패했습니다", error);
      toast.error("지점 이동 요청에 실패했습니다. 네트워크 연결을 확인하세요.");
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>재생 제어</CardTitle>
          <CardDescription>
            개발·테스트 환경 전용이다. 프로덕션에는 이 경로가 존재하지 않는다.
            실제 Spring 재생 상태를 읽고 조작한다.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6">
          <Stat
            label="phase"
            value={<PhaseBadge phase={state.phase} feeFree={state.feeFree} />}
            hint={state.leaderboardBlackout ? "랭킹 암전" : undefined}
          />
          <Stat
            label="일차 · 게임 속 시각"
            value={`${state.seasonDay}일차 ${gameTimeLabel(position)}`}
            hint={`오프셋 ${Math.round(position)}초`}
          />
          {/* 게임 시계의 현재 날짜가 아니라 마지막으로 공개된 틱의 거래일이다.
              당일 마지막 틱 이후에는 폐장·발표를 지나도 이 값이 그대로 멈춘다. */}
          <Stat
            label="공개 기준 거래일"
            value={state.gameDate ? gameDay(state.gameDate) : "미공개"}
            hint={
              state.gameMonth ? gameMonth(state.gameMonth) : "첫 틱 공개 전"
            }
          />
          <Stat
            label="공개 틱 (DB)"
            value={state.tickSeq === null ? "미공개" : String(state.tickSeq)}
            hint={`epoch ${state.replayEpoch}`}
          />
          <Stat
            label="배속"
            value={
              state.warping ? "이동 중" : paused ? "정지" : `${state.speed}배`
            }
            hint={
              state.warping
                ? "목표까지 진행 중"
                : waiting
                  ? `시작 전 대기 · ${formatDateTime(state.scheduledStartAt ?? "")}`
                  : undefined
            }
          />
          <Stat
            label="다음 Phase 전환"
            value={transition === null ? "—" : formatCountdown(transition)}
            hint={
              nextTick === null
                ? paused
                  ? "정지 중"
                  : "당일 가격 종료"
                : `다음 종가 ${formatCountdown(nextTick)}`
            }
          />
        </CardContent>
        <CardContent className="text-xs text-muted-foreground">
          실제 서버 시각 {formatDateTime(state.serverTime)} · 앵커{" "}
          {state.seasonDay}일차 {Math.round(state.anchorOffsetSec)}초 @{" "}
          {formatDateTime(state.anchorAt)}
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        <Card size="sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-1.5">
              <Gauge className="size-4 text-muted-foreground" aria-hidden />
              배속
            </CardTitle>
            <CardDescription className="text-xs">
              정지는 배속 0 이다. 0~100, 소수 둘째 자리까지 받는다.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2">
            <Button
              variant={paused ? "default" : "outline"}
              disabled={pending}
              onClick={() => setSpeed(0)}
            >
              <Pause aria-hidden /> 정지
            </Button>
            {SPEEDS.map((speed) => (
              <Button
                key={speed}
                variant={state.speed === speed ? "default" : "outline"}
                disabled={pending}
                onClick={() => setSpeed(speed)}
              >
                <Play aria-hidden /> {speed}배
              </Button>
            ))}
          </CardContent>
          <CardContent className="flex gap-2">
            <input
              type="text"
              inputMode="decimal"
              aria-label="직접 입력 배속"
              placeholder="예: 0.25"
              value={speedInput}
              onChange={(event) => setSpeedInput(event.target.value)}
              className="h-8 w-full rounded-md border border-input bg-muted px-2.5 text-sm tabular-nums outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            />
            <Button
              variant="outline"
              disabled={pending || speedInput.trim() === ""}
              onClick={applyCustomSpeed}
            >
              적용
            </Button>
          </CardContent>
        </Card>

        <Card size="sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-1.5">
              <Radio className="size-4 text-muted-foreground" aria-hidden />
              Spring WS
            </CardTitle>
            <CardDescription className="text-xs">
              DB 공개 틱과 수신 틱을 따로 본다. 어긋나면 WS 지연이고, DB 가 멈춘
              것과 다르다.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            <div className="flex gap-4">
              <Stat label="연결" value={STATUS_LABEL[socket.status]} />
              <Stat
                label="마지막 수신 틱"
                value={
                  socket.lastTickSeq === null
                    ? "없음"
                    : String(socket.lastTickSeq)
                }
              />
            </div>
            <ol className="max-h-48 overflow-y-auto text-xs tabular-nums">
              {socket.messages.length === 0 && (
                <li className="text-muted-foreground">
                  아직 받은 메시지가 없다.
                </li>
              )}
              {socket.messages.map((message) => (
                <li
                  key={message.id}
                  className="border-b border-border/50 py-1 last:border-0"
                >
                  <span className="text-muted-foreground">
                    {new Date(message.receivedAt).toLocaleTimeString("ko-KR")}
                  </span>{" "}
                  <span className="font-medium">{message.type}</span>
                  {message.tickSeq !== null && ` · tick ${message.tickSeq}`}
                  {message.detail && (
                    <span className="text-muted-foreground">
                      {" "}
                      · {message.detail}
                    </span>
                  )}
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>
      </div>
      {marks === null ? (
        <Card size="sm">
          <CardContent className="text-sm text-muted-foreground">
            이 환경에는 지점 이동이 없다. 고장이 아니라 개발·테스트 전용 기능이
            꺼져 있다는 뜻이다.
          </CardContent>
        </Card>
      ) : (
        <ReplayMarks
          marks={marks}
          clock={state}
          position={position}
          pending={pending}
          onSeek={(mark, ticksBefore) => void seek(mark, ticksBefore)}
        />
      )}
    </>
  );
}

const STATUS_LABEL = {
  connecting: "연결 중",
  connected: "연결됨",
  disconnected: "끊김",
} as const;
