import { firstTickOfGameMonth, LAST_TICK_SEQ, snapshot } from "./clock.ts";
import { advance, clockState, seek, setSpeed } from "./state.ts";

/**
 * 재생 제어 — **목 전용 기능이 아니라 계약이다** (`adm.md` F-ADM-02).
 *
 * 목 전용 경로로 두면 목이 사라지는 순간 함께 사라지고, 테스트 서버에서 같은 것을 화면까지
 * 처음부터 다시 만들게 된다. 계약으로 두면 목은 "Spring 이 나중에 구현할 엔드포인트를 먼저
 * 구현한 것" 이 되고, 이관 규칙이 여기에도 그대로 적용된다.
 *
 * 프로덕션에는 존재하지 않는다. Spring 쪽 게이팅은 기존 테스트 지원 스위치
 * (`app.test-support.enabled`)를 재사용한다 — 새 스위치를 만들지 않는다.
 */

export type ReplayState = ReturnType<typeof snapshot> & {
  speed: number;
  anchorTickSeq: number;
  /** 이 요청이 계좌·주문·체결을 초기화했는지. 숨기지 않고 응답에 담는다. */
  accountsReset: boolean;
};

export function replayState(accountsReset: boolean): ReplayState {
  const state = clockState();

  return {
    ...snapshot(state, Date.now()),
    speed: state.speed,
    anchorTickSeq: state.anchorTickSeq,
    accountsReset,
  };
}

export type PatchBody = {
  speed?: unknown;
  anchorTickSeq?: unknown;
  anchorGameMonth?: unknown;
};

/** 요청이 거부되는 이유. `null` 이면 통과다. */
export type Rejection = { status: number; message: string };

/**
 * `PATCH /api/v1/admin/replay` — 배속과 앵커를 **한 요청에서 함께** 받는다.
 *
 * 같은 시계 하나를 건드리므로 엔드포인트를 나누면 둘을 동시에 바꾸는 요청이 두 번 왕복이
 * 된다. `speed: 0` 이 정지다.
 *
 * @returns 거부 이유. 통과하면 `null` 이고 시계가 바뀐다
 */
export function applyPatch(body: PatchBody): Rejection | null {
  const { speed, anchorTickSeq, anchorGameMonth } = body;

  if (
    speed === undefined &&
    anchorTickSeq === undefined &&
    anchorGameMonth === undefined
  ) {
    return {
      status: 400,
      message:
        "speed · anchorTickSeq · anchorGameMonth 중 최소 하나가 필요합니다.",
    };
  }

  // 같은 앵커를 두 좌표계로 가리키면 어느 쪽이 이기는지 정할 근거가 없다.
  if (anchorTickSeq !== undefined && anchorGameMonth !== undefined) {
    return {
      status: 400,
      message: "anchorTickSeq 와 anchorGameMonth 를 함께 지정할 수 없습니다.",
    };
  }

  if (speed !== undefined) {
    if (typeof speed !== "number" || !Number.isFinite(speed) || speed < 0) {
      return { status: 400, message: "speed 는 0 이상의 수여야 합니다." };
    }
  }

  // 되감기 좌표계를 둘 다 받고 **서버가 변환한다.** 테스트는 틱 번호로 지점을 특정하고,
  // 사람은 게임 월로 말한다("IPO 직전으로 가"). 월 → 틱 매핑은 거래일 달력에서 파생되므로
  // 서버만 알 수 있다.
  let target: number | undefined;

  if (anchorTickSeq !== undefined) {
    if (
      typeof anchorTickSeq !== "number" ||
      !Number.isInteger(anchorTickSeq) ||
      anchorTickSeq < 0 ||
      anchorTickSeq > LAST_TICK_SEQ
    ) {
      return {
        status: 400,
        message: `anchorTickSeq 는 0 이상 ${LAST_TICK_SEQ} 이하의 정수여야 합니다.`,
      };
    }
    target = anchorTickSeq;
  }

  if (anchorGameMonth !== undefined) {
    if (typeof anchorGameMonth !== "string") {
      return {
        status: 400,
        message: "anchorGameMonth 는 YYYY-MM 형식이어야 합니다.",
      };
    }

    const firstTick = firstTickOfGameMonth(anchorGameMonth);
    if (firstTick === undefined) {
      return {
        status: 400,
        message: `${anchorGameMonth} 은 1일차 재생 구간에 없습니다.`,
      };
    }
    target = firstTick;
  }

  // 배속을 먼저 바꾼다. seek 이 앵커 시각을 지금으로 놓으므로 순서가 뒤바뀌면 새 앵커가
  // 낡은 배속으로 해석된다.
  if (speed !== undefined) {
    setSpeed(speed);
  }
  if (target !== undefined) {
    seek(target);
  }

  return null;
}

/**
 * `POST /api/v1/admin/replay/advance` — 틱 하나를 **정상 발행**한다.
 *
 * 가격·체결 판정·브로드캐스트가 전부 일어나고 **계좌는 그대로다.** 이것이 seek 과 다른
 * 점이고, 그래서 엔드포인트가 갈린다 — 하나로 묶으면 한 칸 전진할 때마다 계좌가 초기화되어
 * "예약이 다음 Tick 에 체결되는 것을 눈으로 보기" 라는 용도가 정확히 죽는다.
 */
export function applyAdvance(): Rejection | null {
  const state = clockState();

  if (state.speed !== 0) {
    return {
      status: 409,
      message:
        "수동 전진은 정지 상태에서만 의미가 있습니다. speed 를 0 으로 먼저 바꾸세요.",
    };
  }
  if (state.anchorTickSeq >= LAST_TICK_SEQ) {
    return { status: 409, message: "1일차 재생이 이미 끝났습니다." };
  }

  advance();
  return null;
}
