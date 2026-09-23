import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";
import type { Briefing } from "@/lib/market-types";

import { BriefingDialog } from "./briefing-dialog";
import { BriefingDialogV2 } from "./briefing-dialog.v2";

/** 1일차. 시각은 하루 시간표(11:05 재생 ~ 17:00 정산)를 그대로 옮긴 것이다. */
const BRIEFING: Briefing = {
  seasonDay: 1,
  replayStartGameMonth: "2020-01",
  schedule: {
    tradingStartsAt: "2026-09-22T02:05:00Z",
    feeFreeStartsAt: "2026-09-22T03:00:00Z",
    feeFreeEndsAt: "2026-09-22T03:10:00Z",
    blackoutStartsAt: "2026-09-22T07:00:00Z",
    closesAt: "2026-09-22T07:40:00Z",
    settlementAt: "2026-09-22T08:00:00Z",
  },
  ipoWindows: [
    {
      disclosureAt: "2026-09-22T03:17:00Z",
      subscriptionClosesAt: "2026-09-22T03:29:00Z",
    },
    {
      disclosureAt: "2026-09-22T04:41:00Z",
      subscriptionClosesAt: "2026-09-22T04:53:00Z",
    },
  ],
};

const meta = {
  title: "Layout/BriefingDialog",
  component: BriefingDialog,
  parameters: { layout: "centered" },
  args: {
    briefing: BRIEFING,
    open: true,
    onOpenChange: () => {},
    onAcknowledge: () => {},
  },
} satisfies Meta<typeof BriefingDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 당일 청약이 없는 날. 줄 하나가 통째로 빠진다 — 빈 값을 적지 않는다. */
export const NoIpo: Story = {
  args: { briefing: { ...BRIEFING, ipoWindows: [] } },
};

/** 2일차. 재생 시작 시점만 바뀌고 시간표는 매일 같다. */
export const DayTwo: Story = {
  args: {
    briefing: { ...BRIEFING, seasonDay: 2, replayStartGameMonth: "2022-04" },
  },
};

export const Dark: Story = { globals: { theme: "dark" } };

// --- v2 시안 --------------------------------------------------------------
//
// 담는 내용은 v1 과 같다 — 문구·계산이 같은 상수에서 오므로 (`briefing-dialog.tsx` 가
// `PACE`·`MARKETS`·`at`·`minutesBetween` 을 내보낸다) 여기 남는 차이는 배치와 아이콘뿐이다.
// `meta.args` 를 그대로 물려받아 같은 브리핑을 두 시안에 먹인다.

/**
 * 일정을 세로 타임라인으로 편 시안. v1 의 `오늘 일정` 한 줄에 들어 있던 시각 다섯 개가
 * 각자의 줄을 갖고, 시각이 왼쪽에 세로로 맞는다. 아이콘은 줄을 가르는 표식이고 뜻은 옆의
 * 글자가 진다 — 색은 쓰지 않으며 무료 타임만 `accent` 무채색 면으로 띄운다.
 */
export const V2: Story = {
  name: "V2",
  render: (args) => <BriefingDialogV2 {...args} />,
};

/** 청약이 없는 날. v1 과 같이 구획 하나가 통째로 빠진다. */
export const V2NoIpo: Story = {
  ...V2,
  name: "V2 (청약 없음)",
  args: { briefing: { ...BRIEFING, ipoWindows: [] } },
};

/**
 * 채택 판단용. 모달이라 둘을 나란히 띄울 수 없어 **한 스토리 안에서 번갈아 연다** —
 * 위 버튼으로 바꿔 가며 같은 브리핑의 두 배치를 견준다. 결정 나면 진 쪽과 함께 지운다.
 */
export const Versions: Story = {
  parameters: { layout: "fullscreen" },
  render: function Versions(args) {
    const [shown, setShown] = useState<"v1" | "v2">("v1");
    return (
      <div className="flex min-h-96 flex-col items-start gap-2 bg-background p-6">
        <div className="flex gap-2">
          {(["v1", "v2"] as const).map((version) => (
            <Button
              key={version}
              size="sm"
              variant={shown === version ? "default" : "outline"}
              onClick={() => setShown(version)}
            >
              {version === "v1" ? "v1 (현재)" : "v2 (시안)"}
            </Button>
          ))}
        </div>
        {shown === "v1" ? (
          <BriefingDialog {...args} />
        ) : (
          <BriefingDialogV2 {...args} />
        )}
      </div>
    );
  },
};

export const V2Dark: Story = {
  ...V2,
  name: "V2 Dark",
  globals: { theme: "dark" },
};
