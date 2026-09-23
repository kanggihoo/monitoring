/**
 * 관리자 재생 시계 계약 (`GET`/`PATCH /api/v1/admin/replay`, Spring).
 *
 * **목 마켓의 `ClockSnapshot` 과 별개 타입이다.** 둘은 서로 다른 게임 시계이고 `tickSeq` 도
 * 다르다 — 한 타입으로 합치면 관리자 화면의 Spring 상태가 목 마켓 계약을 오염시킨다
 * (`market-replay-clock-scheduler.md` "기존 관리자 화면의 부분 이관").
 */

export type ReplayPhase = "BRIEFING" | "TRADING" | "SETTLING" | "REVIEW_ONLY";

export type ReplayClock = {
  seasonId: number;
  /** 진행 중인 일차. */
  seasonDay: number;
  /** 그 일차 11:05 로부터의 가상 초. 브리핑은 -300 이다. */
  offsetSec: number;
  anchorOffsetSec: number;
  /** 앵커 게임 위치에 대응하는 실제 시각. 시작 예약 중에는 미래 시각이다. */
  anchorAt: string;
  speed: number;
  phase: ReplayPhase;
  /** 실제 서버 시각. 게임 속 시각과 구별해 표시한다. */
  serverTime: string;
  /** 최초 공개 전에는 null. 준비 상태를 가짜 틱으로 표시하지 않는다. */
  tickSeq: number | null;
  /**
   * 이 시즌 첫 Tick 의 순번. **1 이라고 가정하지 않는다.**
   *
   * `tickSeq` 는 시즌별 순번이 아니라 전역 식별자라, 시즌마다 겹치지 않는 번호 구간을 쓴다
   * (시즌 2 의 첫 Tick 은 1000001 이다). 그래서 "이 지점보다 앞선 Tick 이 몇 개인가" 를 순번
   * 에서 바로 뺄 수 없고, 이 값을 기준으로 세야 한다. 틱이 적재되지 않았으면 null 이다.
   */
  firstTickSeq: number | null;
  gameDate: string | null;
  gameMonth: string | null;
  /** 정지 중이거나 당일 가격이 끝났으면 null. 둘을 같은 뜻으로 읽지 않는다. */
  nextTickAt: string | null;
  phaseEndsAt: string | null;
  feeFree: boolean;
  feeFreeEndsAt: string | null;
  leaderboardBlackout: boolean;
  replayEpoch: number;
  /** 예약 대기일 때만 값이 있다. 시작하면 null 로 돌아온다. */
  scheduledStartAt: string | null;
  /** 지점 이동이 진행 중인가. 목표 미도달 정지와 도달을 구별한다. */
  warping: boolean;
};

export const REPLAY_PATH = "/api/v1/admin/replay";

export const MARKS_PATH = `${REPLAY_PATH}/marks`;

export const SEEK_PATH = `${REPLAY_PATH}/seek`;

/**
 * 이동 지점 하나. **서버가 시즌 편성에서 계산한다** — 월 → Tick 매핑과 "당일 마지막 Tick"은
 * 거래일 달력에서 파생되므로 화면이 표를 복제하면 편성을 바꿀 때 두 곳이 갈라진다.
 */
/**
 * 이동 지점의 갈래. **서버가 준다** — `key` 의 생김새를 화면이 파싱하면 서버가 식별자를
 * 바꾸는 순간 필터가 조용히 비어 버린다.
 */
export type ReplayMarkKind =
  "BOUNDARY" | "MONTH" | "IPO" | "LISTING" | "DAY_LAST_TICK";

export type ReplayMark = {
  key: string;
  kind: ReplayMarkKind;
  label: string;
  seasonDay: number;
  /** 그 일차의 가상 초 위치. */
  offsetSec: number;
  /** 가격 Tick 인 경우만 값이 있다. 폐장·정산 발표는 Tick 이 아니므로 null 이다. */
  tickSeq: number | null;
  passed: boolean;
};

export type ReplaySeekResponse = {
  targetSeasonDay: number;
  targetOffsetSec: number;
  targetTickSeq: number | null;
  /** 이 이동이 시즌을 초기화했는가. 뒤로 가는 이동에서만 참이다. */
  reseeded: boolean;
  clock: ReplayClock;
};

/** 몇 Tick 앞에 설지의 기본값. 이벤트를 세팅을 마친 뒤 보기 위한 여유다. */
export const DEFAULT_TICKS_BEFORE = 3;

