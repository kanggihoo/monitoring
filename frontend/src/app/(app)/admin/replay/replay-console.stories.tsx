import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { ReplayClock } from "@/lib/replay";
import { mockFetch } from "@/stories/mock-fetch";

import { ReplayConsole } from "./replay-console";

const CLOCK: ReplayClock = {
  seasonId: 1,
  seasonDay: 2,
  offsetSec: 5000,
  anchorOffsetSec: 5000,
  anchorAt: "2026-09-21T02:05:00.000Z",
  speed: 1,
  phase: "TRADING",
  serverTime: "2026-09-21T03:28:20.000Z",
  tickSeq: 130,
  firstTickSeq: 1,
  gameDate: "2020-05-14",
  gameMonth: "2020-05",
  nextTickAt: "2026-09-21T03:28:51.000Z",
  phaseEndsAt: "2026-09-21T07:00:00.000Z",
  feeFree: false,
  feeFreeEndsAt: null,
  leaderboardBlackout: false,
  replayEpoch: 0,
  scheduledStartAt: null,
  warping: false,
};

/**
 * 2초마다 상태를 다시 읽는다 — 스토리도 같은 값을 계속 돌려준다. 지점 이동 표는 따로
 * 조회하므로 여기서는 비워 두고, 그 표의 모습은 `Screens/Admin/ReplayMarks` 에 있다.
 *
 * STOMP 연결은 붙지 않는다 (스토리북에 Spring 이 없다). `연결 끊김` 으로 뜨는 것이 정상이다.
 */
const routes = (clock: ReplayClock) => ({
  "/api/v1/admin/replay/marks": [],
  "/api/v1/admin/replay": clock,
});

const meta = {
  title: "Screens/Admin/ReplayConsole",
  component: ReplayConsole,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { initial: CLOCK },
  beforeEach: () => mockFetch(routes(CLOCK)),
} satisfies Meta<typeof ReplayConsole>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 1배속으로 흐르는 중. */
export const Running: Story = {};

/** 정지. `speed: 0` 이고 다음 틱 시각이 없다 — 당일 가격이 끝난 것과 구별해 적는다. */
export const Paused: Story = {
  args: { initial: { ...CLOCK, speed: 0, nextTickAt: null } },
  beforeEach: () => mockFetch(routes({ ...CLOCK, speed: 0, nextTickAt: null })),
};

/** 60배속. 빨리 감기 중에도 게임 시각과 실제 시각을 구별해 보인다. */
export const FastForward: Story = {
  args: { initial: { ...CLOCK, speed: 60 } },
  beforeEach: () => mockFetch(routes({ ...CLOCK, speed: 60 })),
};

/** 시작 예약 중. 앵커 시각이 미래다. */
export const Scheduled: Story = {
  args: {
    initial: {
      ...CLOCK,
      speed: 0,
      tickSeq: null,
      firstTickSeq: null,
      scheduledStartAt: "2026-09-22T02:05:00.000Z",
      phase: "BRIEFING",
    },
  },
  beforeEach: () =>
    mockFetch(
      routes({
        ...CLOCK,
        speed: 0,
        tickSeq: null,
        firstTickSeq: null,
        scheduledStartAt: "2026-09-22T02:05:00.000Z",
        phase: "BRIEFING",
      })
    ),
};

/** 지점 이동이 진행 중. 목표에 닿지 않은 정지와 도달을 구별한다. */
export const Warping: Story = {
  args: { initial: { ...CLOCK, warping: true } },
  beforeEach: () => mockFetch(routes({ ...CLOCK, warping: true })),
};

export const Dark: Story = { globals: { theme: "dark" } };
