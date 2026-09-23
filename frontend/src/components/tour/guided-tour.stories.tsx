import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";

import { GuidedTour, type TourStep } from "./guided-tour";

const STEPS: TourStep[] = [
  {
    target: "demo-header",
    title: "지금은 과거의 어느 날",
    body: "날짜와 내 자산을 확인할 수 있습니다.",
    placement: "bottom",
  },
  {
    target: "demo-list",
    title: "종목 확인",
    body: "미국·국내·코인이 한자리에 있습니다.",
    placement: "right",
  },
  {
    target: "demo-detail",
    title: "차트와 종목 정보",
    body: "종목의 등락과 정보를 확인할 수 있습니다.",
    placement: "left",
  },
  {
    target: "demo-order",
    title: "매수, 매도",
    body: "종목을 거래할 수 있습니다.",
    placement: "top",
  },
];

/** 가리킬 자리는 `data-tour` 로 표시한다 — 실제 화면과 같은 방식의 가짜 레이아웃이다. */
function DemoScreen() {
  return (
    <div className="flex h-[32rem] flex-col gap-3 bg-background p-3">
      <div
        data-tour="demo-header"
        className="flex h-16 shrink-0 items-center rounded-xl border border-border bg-card px-5 text-sm"
      >
        헤더
      </div>
      <div className="flex min-h-0 flex-1 gap-3">
        <div
          data-tour="demo-list"
          className="w-56 rounded-xl border border-border bg-card p-3 text-sm"
        >
          종목 목록
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <div
            data-tour="demo-detail"
            className="flex-1 rounded-xl border border-border bg-card p-3 text-sm"
          >
            차트
          </div>
          <div
            data-tour="demo-order"
            className="h-32 rounded-xl border border-border bg-card p-3 text-sm"
          >
            주문
          </div>
        </div>
      </div>
    </div>
  );
}

// 포털로 <body> 에 붙고 화면을 덮으므로 autodocs 는 걸지 않는다.
const meta = {
  title: "Layout/GuidedTour",
  component: GuidedTour,
  parameters: { layout: "fullscreen" },
  args: { steps: STEPS, open: true, onClose: () => {}, label: "둘러보기" },
} satisfies Meta<typeof GuidedTour>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 구멍이 대상을 비추고 말풍선이 그 옆에 붙는다. `다음` 으로 단계를 넘겨 본다. */
export const Default: Story = {
  render: (args) => (
    <>
      <DemoScreen />
      <GuidedTour {...args} />
    </>
  ),
};

/**
 * 대상이 화면에 없을 때. 말풍선만 가운데에 뜬다 — 가리킬 것이 없다고 튜토리얼이
 * 멈추지는 않는다.
 */
export const MissingTarget: Story = {
  args: {
    steps: [
      {
        target: "없는-자리",
        title: "여기에 무언가 있었습니다",
        body: "대상을 못 찾으면 가운데에 그냥 띄운다.",
      },
    ],
  },
  render: (args) => (
    <>
      <DemoScreen />
      <GuidedTour {...args} />
    </>
  ),
};

/** 닫고 다시 열면 1단계부터다 — 보던 단계에서 이어지면 어리둥절하다. */
export const Reopen: Story = {
  render: (args) => {
    const [open, setOpen] = useState(true);
    return (
      <>
        <DemoScreen />
        <div className="px-3">
          <Button size="sm" onClick={() => setOpen(true)}>
            다시 열기
          </Button>
        </div>
        <GuidedTour {...args} open={open} onClose={() => setOpen(false)} />
      </>
    );
  },
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
