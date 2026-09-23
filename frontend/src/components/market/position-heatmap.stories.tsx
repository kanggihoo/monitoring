import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { PositionHeatmap } from "./position-heatmap";
import { PositionHeatmapV2 } from "./position-heatmap.v2";

const meta = {
  title: "Screens/Dashboard/PositionHeatmap",
  component: PositionHeatmap,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: {
    // 실제 자리는 대시보드 오른쪽 기둥이다. 그 폭을 스토리에서도 지킨다.
    className: "max-w-md",
    heatmap: {
      asOfTickSeq: 137,
      asOfGameDate: "2022-04-15",
      totalValuationKrw: "412000000000",
      buckets: [
        {
          category: "NASDAQ",
          valuationKrw: "148320000000",
          weight: 36,
          weightDelta: -1.82,
        },
        {
          category: "KRX",
          valuationKrw: "102000000000",
          weight: 24.76,
          weightDelta: 0.41,
        },
        {
          category: "CRYPTO",
          valuationKrw: "41200000000",
          weight: 10,
          weightDelta: -3.1,
        },
        {
          category: "CASH",
          valuationKrw: "120480000000",
          weight: 29.24,
          weightDelta: 4.51,
        },
      ],
    },
  },
} satisfies Meta<typeof PositionHeatmap>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 현금으로 몰리는 순간 — 공포 지표다. */
export const FlightToCash: Story = {};

export const Waiting: Story = { args: { heatmap: null } };

export const Dark: Story = { globals: { theme: "dark" } };

// --- v2 시안 --------------------------------------------------------------
//
// 도넛 + 범례. 갈래 색은 `foreground` 알파 램프(무채색)다 — 등락 밖에서 빨강·파랑을
// 쓰지 않는다는 DESIGN.md 금지표를 지킨다. ±pp 만 등락색을 쓴다.

/** 실제 자리(오른쪽 기둥) 폭. 좁아서 도넛과 범례가 세로로 접힌다. */
export const V2: Story = {
  name: "V2",
  render: (args) => <PositionHeatmapV2 {...args} />,
};

/** 시안이 가정한 폭. 컨테이너가 넓어지면 도넛과 범례가 나란히 선다. */
export const V2Wide: Story = {
  name: "V2 (넓은 폭)",
  args: { className: "max-w-3xl" },
  render: (args) => <PositionHeatmapV2 {...args} />,
};

export const V2Waiting: Story = {
  name: "V2 (집계 전)",
  args: { heatmap: null },
  render: (args) => <PositionHeatmapV2 {...args} />,
};

/** 채택 판단용. 같은 집계로 위아래에 둔다. 결정 나면 진 쪽과 함께 지운다. */
export const Versions: Story = {
  render: (args) => (
    <div className="flex flex-col gap-6">
      <div>
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          v1 (현재)
        </p>
        <PositionHeatmap {...args} />
      </div>
      <div>
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          v2 (시안)
        </p>
        <PositionHeatmapV2 {...args} />
      </div>
    </div>
  ),
};

export const V2Dark: Story = {
  ...V2Wide,
  name: "V2 Dark",
  globals: { theme: "dark" },
};
