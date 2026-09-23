import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { cn } from "@/lib/utils";

import { EXTREME_DAYS } from "./fixtures";
import { PriceChange } from "./price-change";

const meta = {
  title: "Common/PriceChange",
  component: PriceChange,
  tags: ["autodocs"],
  argTypes: {
    variant: { control: "inline-radio", options: ["text", "badge"] },
  },
  args: { value: 3.82 },
} satisfies Meta<typeof PriceChange>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Up: Story = {};

export const Down: Story = { args: { value: -12.4 } };

export const Flat: Story = { args: { value: 0 } };

export const Badge: Story = { args: { variant: "badge" } };

/** 등락폭(원)도 같은 컴포넌트로 낸다. */
export const Amount: Story = {
  args: { value: -125_000, unit: "원", digits: 0 },
};

/** 상승 빨강 / 하락 파랑은 국내 시장 관행이다. 색 없이 부호·기호만으로도 읽힌다. */
export const AllStates: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-col gap-4 bg-card p-6">
      {(["text", "badge"] as const).map((variant) => (
        <div key={variant} className="flex items-center gap-4">
          {[9.31, 0.42, 0, -0.77, -18.6].map((v) => (
            <PriceChange key={v} value={v} variant={variant} />
          ))}
        </div>
      ))}
    </div>
  ),
};

export const Dark: Story = { ...AllStates, globals: { theme: "dark" } };

/**
 * **자릿수가 터질 때.** `whitespace-nowrap` 이라 접히지 않고 칸 밖으로 넘친다 — 넘치는
 * 순간을 눈으로 보려고 랭킹 화면의 실제 열 폭을 그대로 두르고 그린다
 * (`ranking-screen.tsx` 의 `COLUMNS`: 수익률 칸이 폰 `4.5rem`=72px · `md` `5rem`=80px).
 *
 * **수익률이 마지막 칸이라 넘친 만큼은 카드 밖으로 나간다.** 왼쪽으로 밀 칸이 없어서
 * `justify-self-end` 가 오른쪽 끝에 붙여 두기 때문이다 — `평가액` 탭에서는 그 왼쪽 칸과도
 * 부딪힌다. 시즌이 3일이고 매일 같은 판이 다시 열리므로 이 자릿수는 시간이 가면 늘기만 한다.
 */
export const Overflowing: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-col gap-3 bg-card p-6 text-sm">
      {[
        ["평범한 하루", 3.82],
        ["잘 본 하루", 128.4],
        ["1일차 관측", EXTREME_DAYS[0].returnRate],
        ["2일차 추정", EXTREME_DAYS[1].returnRate],
        ["3일차 추정", EXTREME_DAYS[2].returnRate],
      ].map(([label, value]) => (
        <div key={label as string} className="flex items-center gap-4">
          <span className="w-24 shrink-0 text-xs text-muted-foreground">
            {label}
          </span>
          {/* 폰 72px · md 80px 칸. 실제로는 `justify-self-end` 라 오른쪽에 붙는다. */}
          {[
            ["폰 4.5rem", "w-18"],
            ["md 5rem", "w-20"],
          ].map(([note, width]) => (
            <span key={note} className="flex flex-col gap-0.5">
              <span className="text-2xs text-muted-foreground">{note}</span>
              <span
                className={cn(
                  width,
                  "flex justify-end border border-dashed border-border-strong"
                )}
              >
                <PriceChange value={value as number} />
              </span>
            </span>
          ))}
        </div>
      ))}
    </div>
  ),
};

export const OverflowingDark: Story = {
  ...Overflowing,
  name: "Overflowing (Dark)",
  globals: { theme: "dark" },
};
