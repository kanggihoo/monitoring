import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS, POSITIONS, PRICES } from "./fixtures";
import { InstrumentList } from "./instrument-list";
import { InstrumentListV2 } from "./instrument-list.v2";
import { OpeningNoticeV2 } from "./opening-notice.v2";

const meta = {
  title: "Screens/Market/InstrumentList",
  component: InstrumentList,
  tags: ["autodocs"],
  decorators: [
    (Story, { parameters }) => (
      <div
        className={`h-[36rem] rounded-lg bg-card p-3 ${parameters.frameWidth ?? "w-72"}`}
      >
        <Story />
      </div>
    ),
  ],
  args: {
    instruments: INSTRUMENTS,
    prices: PRICES,
    selectedId: 6,
    onSelect: () => {},
    isLoggedIn: true,
    className: "h-full",
  },
} satisfies Meta<typeof InstrumentList>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 별은 관심, 선택 행은 무채색 `accent`. 휴장 종목(삼성전자)은 등락 대신 배지가 뜬다. */
export const Default: Story = {
  render: (args) => {
    const [selected, setSelected] = useState(args.selectedId);
    return (
      <InstrumentList {...args} selectedId={selected} onSelect={setSelected} />
    );
  },
};

/** 시세가 아직 없으면 값 자리만 비운다 — 레이아웃이 튀지 않는다. */
export const BeforeFirstTick: Story = { args: { prices: {} } };

/**
 * 미인증. **별과 관심 필터가 사라지지 않고 누르면 로그인 안내가 뜬다** — 아무 일도 일어나지
 * 않으면 고장으로 오해한다 (ADR-0005). 담긴 것이 없어 관심 개수도 비어 있다.
 */
export const Unauthenticated: Story = { args: { isLoggedIn: false } };

export const Dark: Story = { ...Default, globals: { theme: "dark" } };

// --- v2 시안 --------------------------------------------------------------
//
// 목록·필터·별은 v1 과 같으므로 `meta.args` 를 그대로 물려받는다. 달라지는 것은 보유
// 종목에 붙는 한 줄뿐이라, 같은 시세로 세워야 그 차이만 남는다.

/**
 * `보유 4` 칩이 하나 더 선다. **누르기 전에는 v1 과 똑같다** — 보유 정보는 그 필터
 * 안에서만 같은 두 칸을 넘겨받는다 (평단·평가금·수익률, 줄 높이는 그대로).
 */
export const V2: Story = {
  name: "V2",
  render: (args) => {
    const [selected, setSelected] = useState(args.selectedId);
    return (
      <InstrumentListV2
        {...args}
        positions={POSITIONS}
        selectedId={selected}
        onSelect={setSelected}
      />
    );
  },
};

/** 보유가 없으면 `보유` 칩이 없다 — v1 과 완전히 같은 목록이 된다. */
export const V2WithoutPositions: Story = {
  name: "V2 (보유 없음)",
  render: (args) => <InstrumentListV2 {...args} />,
};

/** 채택 판단용. 같은 종목·같은 시세로 나란히 둔다. 결정 나면 진 쪽과 함께 지운다. */
export const Versions: Story = {
  render: (args) => (
    <div className="flex h-full gap-6">
      <div className="flex min-h-0 flex-1 flex-col">
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          v1 (현재)
        </p>
        <InstrumentList {...args} className="min-h-0 flex-1" />
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          v2 (시안)
        </p>
        <InstrumentListV2
          {...args}
          positions={POSITIONS}
          className="min-h-0 flex-1"
        />
      </div>
    </div>
  ),
  parameters: { frameWidth: "w-[38rem]" },
};

export const V2Dark: Story = {
  ...V2,
  name: "V2 Dark",
  globals: { theme: "dark" },
};

/**
 * 개장 직후 (11:05~첫 종가). **목록을 덮지 않고 값 자리를 설명하는 한 줄만 위에 얹는다** —
 * 이름·갈래·관심 별은 첫 종가와 무관하게 이미 제 값이라, 가리면 볼 수 있던 것을 못 보게
 * 된다. `BeforeFirstTick` 과 나란히 보면 그 한 줄이 무엇을 바꾸는지가 보인다.
 *
 * 실제로는 이 줄이 `useOpeningWait()` 로 스스로 뜨고 사라진다 (`opening-notice.v2.tsx`) —
 * 여기서는 값을 손으로 넘겨 대기 구간을 고정해 둔다.
 *
 * **`보유` 필터 시안과는 별개다.** 같은 파일에 있을 뿐 서로를 필요로 하지 않는다.
 */
export const V2Opening: Story = {
  name: "V2 (개장 직후)",
  args: { prices: {} },
  render: (args) => (
    <InstrumentListV2
      {...args}
      opening={
        <OpeningNoticeV2
          slot="instruments"
          variant="strip"
          phase="TRADING"
          seconds={31}
          countdownLabel="첫 종가까지"
        />
      }
    />
  ),
};

/** 아직 재생 전 (11:00~11:05). 같은 줄이 `개장까지` 를 센다. */
export const V2OpeningBriefing: Story = {
  ...V2Opening,
  name: "V2 (브리핑 구간)",
  render: (args) => (
    <InstrumentListV2
      {...args}
      opening={
        <OpeningNoticeV2
          slot="instruments"
          variant="strip"
          phase="BRIEFING"
          seconds={218}
          countdownLabel="개장까지"
        />
      }
    />
  ),
};

export const V2OpeningDark: Story = {
  ...V2Opening,
  name: "V2 (개장 직후) Dark",
  globals: { theme: "dark" },
};
