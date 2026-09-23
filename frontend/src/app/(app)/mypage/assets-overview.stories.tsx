import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  clockSnapshot,
  EXTREME_VALUATION,
  INSTRUMENTS,
  POSITIONS,
  VALUATION,
} from "@/components/market/fixtures";
import type { PositionItem, Valuation } from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { AssetsOverview } from "./assets-overview";

/**
 * 요약·보유 종목·자산 배분이 **같은 스토어**를 본다 — 따로 부르면 두 카드가 서로 다른 틱의
 * 비중을 말할 수 있다. 스토리도 한 곳에만 심는다.
 */
function Seed({
  valuation,
  positions,
}: {
  valuation: Valuation | null;
  positions: PositionItem[];
}) {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
    tickStore.setState({
      tickSeq: 137,
      valuation,
      positions: {
        asOfTickSeq: 137,
        asOfGameDate: "2020-08-14",
        usdKrwRate: "1187.20",
        positions,
      },
      usdKrwRate: "1187.20",
    });
  }, [valuation, positions]);
  return null;
}

const meta = {
  title: "Screens/MyPage/AssetsOverview",
  component: AssetsOverview,
  parameters: { layout: "fullscreen" },
  beforeEach: () => mockFetch({ "/api/v1/accounts/me/exchanges/preview": 503 }),
  decorators: [
    (Story) => (
      <div className="flex h-[46rem] bg-background p-3">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS },
} satisfies Meta<typeof AssetsOverview>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 요약 한 줄 + 보유 종목 표 + 자산 배분 + 환전. */
export const Default: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed valuation={VALUATION} positions={POSITIONS} />
        <Story />
      </>
    ),
  ],
};

/**
 * 계좌가 아직 오지 않았을 때. **자리 채움 숫자를 그리지 않는다** — 남의 시드와 남의 손익이
 * 잠깐 뜨면 그 값을 보고 주문 금액을 정할 수 있다.
 */
export const Loading: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed valuation={null} positions={[]} />
        <Story />
      </>
    ),
  ],
};

/** 아무것도 사지 않은 계좌. 표는 비고 배분은 현금 100% 다. */
export const NoPositions: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed valuation={VALUATION} positions={[]} />
        <Story />
      </>
    ),
  ],
};

export const Dark: Story = {
  ...Default,
  globals: { theme: "dark" },
};

/**
 * **자릿수가 터졌을 때.** 요약 한 줄은 `lg` 에서 4열 격자라 한 칸이 화면 폭의 1/4 이다 —
 * 총자산이 조 단위가 되면 `text-2xl` 숫자가 칸을 넘어 다음 줄로 접히거나 옆 칸을 민다.
 * 좁은 창에서 먼저 무너지니 미리보기 프레임을 줄여 가며 본다.
 */
export const Extreme: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed valuation={EXTREME_VALUATION} positions={POSITIONS} />
        <Story />
      </>
    ),
  ],
};

export const ExtremeDark: Story = {
  ...Extreme,
  name: "Extreme (Dark)",
  globals: { theme: "dark" },
};
