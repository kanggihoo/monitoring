import { gameMonthsBetween, tradingDaysOf } from "./calendar.ts";
import { isoUtc } from "./time.ts";

/**
 * 게임 시계 — 월 그리드.
 *
 * **게임 1개월 = 실제 720초 고정이고 틱 간격은 고정하지 않는다.** 한 달의 거래일 수가
 * 20~23일로 다르므로 "1거래일 = 35초" 로 두면 22일인 달이 밀리고, 27개월을 돌리면 IPO
 * 편성·재생 종료·폐장이 전부 뒤틀린다 (`_common.md §7.1`).
 *
 * ```
 * tickAt(k) = 월 시작 + round(720초 × k ÷ N)   (k = 1..N)
 * N = 그 달의 거래일 수 = 한국·미국 거래일의 합집합
 * ```
 *
 * 간격을 반복 가산하지 않는다 — 같은 오차가 월 안에서 재발한다. `k = N` 이 정확히
 * `월 시작 + 720초` 가 되어 월 마지막 틱이 월 경계에 놓인다.
 *
 * **시계는 앵커와 배속 두 값에서 전부 파생된다.** `phase`·`tickSeq`·`gameDate`·
 * `nextTickAt`·`feeFree`·`leaderboardBlackout` 이 모두 그 둘의 함수이고, 재생 제어는
 * 그 둘을 바꾸는 것이다 (`adm.md`).
 */

/** 게임 1개월의 실제 재생 시간. 절대 불변 규칙 (`_common.md §7.1`). */
const SECONDS_PER_GAME_MONTH = 720;

/**
 * 1일차 재생 구간. 목은 1일차만 재생한다 — 휴장일 데이터가 전 구간을 이미 갖고 있으므로
 * 2·3일차 확장은 이 상수를 바꾸는 일이다 (기능명세서: 1일차 2020.01~2022.03).
 */
export const SEASON_DAY = {
  day: 1,
  fromGameMonth: "2020-01",
  toGameMonth: "2022-03",
};

/**
 * 하루 시간표를 11:00 기준 분으로 표현한다 (기능명세서 0장).
 *
 * **하루 타임라인 전체가 배속으로 균일 압축된다.** 틱만 압축하면 재생이 폐장 훨씬 전에
 * 끝나 카운트다운이 거짓말을 한다. 무료 타임·암전 구간도 같은 압축을 받는다.
 */
const DAY_MINUTES = {
  briefingStart: 0, // 11:00
  tradingStart: 5, // 11:05 — 재생 시작. IPO 편성 시각의 기준점이다
  feeFreeStart: 60, // 12:00
  feeFreeEnd: 70, // 12:10
  blackoutStart: 300, // 16:00
  marketClose: 340, // 16:40 — 폐장
  settlingEnd: 360, // 17:00 — 정산 발표
  nextOpen: 1440, // 익일 11:00
} as const;

/** 당일 청약 창구 (공시 ~ 청약 마감). 목의 임시 편성이다. */
const IPO_WINDOW_MINUTES: readonly [number, number][] = [
  [77, 89],
  [161, 173],
];

export type Phase = "BRIEFING" | "TRADING" | "SETTLING" | "REVIEW_ONLY";

export type ScheduledTick = {
  /** 시즌 누적 순번. 1일차가 1부터다 (`_common.md §7.3`). */
  tickSeq: number;
  gameDate: string;
  gameMonth: string;
  /** 재생 시작부터의 게임 페이스 초. 배속을 곱하기 전 값이다. */
  offsetSec: number;
};

/** 1일차 전체 틱 일정. 시드가 아니라 달력에서 파생되므로 기동 시 한 번 계산한다. */
export const SCHEDULE: readonly ScheduledTick[] = buildSchedule();

export const LAST_TICK_SEQ = SCHEDULE.length;

/** 재생이 끝나는 시점(재생 시작부터의 분). 1일차는 27개월 × 12분 = 324분이다. */
export const REPLAY_MINUTES =
  gameMonthsBetween(SEASON_DAY.fromGameMonth, SEASON_DAY.toGameMonth).length *
  (SECONDS_PER_GAME_MONTH / 60);

export type ClockState = {
  /** 0 이면 정지. 정지 중에는 시간이 흐르지 않는다. */
  speed: number;
  /** `anchorRealMs` 시점에 시계가 서 있는 틱. */
  anchorTickSeq: number;
  anchorRealMs: number;
};

