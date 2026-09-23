import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS, POSITIONS } from "@/components/market/fixtures";

import { HoldingsTable } from "./holdings-table";

const meta = {
  title: "Screens/MyPage/HoldingsTable",
  component: HoldingsTable,
  tags: ["autodocs"],
  args: {
    positions: POSITIONS,
    instruments: INSTRUMENTS,
  },
} satisfies Meta<typeof HoldingsTable>;

export default meta;

type Story = StoryObj<typeof meta>;

// 표는 `flex-1` 로 부모 높이를 채운다. 높이를 주지 않으면 카드 안 스크롤 영역이
// 생기지 않아 넘치는 경우를 볼 수 없다.
const wrap: Story["decorators"] = [
  (Story) => (
    <div className="flex h-[26rem] bg-background p-6">
      <Story />
    </div>
  ),
];

/** 원화·달러, 평가익·평가손, 잠긴 수량이 한 화면에 같이 있는 기본 상태. */
export const Default: Story = { decorators: wrap };

export const Empty: Story = {
  decorators: wrap,
  args: { positions: [] },
};

/** 한 종목이 넘치면 카드 안에서만 스크롤한다 — 페이지가 밀리지 않는다. */
export const Overflowing: Story = {
  decorators: wrap,
  args: {
    positions: [
      ...POSITIONS,
      ...POSITIONS.map((position) => ({
        ...position,
        instrumentId: position.instrumentId + 100,
      })),
    ],
  },
};

/**
 * 유니버스에 없는 종목. 메타를 못 찾으면 이름 자리에 `#id` 를 두고 자릿수는 2 로 둔다
 * (`holdings-table.tsx`). 값은 손대지 않는다.
 */
export const UnknownInstrument: Story = {
  decorators: wrap,
  args: { instruments: [] },
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
