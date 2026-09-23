"use client";

import { useEffect, useState } from "react";

/**
 * 1초마다, 그리고 탭이 다시 보이는 순간 다시 그리게 하는 로컬 시각(ms).
 *
 * 값을 세어 내려가지 않는다 — 카운트다운은 이 값과 게임 시계 오프셋에서 매번 다시 계산한다.
 * 백그라운드에서 타이머가 스로틀돼도 `visibilitychange` 가 돌아온 순간 한 번 갱신하므로
 * 즉시 정확하다.
 *
 * **타이머는 훅마다가 아니라 모듈에 하나다.** 컴포넌트마다 자기 시점에 `setInterval` 을
 * 걸면 초 경계를 넘는 순간이 서로 달라, 같은 절대 시각에서 계산한 남은 초가 헤더와 카드에서
 * 1 초씩 어긋나 보였다 (`remainingSeconds` 가 올림이라 위상차가 그대로 드러난다). 같은
 * tick 을 나눠 쓰면 어긋날 자리가 없다.
 */

const INTERVAL_MS = 1000;

/** 구독 중인 훅들. 비면 타이머를 접는다 — 화면에 시계가 하나도 없으면 돌 이유가 없다. */
const listeners = new Set<(now: number) => void>();
let now = Date.now();
let timer: ReturnType<typeof setInterval> | null = null;

/** 시각을 다시 잡고 **구독자 전부에게 같은 값**을 준다. */
function tick() {
  now = Date.now();
  for (const listener of listeners) {
    listener(now);
  }
}

function start() {
  timer = setInterval(tick, INTERVAL_MS);
  document.addEventListener("visibilitychange", tick);
  window.addEventListener("focus", tick);
}

function stop() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  document.removeEventListener("visibilitychange", tick);
  window.removeEventListener("focus", tick);
}

/** @returns 공용 시계가 마지막으로 잡은 로컬 시각(ms) */
export function useNow(): number {
  const [value, setValue] = useState(now);

  useEffect(() => {
    listeners.add(setValue);
    if (listeners.size === 1) {
      start();
    }
    // **여기서 한 번 돌린다.** 구독자가 없는 동안 타이머가 멎어 있었으면 들고 있던 `now` 가
    // 그만큼 낡았고, 첫 구독자만 낡은 값으로 한 박자를 보낸다.
    tick();
    return () => {
      listeners.delete(setValue);
      if (listeners.size === 0) {
        stop();
      }
    };
  }, []);

  return value;
}