export type ClockSnapshot = {
  phase: Phase;
  /**
   * **정지 중에는 `null`** — 시간이 흐르지 않으므로 다음 phase 전환의 절대 시각이
   * 존재하지 않는다. `nextTickAt` 과 같은 이유다.
   */
  phaseEndsAt: string | null;
  serverTime: string;
  gameDate: string;
  gameMonth: string;
  tickSeq: number;
  /** **정지 중에는 `null`** — 다음 틱이 오지 않으므로 절대 시각이 존재하지 않는다. */
  nextTickAt: string | null;
  feeFree: boolean;
  /**
   * 수수료 무료 종료 절대 시각. 무료 구간 밖이거나 정지 중이면 null.
   * 계약(`_common.md §7.3`)에 아직 없는 필드다 — 무료 종료 카운트다운을 절대 시각으로 그리려면
   * 필요해 목이 먼저 낸다. 백엔드 착수 시 계약에 올린다.
   */
  feeFreeEndsAt: string | null;
  leaderboardBlackout: boolean;
  seasonDay: number;
};

/** 재생 시작부터의 게임 페이스 초. 틱 0 은 재생 시작 그 자체다. */
export function offsetOf(tickSeq: number): number {
  if (tickSeq <= 0) {
    return 0;
  }

  const tick = SCHEDULE[Math.min(tickSeq, LAST_TICK_SEQ) - 1];
  return tick?.offsetSec ?? 0;
}

/** 그 게임 월의 첫 틱. 되감기가 게임 월 좌표를 틱으로 바꿀 때 쓴다. */
export function firstTickOfGameMonth(gameMonth: string): number | undefined {
  return SCHEDULE.find((tick) => tick.gameMonth === gameMonth)?.tickSeq;
}

/**
 * 지금 서 있는 틱. 정지 중이면 앵커 그대로다.
 *
 * 배속이 돌고 있으면 앵커에서 흐른 실제 시간을 게임 페이스 초로 환산해 그 지점까지
 * **발행됐어야 할 마지막 틱**을 찾는다. 절대 시각 기반이라 프로세스가 멈췄다 깨어나도
 * 따라잡고, 같은 질문을 반복해도 같은 답이 나온다.
 */
export function tickSeqAt(state: ClockState, nowMs: number): number {
  if (state.speed === 0) {
    return clampTick(state.anchorTickSeq);
  }

  const elapsedSec = ((nowMs - state.anchorRealMs) * state.speed) / 1000;
  const position = offsetOf(state.anchorTickSeq) + elapsedSec;

  // 일정이 오름차순이므로 뒤에서부터 첫 매치가 답이다.
  for (let index = SCHEDULE.length - 1; index >= 0; index--) {
    const tick = SCHEDULE[index];
    if (tick && tick.offsetSec <= position) {
      return tick.tickSeq;
    }
  }

  return 0;
}

/** 그 틱이 발행되는 실제 시각(ms). 정지 중에는 의미가 없다. */
export function realMsOfTick(state: ClockState, tickSeq: number): number {
  const deltaSec = offsetOf(tickSeq) - offsetOf(state.anchorTickSeq);
  return state.anchorRealMs + (deltaSec * 1000) / state.speed;
}

/**
 * 개장 브리핑 (`sea.md`). 하루 시간표의 절대 시각과 당일 청약 시각만 담는다.
 *
 * **재생 종료 지점·구간 내용·청약 종목명은 담지 않는다** — 기획서 3.5.2 의 명시 금지다.
 * 그 셋을 알면 그날의 시장 국면을 아는 것과 같다. `SEASON_DAY.toGameMonth` 가 이 파일에
 * 있지만 여기 넣지 않는 이유가 그것이다.
 */
export function briefing(state: ClockState) {
  const at = (dayMinute: number) => isoUtc(realMsOfDayMinute(state, dayMinute));

  return {
    seasonDay: SEASON_DAY.day,
    replayStartGameMonth: SEASON_DAY.fromGameMonth,
    schedule: {
      tradingStartsAt: at(DAY_MINUTES.tradingStart),
      feeFreeStartsAt: at(DAY_MINUTES.feeFreeStart),
      feeFreeEndsAt: at(DAY_MINUTES.feeFreeEnd),
      blackoutStartsAt: at(DAY_MINUTES.blackoutStart),
      closesAt: at(DAY_MINUTES.marketClose),
      settlementAt: at(DAY_MINUTES.settlingEnd),
    },
    // ponytail: 실제 편성은 IPO 슬라이스가 정한다. 지금은 시각만 있는 계약을 화면이
    // 그릴 수 있도록 두 건을 재생 시작 기준으로 놓는다 (`ipo.md` 가 붙으면 지운다).
    ipoWindows: IPO_WINDOW_MINUTES.map(([disclosure, closes]) => ({
      disclosureAt: at(disclosure),
      subscriptionClosesAt: at(closes),
    })),
  };
}

/** 11:00 기준 분을 실제 시각(ms)으로. 하루 전체가 같은 배속으로 압축된다. */
function realMsOfDayMinute(state: ClockState, dayMinute: number): number {
  const anchorDayMinute =
    DAY_MINUTES.tradingStart + offsetOf(state.anchorTickSeq) / 60;
  const deltaMinutes = dayMinute - anchorDayMinute;

  return state.anchorRealMs + (deltaMinutes * 60_000) / state.speed;
}

