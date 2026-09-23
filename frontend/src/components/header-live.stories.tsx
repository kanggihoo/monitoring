import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  clockSnapshot,
  EXTREME_RANKING_ME,
  EXTREME_VALUATION,
  RANKING_ME,
  VALUATION,
} from "@/components/market/fixtures";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

import { HeaderLive } from "./header-live";

/**
 * 값은 전부 스토어에서 온다. 스토리가 시계·평가액·순위를 심고, 연결 여부로 끊긴 구간을
 * 만들어 본다 (`header.stories.tsx` 와 같은 방식).
 */
function Seed({
  connected,
  hydrated,
}: {
  connected: boolean;
  hydrated: boolean;
}) {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(connected);
    tickStore.setState({
      tickSeq: 137,
      baseTickSeq: hydrated ? 137 : null,
      valuation: VALUATION,
      myRank: RANKING_ME,
      usdKrwRate: "1187.20",
    });
  }, [connected, hydrated]);
  return null;
}

const meta = {
  title: "Layout/HeaderLive",
  component: HeaderLive,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="flex h-16 items-center rounded-xl border border-border bg-card px-5">
        <Story />
      </div>
    ),
  ],
  args: { isLoggedIn: true },
} satisfies Meta<typeof HeaderLive>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 붙었고 기준도 섰다 — 연결 표시는 **그리지 않는다**. */
export const Connected: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed connected hydrated />
        <Story />
      </>
    ),
  ],
};

/** 붙었지만 이 연결의 시세 기준이 아직 안 섰다. 기다리면 되는 구간이라 문구가 다르다. */
export const Syncing: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed connected hydrated={false} />
        <Story />
      </>
    ),
  ],
};

/** 끊겼다. 화면의 값이 전부 옛것인데 카운트다운만 계속 돈다 — 그때만 말한다. */
export const Disconnected: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed connected={false} hydrated={false} />
        <Story />
      </>
    ),
  ],
};

/** 미인증. 개인 지표가 빈 값이 아니라 안내 한 줄로 바뀐다 (ADR-0005). */
export const Unauthenticated: Story = {
  args: { isLoggedIn: false },
  decorators: [
    (Story) => (
      <>
        <Seed connected hydrated />
        <Story />
      </>
    ),
  ],
};

/** 암전 구간에는 순위 자리에 `암전` 이 들어간다. */
export const LeaderboardBlackout: Story = {
  decorators: [
    (Story) => {
      useEffect(() => {
        sessionStore.getState().applyClock(clockSnapshot());
        sessionStore.getState().setConnected(true);
        tickStore.setState({
          tickSeq: 137,
          baseTickSeq: 137,
          valuation: VALUATION,
          myRank: { ...RANKING_ME, seasonRank: null },
          usdKrwRate: "1187.20",
        });
      }, []);
      return <Story />;
    },
  ],
};

export const Dark: Story = {
  ...Connected,
  globals: { theme: "dark" },
};

/**
 * **자릿수가 터졌을 때.** 헤더는 한 줄에 지표를 늘어놓고 `overflow-x-auto` 로 받는다 —
 * 총자산이 조 단위가 되고 누적이 `+482만%` 가 되면 줄이 길어져 좁은 창에서 가로 스크롤이
 * 생기고, 오른쪽 조작부(알림·테마·사용자)에 밀려 지표가 먼저 잘린다.
 *
 * 값의 출처는 `EXTREME_DAYS` 다 — 1일차에 1억으로 +48,250% 를 찍은 관측값에서 이어 잡았다.
 */
export const Extreme: Story = {
  decorators: [
    (Story) => {
      useEffect(() => {
        sessionStore.getState().applyClock(clockSnapshot());
        sessionStore.getState().setConnected(true);
        tickStore.setState({
          tickSeq: 137,
          baseTickSeq: 137,
          valuation: EXTREME_VALUATION,
          myRank: EXTREME_RANKING_ME,
          usdKrwRate: "1187.20",
        });
      }, []);
      return <Story />;
    },
  ],
};

export const ExtremeDark: Story = {
  ...Extreme,
  name: "Extreme (Dark)",
  globals: { theme: "dark" },
};