/**
 * 이 지점에서 물러설 수 있는 최대 Tick 수. **시즌 첫 Tick 보다 앞에는 설 수 없다.**
 *
 * 가격 Tick 지점은 **시즌 첫 Tick 으로부터의 거리**가 답이다(첫 틱에서는 물러설 곳이 없다).
 * 시간 경계는 그 앞의 마지막 Tick 지점으로 하한을 잡는다 — 1일차 브리핑·개장처럼 앞선 Tick 이
 * 하나도 없는 지점이 `0` 이 되고, 그 행은 "그 지점 자체"로만 갈 수 있다.
 *
 * **순번에서 1 을 빼지 않는다.** `tickSeq` 는 시즌별 순번이 아니라 전역 식별자여서 시즌마다
 * 번호 구간이 다르다 — 시즌 2 의 첫 Tick 1000001 에서 1 을 빼면 물러설 곳이 100만 개라고
 * 말하게 된다. 서버는 자기 시즌 밖의 Tick 을 찾지 않으므로 그 요청은 400 으로 거절되고,
 * 그것을 미리 막는 것이 이 함수의 일이다.
 *
 * @param marks        전체 이동 지점 목록
 * @param mark         대상 지점
 * @param firstTickSeq 시즌 첫 Tick 의 순번 (`ReplayClock.firstTickSeq`)
 */
export function maxTicksBefore(
  marks: ReplayMark[],
  mark: ReplayMark,
  firstTickSeq: number | null
): number {
  if (mark.tickSeq !== null) {
    return Math.max(0, mark.tickSeq - (firstTickSeq ?? mark.tickSeq));
  }
  const last = lastTickSeqAtOrBefore(marks, mark);
  return last === null ? 0 : Math.max(0, last - (firstTickSeq ?? last) + 1);
}

/**
 * 이 이동이 시즌을 초기화하는가. **뒤로 가는 이동만 초기화한다.**
 *
 * 착지 지점은 지정한 Mark 보다 앞이거나 같으므로, Mark 가 현재보다 뒤면 확실히 초기화다.
 * Mark 가 앞이어도 N Tick 물러나면 현재보다 뒤가 될 수 있다 — 가격 Tick 지점은 순번으로
 * 정확히 판정하고, 시간 경계는 그 경계 이전의 마지막 Tick 지점으로 **하한**을 잡는다.
 *
 * **판정할 수 없으면 초기화 쪽으로 기운다.** 확인 대화상자를 한 번 더 띄우는 쪽이 세팅해 둔
 * 계좌를 모르고 날리는 쪽보다 싸다. 실제 결과는 응답의 `reseeded` 가 말한다.
 *
 * @param marks       전체 이동 지점 목록. 경계 지점의 착지를 가늠하는 데 쓴다
 * @param mark        이동할 지점
 * @param ticksBefore 그 지점보다 몇 Tick 앞에 설지
 * @param clock       마지막으로 받은 시계
 * @param position    로컬에서 메운 현재 게임 위치
 */
export function seekResetsSeason(
  marks: ReplayMark[],
  mark: ReplayMark,
  ticksBefore: number,
  clock: ReplayClock,
  position: number
): boolean {
  if (mark.seasonDay !== clock.seasonDay) {
    return mark.seasonDay < clock.seasonDay;
  }
  if (mark.offsetSec < position) {
    return true;
  }
  if (ticksBefore === 0) {
    return false;
  }
  // 착지 Tick 순번을 안다: 그 순번이 지금 공개된 Tick 보다 앞이면 되돌아가는 이동이다.
  const landing =
    mark.tickSeq ?? lastTickSeqAtOrBefore(marks, mark) ?? clock.tickSeq;
  if (landing === null) {
    return false;
  }
  return landing - ticksBefore < (clock.tickSeq ?? 0);
}

/**
 * 앞으로 이동이 처리할 분량. **확인 대화상자 대신 이것을 버튼 옆에 둔다** — 파괴적인 쪽에만
 * 확인을 둬야 그 확인이 읽힌다.
 *
 * `events` 는 경유하는 이동 지점 수로 정확하다. `ticks` 는 목표가 가격 Tick 이면 정확하고,
 * 시간 경계면 그 경계 이전의 마지막 Tick 지점까지만 셀 수 있어 어림값이다.
 *
 * @param marks       전체 이동 지점 목록
 * @param mark        이동할 지점
 * @param ticksBefore 그 지점보다 몇 Tick 앞에 설지
 * @param clock       마지막으로 받은 시계
 * @returns 처리할 Tick 수(셀 수 없으면 null)·이벤트 수·Tick 수가 정확한지
 */
export function seekPreview(
  marks: ReplayMark[],
  mark: ReplayMark,
  ticksBefore: number,
  clock: ReplayClock
): { ticks: number | null; events: number; exact: boolean } {
  const target = { seasonDay: mark.seasonDay, offsetSec: mark.offsetSec };
  const events = marks.filter(
    (other) => !other.passed && atOrBefore(other, target)
  ).length;

  // 최초 공개 전에는 "첫 Tick 직전" 에 서 있는 것으로 센다. 0 으로 두면 시즌 2 처럼 첫 Tick
  // 번호가 큰 시즌에서 처리할 분량이 그 번호만큼(100만 개) 나온다.
  const published = clock.tickSeq ?? (clock.firstTickSeq ?? 1) - 1;
  if (mark.tickSeq !== null) {
    return {
      ticks: Math.max(0, mark.tickSeq - ticksBefore - published),
      events,
      exact: true,
    };
  }
  // 시간 경계는 Tick 이 아니다. 그 경계 이전의 마지막 Tick 지점까지로 어림한다.
  const last = lastTickSeqAtOrBefore(marks, mark);
  return {
    ticks: last === null ? null : Math.max(0, last - published),
    events,
    exact: false,
  };
}

