import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS, PRICES } from "./fixtures";
import { MoversBoard } from "./movers-board";
import { MoversBoardV2 } from "./movers-board.v2";

const meta = {
  title: "Screens/Dashboard/MoversBoard",
  component: MoversBoard,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="max-w-3xl bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { instruments: INSTRUMENTS, prices: PRICES },
} satisfies Meta<typeof MoversBoard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 휴장 종목은 정렬할 값이 없어 빠진다 (`mkt.md`). 막대는 그 판의 최대 등락률 대비다. */
export const Default: Story = {};

export const Waiting: Story = { args: { prices: {} } };

export const Dark: Story = { globals: { theme: "dark" } };

// --- v2 시안 --------------------------------------------------------------
//
// 계산은 v1 과 같으므로 `meta.args` 를 그대로 물려받는다. 컨트롤에서 시세를 바꾸면
// 두 시안이 같이 움직여야 형태 차이만 남는다.

/**
 * 시안용 종가 흐름. 실제 데이터가 아니라 종목 번호로 씨를 삼은 결정적 난수라, 스토리를
 * 다시 열어도 같은 그림이 나온다 — 흔들리면 두 시안을 견줄 수 없다.
 */
function demoCloses(instrumentId: number, close: string, changeRate: number) {
  const end = Number(close);
  const start = end / (1 + changeRate / 100);
  let seed = instrumentId * 7919;
  const rand = () => {
    seed = (seed * 9301 + 49297) % 233280;
    return seed / 233280;
  };
  const swing = Math.abs(end - start) || end * 0.02;

  return Array.from({ length: 12 }, (_, index) => {
    if (index === 11) return String(end);
    const drift = start + ((end - start) * index) / 11;
    return String(drift + (rand() - 0.5) * swing * 0.9);
  });
}

const CLOSES: Record<number, string[]> = {};
for (const price of Object.values(PRICES)) {
  // 휴장 종목은 v2 도 거른다. 그릴 등락이 없다.
  if (price.changeRate === null) continue;
  CLOSES[price.instrumentId] = demoCloses(
    price.instrumentId,
    price.close,
    price.changeRate
  );
}

/** 줄을 누르면 `/?instrumentId=...` 로 간다. hover 는 그 사실을 알리고 미리 받아 둔다. */
export const V2: Story = {
  name: "V2",
  render: (args) => <MoversBoardV2 {...args} series={CLOSES} />,
};

/**
 * 종가 시리즈가 없을 때. 흐름 칸만 비고 나머지는 그대로다 — **지금 대시보드의 상태가
 * 이것이다.** 채택하려면 종목별 종가를 어디선가 받아 와야 한다.
 */
export const V2WithoutSeries: Story = {
  name: "V2 (시리즈 없음)",
  render: (args) => <MoversBoardV2 {...args} />,
};

/** 채택 판단용. 같은 종목·같은 시세로 위아래에 둔다. 결정 나면 진 쪽과 함께 지운다. */
export const Versions: Story = {
  render: (args) => (
    <div className="flex flex-col gap-6">
      <div>
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          v1 (현재)
        </p>
        <MoversBoard {...args} />
      </div>
      <div>
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          v2 (시안)
        </p>
        <MoversBoardV2 {...args} series={CLOSES} />
      </div>
    </div>
  ),
};

export const V2Dark: Story = {
  ...V2,
  name: "V2 Dark",
  globals: { theme: "dark" },
};
