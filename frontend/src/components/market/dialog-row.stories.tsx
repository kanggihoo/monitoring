import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { DialogRow } from "./dialog-row";

/**
 * 개장 브리핑과 부재 요약이 같이 쓰는 한 줄. `dl` 안에서만 쓴다 — 라벨 폭과 구분선을
 * 한 곳에서 정하려고 떼어 낸 것이라, 실제 모양은 `Layout/BriefingDialog` 에서도 볼 수 있다.
 */
const meta = {
  title: "Common/DialogRow",
  component: DialogRow,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <dl className="w-96 max-w-full bg-background p-6 text-sm">
        <Story />
      </dl>
    ),
  ],
  args: { label: "거래 시간", children: "11:05 ~ 17:00" },
} satisfies Meta<typeof DialogRow>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 여러 줄. 라벨 폭은 `sm` 이상에서 144px 로 고정되고 값만 늘어난다. */
export const List: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <>
      <DialogRow label="거래 시간">11:05 ~ 17:00</DialogRow>
      <DialogRow label="수수료 무료">12:00 ~ 12:10</DialogRow>
      <DialogRow label="청약">
        <ul className="flex flex-col gap-0.5">
          <li>12:17 공시 · 12:29 마감</li>
          <li>13:41 공시 · 13:53 마감</li>
        </ul>
      </DialogRow>
      <DialogRow label="정산">17:00</DialogRow>
    </>
  ),
};

/** 값이 길면 접힌다 — 라벨은 줄바꿈하지 않는다(`shrink-0`). */
export const LongValue: Story = {
  args: {
    label: "안내",
    children:
      "재생 구간에 증시가 열리지 않은 날이 끼어 있습니다. 그 날의 시세는 움직이지 않고 예약 주문은 다음 개장일로 넘어갑니다.",
  },
};

export const Dark: Story = { ...List, globals: { theme: "dark" } };
