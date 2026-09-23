import { gameMonthsBetween } from "./calendar.ts";
import {
  LAST_TICK_SEQ,
  REPLAY_MINUTES,
  SCHEDULE,
  SEASON_DAY,
} from "./clock.ts";

/**
 * 기동 시 자기 검산. **어긋나면 뜨지 않는다.**
 *
 * 목 서버 자체에는 테스트를 두지 않는다 — 개발 장치이지 제품이 아니다. 대신 이 검산을
 * 둔다. 테스트 스위트보다 이쪽이 맞는 이유는, 목이 틀리면 **목을 쓰는 모든 테스트가
 * 조용히 틀리기** 때문이다.
 */

/** 게임 1개월의 실제 재생 시간(분). */
const MINUTES_PER_GAME_MONTH = 12;

/** 재생 시작 시각(11:05)을 자정 기준 분으로. 편성표의 벽시계 값과 맞춰 보려고 쓴다. */
const TRADING_START_MINUTE = 11 * 60 + 5;

/**
 * IPO 편성표 — **입력이 아니라 검산용 상수표다** (기능명세서 IPO 편성표).
 *
 * 편성 시각을 하드코딩하면 배속에서 전부 깨진다. 구현은 월 그리드에서 파생하고
 * (상장 = 상장 게임월이 시작하는 Tick, 공시 = 24분 전, 마감 = 12분 전) 이 표와 맞는지
 * 확인한다. 8건 전부가 재생 시작(11:05)으로부터 12분의 배수인 것이 그 파생의 증거다.
 */
const IPO_SCHEDULE = [
  {
    day: 1,
    name: "카카오게임즈",
    listingGameMonth: "2020-09",
    disclosedAt: "12:17",
    closesAt: "12:29",
    listedAt: "12:41",
  },
  {
    day: 1,
    name: "코인베이스",
    listingGameMonth: "2021-04",
    disclosedAt: "13:41",
    closesAt: "13:53",
    listedAt: "14:05",
  },
  {
    day: 1,
    name: "크래프톤",
    listingGameMonth: "2021-08",
    disclosedAt: "14:29",
    closesAt: "14:41",
    listedAt: "14:53",
  },
  {
    day: 1,
    name: "리비안",
    listingGameMonth: "2021-11",
    disclosedAt: "15:05",
    closesAt: "15:17",
    listedAt: "15:29",
  },
  {
    day: 2,
    name: "ARM",
    listingGameMonth: "2023-09",
    disclosedAt: "14:05",
    closesAt: "14:17",
    listedAt: "14:29",
  },
  {
    day: 2,
    name: "두산로보틱스",
    listingGameMonth: "2023-10",
    disclosedAt: "14:17",
    closesAt: "14:29",
    listedAt: "14:41",
  },
  {
    day: 3,
    name: "LG CNS",
    listingGameMonth: "2025-02",
    disclosedAt: "12:05",
    closesAt: "12:17",
    listedAt: "12:29",
  },
  {
    day: 3,
    name: "코어위브",
    listingGameMonth: "2025-03",
    disclosedAt: "12:17",
    closesAt: "12:29",
    listedAt: "12:41",
  },
] as const;

/** 일자별 재생 구간. 2·3일차는 재생하지 않지만 IPO 검산이 그 시작 월을 요구한다. */
const DAY_RANGES: Record<number, string> = {
  1: "2020-01",
  2: "2022-04",
  3: "2024-07",
};

/** 1일차 계약값 (스펙 Further Notes 의 검산 결과). */
const EXPECTED = {
  tickCount: 584,
  monthCount: 27,
  replayMinutes: 324,
  minIntervalSec: 31,
  maxIntervalSec: 36,
} as const;

