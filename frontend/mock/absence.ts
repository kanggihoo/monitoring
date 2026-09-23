/**
 * 부재 중 요약 (`acc.md`). **목의 가변 상태다** — `lastAckedTickSeq` 하나가 전부이고
 * 되감기가 초기화한다 (`state.ts` 의 `resetGameState`).
 *
 * 판정은 서버가 한다: `현재 tickSeq − lastAckedTickSeq ≥ 50` (게임 50거래일 ≒ 실제 30분).
 * 프론트는 이 계산을 하지 않으므로 목이 실제로 판정해야 "확인하지 않고 닫으면 다시 뜬다" 를
 * 화면에서 볼 수 있다.
 *
 * **구간 숫자는 유도값이다.** 목에 예약·체결·이벤트가 없어 셀 것이 없으므로 놓친 틱 수에서
 * 만든다 — 목적은 "50틱을 비우면 모달이 뜨고, 확인하면 그 지점부터 다시 센다" 를 보이는
 * 것이지 숫자의 진실성이 아니다. 백엔드가 붙으면 이 파일은 통째로 사라진다.
 */

/** 부재 판정 기준. 게임 50거래일이다. */
const ABSENCE_THRESHOLD = 50;

/** 확인한 지점. 시드는 0 이고 되감기가 여기로 되돌린다. */
let lastAckedTickSeq = 0;

/** 되감기가 부른다 (`state.ts` 의 `resetGameState`). */
export function resetAbsence(): void {
  lastAckedTickSeq = 0;
}

/**
 * 확인. 서버는 `max(기존, 받은 값)` 으로 갱신한다 — 재시도나 지연으로 낡은 값이 도착해도
 * 뒤로 가지 않고, 같은 값을 여러 번 보내도 결과가 같다.
 */
export function acknowledgeAbsence(ackedTickSeq: number): void {
  lastAckedTickSeq = Math.max(lastAckedTickSeq, ackedTickSeq);
}

export type AbsenceSummary =
  | { absent: false }
  | {
      absent: true;
      fromTickSeq: number;
      toTickSeq: number;
      fromGameDate: string;
      toGameDate: string;
      filledOrderCount: number;
      valuationChangeKrw: string;
      returnChange: number;
      eventCount: number;
    };

/**
 * 부재 요약. **상태를 바꾸지 않는다** — 확인은 별도 경로다.
 *
 * @param gameDateOf 틱을 게임 거래일로. 시계를 여기서 다시 계산하지 않는다
 */
export function absenceSummary(
  currentTickSeq: number,
  gameDateOf: (tickSeq: number) => string
): AbsenceSummary {
  const missed = currentTickSeq - lastAckedTickSeq;
  if (missed < ABSENCE_THRESHOLD) {
    return { absent: false };
  }

  return {
    absent: true,
    fromTickSeq: lastAckedTickSeq,
    toTickSeq: currentTickSeq,
    fromGameDate: gameDateOf(lastAckedTickSeq),
    toGameDate: gameDateOf(currentTickSeq),
    filledOrderCount: Math.floor(missed / 40),
    // 놓친 구간이 길수록 크게 흔들린다. 부호는 구간에 따라 갈린다 — 부재가 언제나 손해인
    // 화면만 보이면 "비워도 지지 않는다" 는 이 기능의 요점이 검증되지 않는다.
    valuationChangeKrw: String(swing(missed) * 10_000),
    returnChange: Math.round(swing(missed) * 3.7) / 100,
    eventCount: Math.floor(missed / 25),
  };
}

/** 구간 길이에서 유도한 진폭. 같은 구간이면 언제나 같은 값이다. */
function swing(missed: number): number {
  return ((missed % 37) - 18) * Math.ceil(missed / 50);
}