/** 지금의 11:00 기준 분. 정지 중이면 앵커 틱이 놓인 지점에서 멈춰 있다. */
function dayMinuteAt(state: ClockState, nowMs: number): number {
  const anchorDayMinute =
    DAY_MINUTES.tradingStart + offsetOf(state.anchorTickSeq) / 60;

  if (state.speed === 0) {
    return anchorDayMinute;
  }

  const elapsedMinutes = ((nowMs - state.anchorRealMs) * state.speed) / 60_000;

  return anchorDayMinute + elapsedMinutes;
}

/**
 * 화면과 계약이 보는 시계 전체. 앵커와 배속에서 전부 파생된다.
 *
 * **남은 초가 아니라 절대 시각을 보낸다.** 탭이 백그라운드로 가면 타이머가 스로틀되므로,
 * `serverTime` 을 함께 보내 클라이언트가 오프셋을 한 번 구해 두고 매 렌더링마다 다시
 * 계산하게 한다 (`_common.md §7.3`).
 */
export function snapshot(state: ClockState, nowMs: number): ClockSnapshot {
  const tickSeq = tickSeqAt(state, nowMs);
  const dayMinute = dayMinuteAt(state, nowMs);
  const tick = SCHEDULE[tickSeq - 1];
  const nextTick = SCHEDULE[tickSeq];

  return {
    phase: phaseOf(dayMinute),
    // 정지 중에는 배속으로 나눌 수 없다 — 다음 전환이 오지 않으므로 절대 시각이 없다.
    phaseEndsAt:
      state.speed === 0
        ? null
        : isoUtc(realMsOfDayMinute(state, phaseEndMinute(dayMinute))),
    serverTime: isoUtc(nowMs),
    // 첫 틱 전에는 확정된 종가가 없다. 재생 구간의 첫 거래일을 그대로 가리킨다.
    gameDate: gameDateOf(tickSeq),
    gameMonth: tick?.gameMonth ?? SEASON_DAY.fromGameMonth,
    tickSeq,
    nextTickAt:
      state.speed === 0 || !nextTick
        ? null
        : isoUtc(realMsOfTick(state, nextTick.tickSeq)),
    feeFree:
      dayMinute >= DAY_MINUTES.feeFreeStart &&
      dayMinute < DAY_MINUTES.feeFreeEnd,
    feeFreeEndsAt:
      state.speed === 0 ||
      dayMinute < DAY_MINUTES.feeFreeStart ||
      dayMinute >= DAY_MINUTES.feeFreeEnd
        ? null
        : isoUtc(realMsOfDayMinute(state, DAY_MINUTES.feeFreeEnd)),
    leaderboardBlackout:
      dayMinute >= DAY_MINUTES.blackoutStart &&
      dayMinute < DAY_MINUTES.marketClose,
    seasonDay: SEASON_DAY.day,
  };
}

function phaseOf(dayMinute: number): Phase {
  if (dayMinute < DAY_MINUTES.tradingStart) return "BRIEFING";
  if (dayMinute < DAY_MINUTES.marketClose) return "TRADING";
  if (dayMinute < DAY_MINUTES.settlingEnd) return "SETTLING";
  return "REVIEW_ONLY";
}

function phaseEndMinute(dayMinute: number): number {
  if (dayMinute < DAY_MINUTES.tradingStart) return DAY_MINUTES.tradingStart;
  if (dayMinute < DAY_MINUTES.marketClose) return DAY_MINUTES.marketClose;
  if (dayMinute < DAY_MINUTES.settlingEnd) return DAY_MINUTES.settlingEnd;
  return DAY_MINUTES.nextOpen;
}

export function clampTick(tickSeq: number): number {
  return Math.max(0, Math.min(tickSeq, LAST_TICK_SEQ));
}

/** 그 틱이 확정한 종가의 게임 거래일. 첫 틱 전에는 재생 구간의 첫 거래일을 가리킨다. */
export function gameDateOf(tickSeq: number): string {
  return (
    SCHEDULE[Math.max(1, tickSeq) - 1]?.gameDate ?? SCHEDULE[0]?.gameDate ?? ""
  );
}

function buildSchedule(): ScheduledTick[] {
  const months = gameMonthsBetween(
    SEASON_DAY.fromGameMonth,
    SEASON_DAY.toGameMonth
  );
  const ticks: ScheduledTick[] = [];
  let tickSeq = 0;

  months.forEach((gameMonth, monthIndex) => {
    const days = tradingDaysOf(gameMonth);
    const monthStartSec = monthIndex * SECONDS_PER_GAME_MONTH;

    days.forEach((gameDate, dayIndex) => {
      tickSeq += 1;
      ticks.push({
        tickSeq,
        gameDate,
        gameMonth,
        // 절대값으로 계산한다. 간격을 반복 가산하면 오차가 월 안에서 재발한다.
        offsetSec:
          monthStartSec +
          Math.round((SECONDS_PER_GAME_MONTH * (dayIndex + 1)) / days.length),
      });
    });
  });

  return ticks;
}
