"use client";

import { useStore } from "zustand";

import { remainingSeconds } from "@/lib/game-clock";
import type { Phase } from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";

import { useNow } from "./use-now";

/** 다음 상태 전환의 이름. 수수료 무료 구간에 들어가면 그 종료가 먼저 온다. */
export const TRANSITION_LABEL: Record<Phase, string> = {
  BRIEFING: "개장까지",
  TRADING: "폐장까지",
  SETTLING: "정산 발표까지",
  REVIEW_ONLY: "다음 개장까지",
};

export type GameClock = {
  phase: Phase | null;
  gameMonth: string | null;
  gameDate: string | null;
  seasonDay: number | null;
  feeFree: boolean;
  leaderboardBlackout: boolean;
  /** 다음 상태 전환까지. 정지 중이면 seconds 가 null. 무료 타임에도 phase 기준이다. */
  transition: { label: string; seconds: number | null };
  /** 수수료 무료 종료까지 남은 초. 무료 타임이 아니거나 정지 중이면 null. */
  feeFreeSeconds: number | null;
  /** 다음 종가까지 — "이 가격으로 살 수 있는 남은 시간". 정지 중이면 null. */
  nextCloseSeconds: number | null;
  connected: boolean;
};

/**
 * 게임 시계. 세션 스토어의 절대 시각과 오프셋 위의 얇은 껍데기다 — 계산은 `lib/game-clock` 의
 * 순수 함수가 하고, 이 훅은 1초마다 다시 부르는 일만 한다.
 */
export function useGameClock(): GameClock {
  const session = useStore(sessionStore);
  const now = useNow();
  const offset = session.offsetMs ?? 0;

  // **전환 시계는 언제나 phase 기준이다.** 무료 타임은 제 배너가 따로 세므로, 이 값까지
  // 무료 종료로 바뀌면 그 10분 동안 폐장까지 남은 시간을 볼 곳이 없어진다.
  const transition = {
    label: session.phase ? TRANSITION_LABEL[session.phase] : "다음 전환까지",
    seconds: remainingSeconds(session.phaseEndsAt, offset, now),
  };

  return {
    phase: session.phase,
    gameMonth: session.gameMonth,
    gameDate: session.gameDate,
    seasonDay: session.seasonDay,
    feeFree: session.feeFree,
    leaderboardBlackout: session.leaderboardBlackout,
    transition,
    feeFreeSeconds:
      session.feeFree && session.feeFreeEndsAt
        ? remainingSeconds(session.feeFreeEndsAt, offset, now)
        : null,
    nextCloseSeconds:
      session.phase === "TRADING"
        ? remainingSeconds(session.nextTickAt, offset, now)
        : null,
    connected: session.connected,
  };
}
