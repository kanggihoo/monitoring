import { describe, expect, it } from "vitest";

import {
  clockOffsetMs,
  formatCountdown,
  remainingSeconds,
} from "@/lib/game-clock";

const SERVER_NOW = "2026-09-21T05:31:33Z";
const PHASE_ENDS = "2026-09-21T07:40:00Z"; // 폐장까지 2:08:27
const NEXT_TICK = "2026-09-21T05:32:07Z"; // 다음 종가까지 34초

describe("게임 시계 오프셋", () => {
  it("로컬 시계가 5분 느려도 남은 시간은 서버 기준이다", () => {
    const local = Date.parse(SERVER_NOW) - 5 * 60_000;
    const offset = clockOffsetMs(SERVER_NOW, local);

    expect(remainingSeconds(NEXT_TICK, offset, local)).toBe(34);
    expect(remainingSeconds(PHASE_ENDS, offset, local)).toBe(
      2 * 3600 + 8 * 60 + 27
    );
  });

  it("로컬 시계가 하루 빨라도 같은 값이다", () => {
    const local = Date.parse(SERVER_NOW) + 24 * 3600_000;
    const offset = clockOffsetMs(SERVER_NOW, local);

    expect(remainingSeconds(NEXT_TICK, offset, local)).toBe(34);
  });

  it("긴 공백 뒤에도 누적 오차가 없다 — 절대 시각에서 다시 계산한다", () => {
    const local = Date.parse(SERVER_NOW);
    const offset = clockOffsetMs(SERVER_NOW, local);

    // 탭이 30분 백그라운드에 있었다. 타이머는 한 번도 돌지 않았다.
    const later = local + 30 * 60_000;
    expect(remainingSeconds(PHASE_ENDS, offset, later)).toBe(
      2 * 3600 + 8 * 60 + 27 - 30 * 60
    );
  });

  it("지난 시각은 0 이고, 정지 중(null)은 null 이다", () => {
    const local = Date.parse(SERVER_NOW);
    const offset = clockOffsetMs(SERVER_NOW, local);

    expect(remainingSeconds(NEXT_TICK, offset, local + 60_000)).toBe(0);
    expect(remainingSeconds(null, offset, local)).toBeNull();
  });

  it("카운트다운 표기", () => {
    expect(formatCountdown(34)).toBe("00:34");
    expect(formatCountdown(7707)).toBe("2:08:27");
    expect(formatCountdown(-3)).toBe("00:00");
  });
});
