import { describe, expect, it } from "vitest";

import {
  ANNOUNCE_OFFSET,
  gameTimeLabel,
  livePosition,
  maxTicksBefore,
  parseSpeed,
  type ReplayClock,
  type ReplayMark,
  seekPreview,
  seekResetsSeason,
} from "@/lib/replay";

describe("배속 입력", () => {
  it("계약 범위의 값을 받는다", () => {
    expect(parseSpeed("0")).toBe(0);
    expect(parseSpeed("1")).toBe(1);
    expect(parseSpeed("0.25")).toBe(0.25);
    expect(parseSpeed("99.99")).toBe(99.99);
    expect(parseSpeed("100")).toBe(100);
    expect(parseSpeed(" 1.5 ")).toBe(1.5);
  });

  it("범위 밖·음수·초과 소수 정밀도를 반올림하지 않고 거절한다", () => {
    // 서버가 조용히 반올림하지 않으므로 화면도 반올림해 보내지 않는다.
    expect(parseSpeed("-1")).toBeNull();
    expect(parseSpeed("100.01")).toBeNull();
    expect(parseSpeed("101")).toBeNull();
    expect(parseSpeed("0.001")).toBeNull();
    expect(parseSpeed("1.234")).toBeNull();
  });

  it("숫자가 아닌 입력을 거절한다", () => {
    expect(parseSpeed("")).toBeNull();
    expect(parseSpeed("빠르게")).toBeNull();
    expect(parseSpeed("1e2")).toBeNull();
  });
});

describe("게임 속 시각", () => {
  it("오프셋 0 이 거래 시작 11:05 다", () => {
    expect(gameTimeLabel(0)).toBe("11:05:00");
  });

  it("브리핑 -300 은 11:00 이다", () => {
    // 게임 속 11:05 는 오프셋 기준점이지 실제 시작 시각이 아니다.
    expect(gameTimeLabel(-300)).toBe("11:00:00");
  });

  it("주요 경계가 계약된 게임 시각과 맞는다", () => {
    expect(gameTimeLabel(3300)).toBe("12:00:00");
    expect(gameTimeLabel(3900)).toBe("12:10:00");
    expect(gameTimeLabel(17700)).toBe("16:00:00");
    expect(gameTimeLabel(20100)).toBe("16:40:00");
    expect(gameTimeLabel(21300)).toBe("17:00:00");
  });
});

describe("폴링 사이의 게임 위치", () => {
  const clock = (over: Partial<ReplayClock> = {}): ReplayClock =>
    ({
      seasonId: 1,
      seasonDay: 1,
      offsetSec: 0,
      anchorOffsetSec: 0,
      anchorAt: "2026-09-21T02:05:00.000Z",
      speed: 1,
      phase: "TRADING",
      serverTime: "2026-09-21T02:05:00.000Z",
      tickSeq: 1,
      gameDate: "2020-01-02",
      gameMonth: "2020-01",
      nextTickAt: null,
      phaseEndsAt: null,
      feeFree: false,
      feeFreeEndsAt: null,
      leaderboardBlackout: false,
      replayEpoch: 0,
      scheduledStartAt: null,
      firstTickSeq: 1,
      ...over,
    }) as ReplayClock;

  it("배속을 곱해 흐른 만큼 메운다", () => {
    // 2초 폴링 사이가 비면 1배속은 2초, 10배속은 20초씩 뭉텅이로 뛴다.
    expect(livePosition(clock({ speed: 1 }), 2)).toBe(2);
    expect(livePosition(clock({ speed: 10 }), 2)).toBe(20);
    expect(livePosition(clock({ speed: 0.25 }), 8)).toBe(2);
  });

  it("정지 중에는 위치가 흐르지 않는다", () => {
    expect(livePosition(clock({ speed: 0, offsetSec: -120 }), 30)).toBe(-120);
  });

  it("예약 대기 중에는 서버가 고정한 위치를 그대로 쓴다", () => {
    // 서버는 예정 시각까지 -300 에 머문다. 로컬에서 밀면 화면만 앞서 간다.
    const waiting = clock({
      offsetSec: -300,
      speed: 1,
      scheduledStartAt: "2026-09-21T03:00:00.000Z",
    });

    expect(livePosition(waiting, 120)).toBe(-300);
  });

  it("발표 위치를 넘어서 그리지 않는다", () => {
    // 상한을 넘는 진행은 서버가 일차 전환으로 처리한다.
    const nearEnd = clock({ offsetSec: ANNOUNCE_OFFSET - 5, speed: 100 });

    expect(livePosition(nearEnd, 60)).toBe(ANNOUNCE_OFFSET);
  });

  it("로컬 시계가 뒤로 가도 위치를 되돌리지 않는다", () => {
    expect(livePosition(clock({ offsetSec: 500 }), -30)).toBe(500);
  });
});

