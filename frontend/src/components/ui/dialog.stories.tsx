import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogTitle,
} from "@/components/ui/dialog";

// 포털로 <body> 에 붙는 컴포넌트라 autodocs 를 걸지 않는다. 문서 페이지는 한 iframe 에
// 모든 스토리를 함께 그리는데, 열린 다이얼로그가 여럿이면 서로를 덮어 아무것도 못 본다.
const meta = {
  title: "UI/Dialog",
  component: Dialog,
  parameters: { layout: "centered" },
  args: { defaultOpen: true },
} satisfies Meta<typeof Dialog>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 답을 요구하지 않는 제안. 덮개를 눌러도 닫힌다 — 그게 `AlertDialog` 와 갈리는 지점이다. */
export const Default: Story = {
  render: (args) => (
    <Dialog {...args}>
      <DialogContent>
        <DialogTitle>계정을 만들면 기록이 남습니다</DialogTitle>
        <p className="text-sm text-muted-foreground">
          지금은 둘러보기 상태라 주문과 순위가 저장되지 않습니다.
        </p>
        <div className="flex justify-end gap-2">
          <DialogClose render={<Button variant="ghost" size="sm" />}>
            나중에
          </DialogClose>
          <DialogClose render={<Button size="sm" />}>로그인</DialogClose>
        </div>
      </DialogContent>
    </Dialog>
  ),
};

/** 긴 본문. 폭은 `max-w-sm` 에서 멈추고 줄이 늘어난다. */
export const LongBody: Story = {
  render: (args) => (
    <Dialog {...args}>
      <DialogContent>
        <DialogTitle>휴장일 안내</DialogTitle>
        <p className="text-sm text-muted-foreground">
          재생 구간에 증시가 열리지 않은 날이 끼어 있습니다. 그 날의 시세는
          움직이지 않고, 예약 주문은 다음 개장일로 넘어갑니다. 수수료 무료
          구간도 개장일 기준으로만 계산합니다.
        </p>
        <div className="flex justify-end">
          <DialogClose render={<Button size="sm" />}>알겠습니다</DialogClose>
        </div>
      </DialogContent>
    </Dialog>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
