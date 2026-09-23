import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { ReplayClock, ReplayMark } from "@/lib/replay";

import { ReplayMarks } from "./replay-marks";

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
  // `tickSeq` 는 시즌별 순번이 아니라 전역 식별자라 1 이라고 가정하지 않는다 (`replay.ts`).
  firstTickSeq: 1,
  gameDate: "2020-05-14",
  gameMonth: "2020-05",
  nextTickAt: "2026-09-21T03:28:51.000Z",
  phaseEndsAt: null,
  feeFree: false,
  feeFreeEndsAt: null,
  leaderboardBlackout: false,
  replayEpoch: 0,
  scheduledStartAt: null,
  warping: false,
};

const mark = (over: Partial<ReplayMark> & { key: string }): ReplayMark => ({
  kind: "BOUNDARY",
  label: over.key,
  seasonDay: 1,
  offsetSec: 0,
  tickSeq: null,
  passed: false,
  ...over,
});

/** 두 일차 · 네 갈래 · 지나간 지점과 앞의 지점을 섞어 둔다. */
const MARKS: ReplayMark[] = [
  mark({
    key: "day1.briefing",
    label: "1일차 브리핑",
    offsetSec: -300,
    passed: true,
  }),
  mark({
    key: "month.2020-01",
    kind: "MONTH",
    label: "2020-01 첫 틱",
    offsetSec: 100,
    tickSeq: 12,
    passed: true,
  }),
  mark({
    key: "day1.lastTick",
    kind: "DAY_LAST_TICK",
    label: "1일차 마지막 틱",
    offsetSec: 20900,
    tickSeq: 96,
    passed: true,
  }),
  mark({
    key: "day2.briefing",
    label: "2일차 브리핑",
    seasonDay: 2,
    offsetSec: -300,
    passed: true,
  }),
  mark({
    key: "month.2020-05",
    kind: "MONTH",
    label: "2020-05 첫 틱",
    seasonDay: 2,
    offsetSec: 120,
    tickSeq: 101,
    passed: true,
  }),
  mark({
    key: "day2.ipo.close",
    kind: "IPO",
    label: "카카오게임즈 청약 마감",
    seasonDay: 2,
    offsetSec: 7200,
    tickSeq: 148,
  }),
  mark({
    key: "day2.lastTick",
    kind: "DAY_LAST_TICK",
    label: "2일차 마지막 틱",
    seasonDay: 2,
    offsetSec: 20900,
    tickSeq: 192,
  }),
  mark({
    key: "day3.briefing",
    label: "3일차 브리핑",
    seasonDay: 3,
    offsetSec: -300,
  }),
];

const meta = {
  title: "Screens/Admin/ReplayMarks",
  component: ReplayMarks,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: {
    marks: MARKS,
    clock: CLOCK,
    position: 5000,
    pending: false,
    onSeek: () => {},
  },
} satisfies Meta<typeof ReplayMarks>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 기본. **현재 일차만 펼치고 월 첫 틱은 접는다** — 지점이 128개라 한 화면에 들어오는 것이
 * 목적이다. 뒤로 가는 이동에만 확인 대화상자가 붙는다.
 */
export const Default: Story = {};

/** 조작이 왕복 중. 모든 이동 버튼이 함께 잠긴다. */
export const Pending: Story = { args: { pending: true } };

/** 시즌 첫날. 지나온 지점이 거의 없다. */
export const FirstDay: Story = {
  args: { clock: { ...CLOCK, seasonDay: 1, tickSeq: 30 }, position: 1200 },
};

/** 틱이 아직 적재되지 않은 환경. 순번 기준이 없어 물러설 칸 수를 셀 수 없다. */
export const BeforeFirstTick: Story = {
  args: { clock: { ...CLOCK, tickSeq: null, firstTickSeq: null } },
};

export const Dark: Story = { globals: { theme: "dark" } };