describe("지점 이동", () => {
  const clock = (over: Partial<ReplayClock> = {}): ReplayClock =>
    ({
      seasonId: 1,
      seasonDay: 1,
      offsetSec: 0,
      anchorOffsetSec: 0,
      anchorAt: "2026-09-21T02:05:00.000Z",
      speed: 0,
      phase: "TRADING",
      serverTime: "2026-09-21T02:05:00.000Z",
      tickSeq: 10,
      gameDate: "2020-01-02",
      gameMonth: "2020-01",
      nextTickAt: null,
      phaseEndsAt: null,
      feeFree: false,
      feeFreeEndsAt: null,
      leaderboardBlackout: false,
      replayEpoch: 0,
      scheduledStartAt: null,
      warping: false,
      ...over,
    }) as ReplayClock;

  const mark = (over: Partial<ReplayMark> = {}): ReplayMark => ({
    key: "month.2020-02",
    kind: "MONTH",
    label: "2020-02 첫 틱",
    seasonDay: 1,
    offsetSec: 5000,
    tickSeq: 30,
    passed: false,
    ...over,
  });

  describe("초기화 판정", () => {
    /** 경계 지점의 착지를 가늠할 때 쓰는 목록. 30 번이 폐장 이전의 마지막 Tick 지점이다. */
    const all = [
      mark({ key: "t12", offsetSec: 100, tickSeq: 12 }),
      mark({ key: "b3300", offsetSec: 3300, tickSeq: null }),
      mark({ key: "t30", offsetSec: 5000, tickSeq: 30 }),
      mark({ key: "b20100", offsetSec: 20100, tickSeq: null }),
    ];

    it("앞선 일차는 초기화이고 뒤 일차는 아니다", () => {
      const now = clock({ seasonDay: 2, offsetSec: 100 });
      expect(seekResetsSeason(all, mark({ seasonDay: 1 }), 0, now, 100)).toBe(
        true
      );
      expect(seekResetsSeason(all, mark({ seasonDay: 3 }), 0, now, 100)).toBe(
        false
      );
    });

    it("같은 일차에서 현재보다 뒤면 초기화다", () => {
      expect(
        seekResetsSeason(all, mark({ offsetSec: 4000 }), 0, clock(), 5000)
      ).toBe(true);
    });

    it("같은 위치로의 이동은 초기화가 아니다", () => {
      // 앵커가 그 위치에 그대로 서 있으므로 진행 결과를 지울 이유가 없다.
      expect(
        seekResetsSeason(all, mark({ offsetSec: 5000 }), 0, clock(), 5000)
      ).toBe(false);
    });

    it("앞선 지점이라도 Tick 을 물러나 현재보다 뒤가 되면 초기화다", () => {
      const now = clock({ tickSeq: 29 });
      expect(seekResetsSeason(all, mark({ tickSeq: 30 }), 3, now, 100)).toBe(
        true
      );
      expect(seekResetsSeason(all, mark({ tickSeq: 30 }), 1, now, 100)).toBe(
        false
      );
    });

    it("시간 경계도 그 경계 이전의 마지막 Tick 지점으로 판정한다", () => {
      // 경계 지점에는 Tick 번호가 없다. 그 판정을 건너뛰면 서버가 초기화하는 이동이
      // 확인 대화상자 없이 나간다.
      const boundary = mark({ key: "b20100", offsetSec: 20100, tickSeq: null });
      expect(
        seekResetsSeason(all, boundary, 3, clock({ tickSeq: 29 }), 100)
      ).toBe(true);
      expect(
        seekResetsSeason(all, boundary, 3, clock({ tickSeq: 20 }), 100)
      ).toBe(false);
    });
  });

  describe("처리량 미리보기", () => {
    const marks = [
      mark({ key: "a", offsetSec: 100, tickSeq: 12, passed: true }),
      mark({ key: "b", offsetSec: 3300, tickSeq: null }),
      mark({ key: "c", offsetSec: 5000, tickSeq: 30 }),
      mark({ key: "d", offsetSec: 20100, tickSeq: null }),
    ];

    it("가격 Tick 목표는 착지 순번까지 정확히 센다", () => {
      const preview = seekPreview(marks, marks[2]!, 3, clock({ tickSeq: 10 }));

      expect(preview.ticks).toBe(17);
      expect(preview.exact).toBe(true);
      // 지나가지 않은 지점 중 목표 이하인 것: b · c
      expect(preview.events).toBe(2);
    });

    it("시간 경계 목표는 그 앞의 마지막 Tick 지점까지로 어림한다", () => {
      const preview = seekPreview(marks, marks[3]!, 0, clock({ tickSeq: 10 }));

      expect(preview.ticks).toBe(20);
      expect(preview.exact).toBe(false);
      expect(preview.events).toBe(3);
    });

    it("아직 아무 Tick 도 공개되지 않았으면 0 에서 센다", () => {
      expect(
        seekPreview(marks, marks[2]!, 0, clock({ tickSeq: null })).ticks
      ).toBe(30);
    });
  });

  describe("물러설 수 있는 최대 Tick 수", () => {
    const all = [
      mark({ key: "b-300", kind: "BOUNDARY", offsetSec: -300, tickSeq: null }),
      mark({ key: "t1", offsetSec: 33, tickSeq: 1 }),
      mark({ key: "t30", offsetSec: 5000, tickSeq: 30 }),
      mark({
        key: "b20100",
        kind: "BOUNDARY",
        offsetSec: 20100,
        tickSeq: null,
      }),
    ];

    it("가격 Tick 지점은 자기 순번 직전까지다", () => {
      // 1 번 틱에서는 물러설 곳이 없다. 시즌 첫 Tick 보다 앞은 존재하지 않는다.
      expect(maxTicksBefore(all, all[1]!, 1)).toBe(0);
      expect(maxTicksBefore(all, all[2]!, 1)).toBe(29);
    });

    it("시간 경계는 그 앞의 마지막 Tick 지점으로 가늠한다", () => {
      // 1일차 브리핑 앞에는 Tick 이 하나도 없다 — 그 지점 자체로만 갈 수 있다.
      expect(maxTicksBefore(all, all[0]!, 1)).toBe(0);
      expect(maxTicksBefore(all, all[3]!, 1)).toBe(30);
    });

    it("첫 Tick 번호가 1 이 아닌 시즌도 같은 개수를 센다", () => {
      // tick_seq 는 전역 식별자라 시즌 2 는 1000001 부터 시작한다. 순번에서 1 을 빼면
      // 첫 Tick 에서 100만 개를 물러설 수 있다고 말하게 되고, 서버는 그 요청을 400 으로
      // 거절한다. 세는 기준은 절대 순번이 아니라 시즌 첫 Tick 이다.
      const s2 = [
        mark({
          key: "b-300",
          kind: "BOUNDARY",
          offsetSec: -300,
          tickSeq: null,
        }),
        mark({ key: "t1", offsetSec: 33, tickSeq: 1_000_001 }),
        mark({ key: "t30", offsetSec: 5000, tickSeq: 1_000_030 }),
        mark({
          key: "b20100",
          kind: "BOUNDARY",
          offsetSec: 20100,
          tickSeq: null,
        }),
      ];
      expect(maxTicksBefore(s2, s2[1]!, 1_000_001)).toBe(0);
      expect(maxTicksBefore(s2, s2[2]!, 1_000_001)).toBe(29);
      expect(maxTicksBefore(s2, s2[0]!, 1_000_001)).toBe(0);
      expect(maxTicksBefore(s2, s2[3]!, 1_000_001)).toBe(30);
    });
  });
});