/**
 * 그 지점까지 확실히 지나는 마지막 가격 Tick 의 순번. 목록에 없는 Tick 이 그 사이에 더 있을
 수 있으므로 **하한**이다.
 *
 * @returns 그런 Tick 지점이 없으면 null
 */
function lastTickSeqAtOrBefore(
  marks: ReplayMark[],
  target: { seasonDay: number; offsetSec: number }
): number | null {
  const seqs = marks
    .filter((mark) => mark.tickSeq !== null && atOrBefore(mark, target))
    .map((mark) => mark.tickSeq as number);
  return seqs.length === 0 ? null : Math.max(...seqs);
}

/** 목록이 일차·위치 오름차순이라는 계약에 기대지 않고 좌표로 비교한다. */
function atOrBefore(
  mark: ReplayMark,
  target: { seasonDay: number; offsetSec: number }
): boolean {
  return (
    mark.seasonDay < target.seasonDay ||
    (mark.seasonDay === target.seasonDay && mark.offsetSec <= target.offsetSec)
  );
}

/** 정산 발표 위치(게임 속 17:00). 그 일차 위치의 상한이다. */
export const ANNOUNCE_OFFSET = 21300;

export const SPEED_MAX = 100;

/**
 * 폴링 간격 사이를 로컬에서 메운 게임 위치.
 *
 * 서버 스냅샷의 `offsetSec` 을 그대로 그리면 재조회 주기(2초)만큼 위치가 뭉텅이로 뛴다 —
 * 1배속에서 2초씩, 10배속에서는 20초씩 점프해 시계가 멈춘 듯 보인다. 카운트다운이 이미
 * 절대 시각과 현재 시각의 차이로 매번 다시 계산하는 것과 같은 방식으로 위치도 메운다.
 * **서버가 진실이고 이 값은 다음 재조회에 보정된다.**
 *
 * @param clock      마지막으로 받은 시계
 * @param elapsedSec 그 응답을 받은 뒤 흐른 로컬 시간(초)
 * @returns 지금 화면에 그릴 게임 위치
 */
export function livePosition(clock: ReplayClock, elapsedSec: number): number {
  // 예약 대기 중에는 서버가 위치를 브리핑에 고정한다. 로컬에서 밀면 화면만 앞서 간다.
  if (clock.scheduledStartAt !== null) {
    return clock.offsetSec;
  }
  // 음수는 로컬 시계가 뒤로 간 경우다. 위치를 되돌리지 않는다.
  const advanced = clock.offsetSec + Math.max(0, elapsedSec) * clock.speed;
  // 상한을 넘는 진행은 서버가 일차 전환으로 처리한다. 그때까지 화면은 상한에 머문다.
  return Math.min(ANNOUNCE_OFFSET, advanced);
}

/**
 * 배속 입력을 계약(0~100, 소수 둘째 자리)에 맞춰 읽는다. 서버가 조용히 반올림하지 않으므로
 * 화면도 반올림해서 보내지 않는다 — 어긋난 값은 보내기 전에 거절한다.
 *
 * @returns 계약을 지키면 숫자, 아니면 null
 */
export function parseSpeed(input: string): number | null {
  const trimmed = input.trim();
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed);
  return value > SPEED_MAX ? null : value;
}

/**
 * 게임 속 시각. 오프셋 0 이 11:05 이고 브리핑(-300)은 11:00 이다.
 * 실제 시각과 다른 좌표이므로 화면에서도 다른 항목으로 낸다.
 */
export function gameTimeLabel(offsetSec: number): string {
  // 오프셋 하한이 -300 이라 11:00 밑으로 내려가지 않는다 — 자정을 넘길 일이 없다.
  return new Date(Date.UTC(1970, 0, 1, 11, 5, Math.round(offsetSec)))
    .toISOString()
    .slice(11, 19);
}

/**
 * 관리자 재생 화면이 붙는 **Spring** 의 WS 주소.
 *
 * `lib/ws.ts` 의 `websocketUrl()` 과 일부러 분리한다 — 재생 시계는 Spring 이 소유하므로
 * `MOCK_MARKET=1` 로 마켓만 목에 넘긴 상태(e2e·독립 확인)에서도 관리자 화면은 Spring WS 에
 * 붙어야 한다. 전역 주소를 함께 쓰면 그 상태에서 관리자 화면이 목 WS 를 본다.
 *
 * 배포는 nginx 가 같은 origin 의 `/ws` 를 Spring 으로 넘기므로 기본값이 맞다.
 */
export function replayWebsocketUrl(): string {
  if (process.env.NODE_ENV !== "production") {
    // 로컬은 rewrite 가 업그레이드 요청을 넘기지 못하므로 Spring 에 직결한다.
    return process.env.NEXT_PUBLIC_SPRING_WS_URL ?? "ws://localhost:8080/ws";
  }

  const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${window.location.host}/ws`;
}