/** @throws 검산이 하나라도 어긋나면 Error. 호출부는 이것을 잡지 않는다 */
export function selfCheck(): string[] {
  const months = gameMonthsBetween(
    SEASON_DAY.fromGameMonth,
    SEASON_DAY.toGameMonth
  );
  const failures: string[] = [];
  const notes: string[] = [];

  if (months.length !== EXPECTED.monthCount) {
    failures.push(
      `1일차 월 수가 ${months.length} 다 (기대 ${EXPECTED.monthCount})`
    );
  }
  if (LAST_TICK_SEQ !== EXPECTED.tickCount) {
    failures.push(
      `1일차 Tick 수가 ${LAST_TICK_SEQ} 다 (기대 ${EXPECTED.tickCount})`
    );
  }
  if (REPLAY_MINUTES !== EXPECTED.replayMinutes) {
    failures.push(
      `재생 시간이 ${REPLAY_MINUTES}분이다 (기대 ${EXPECTED.replayMinutes}분)`
    );
  }
  notes.push(
    `1일차 ${months.length}개월 · ${LAST_TICK_SEQ} Tick · 재생 ${REPLAY_MINUTES}분`
  );

  // tickSeq 는 1씩 증가하는 시즌 누적이어야 한다. 구멍 감지가 이 성질에 걸려 있다.
  const broken = SCHEDULE.findIndex(
    (tick, index) => tick.tickSeq !== index + 1
  );
  if (broken >= 0) {
    failures.push(`tickSeq 가 1씩 증가하지 않는다 (index ${broken})`);
  }

  // 틱 간격이 계약 범위 안인지. 고정 주기 스케줄러가 불가능하다는 증거이기도 하다.
  const intervals = SCHEDULE.map(
    (tick, index) => tick.offsetSec - (SCHEDULE[index - 1]?.offsetSec ?? 0)
  );
  const minInterval = Math.min(...intervals);
  const maxInterval = Math.max(...intervals);
  if (
    minInterval < EXPECTED.minIntervalSec ||
    maxInterval > EXPECTED.maxIntervalSec
  ) {
    failures.push(
      `틱 간격 ${minInterval}~${maxInterval}초가 계약 범위(${EXPECTED.minIntervalSec}~${EXPECTED.maxIntervalSec}초)를 벗어난다`
    );
  }
  notes.push(`틱 간격 ${minInterval}~${maxInterval}초`);

  // 각 월의 마지막 Tick 이 정확히 월 경계에 놓이는지. 이것이 어긋나면 27개월을 돌렸을 때
  // IPO 편성·재생 종료·폐장이 전부 뒤틀린다.
  months.forEach((gameMonth, monthIndex) => {
    const monthTicks = SCHEDULE.filter((tick) => tick.gameMonth === gameMonth);
    const lastTick = monthTicks.at(-1);
    const boundarySec = (monthIndex + 1) * MINUTES_PER_GAME_MONTH * 60;

    if (!lastTick) {
      failures.push(`${gameMonth} 에 Tick 이 없다`);
      return;
    }
    if (lastTick.offsetSec !== boundarySec) {
      failures.push(
        `${gameMonth} 마지막 Tick 이 ${lastTick.offsetSec}초에 있다 (월 경계 ${boundarySec}초)`
      );
    }
  });

  // IPO 8건이 편성표와 일치하는지. 표를 입력으로 쓰지 않으므로 이 검산이 유일한 방어선이다.
  for (const ipo of IPO_SCHEDULE) {
    const dayFrom = DAY_RANGES[ipo.day];
    if (!dayFrom) {
      failures.push(`${ipo.day}일차 재생 구간 시작 월을 모른다`);
      continue;
    }

    const monthIndex =
      gameMonthsBetween(dayFrom, ipo.listingGameMonth).length - 1;
    const listedMinute =
      TRADING_START_MINUTE + monthIndex * MINUTES_PER_GAME_MONTH;
    const derived = {
      disclosedAt: hhmm(listedMinute - 2 * MINUTES_PER_GAME_MONTH),
      closesAt: hhmm(listedMinute - MINUTES_PER_GAME_MONTH),
      listedAt: hhmm(listedMinute),
    };

    for (const key of ["disclosedAt", "closesAt", "listedAt"] as const) {
      if (derived[key] !== ipo[key]) {
        failures.push(
          `${ipo.name} ${key} 파생값 ${derived[key]} 이 편성표 ${ipo[key]} 와 다르다`
        );
      }
    }
  }
  notes.push(`IPO ${IPO_SCHEDULE.length}건 편성표 일치`);

  if (failures.length > 0) {
    throw new Error(
      `목 서버 기동 검산 실패 — 목이 틀리면 목을 쓰는 모든 테스트가 조용히 틀린다:\n` +
        failures.map((failure) => `  · ${failure}`).join("\n")
    );
  }

  return notes;
}

function hhmm(totalMinutes: number): string {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}
