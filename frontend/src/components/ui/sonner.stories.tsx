import type { Meta, StoryObj } from "@storybook/nextjs-vite";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Toaster } from "@/components/ui/sonner";

// 토스트는 `<Toaster />` 가 있어야 뜬다. 앱에서는 루트 레이아웃에 한 번 놓는다.
// 포털로 붙으므로 autodocs 는 걸지 않는다 (`dialog.stories.tsx` 와 같은 이유).
const meta = {
  title: "UI/Toaster",
  component: Toaster,
  parameters: { layout: "fullscreen" },
} satisfies Meta<typeof Toaster>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 실제로 앱이 띄우는 문구들. **설명 줄이 붙은 것과 아닌 것을 섞어 둔다** — 설명 줄은
 * sonner 가 제 색을 쥐고 있어서 잉크 면 위에서 대비가 무너지던 자리다 (`sonner.tsx`).
 */
const KINDS = [
  [
    "success",
    () =>
      toast.success("10주를 54,200원에 샀습니다", {
        description: "수수료 0원 · 잔고 37,458,000원",
      }),
  ],
  [
    "info",
    () =>
      toast.info("캐니언모터스 청약 마감이 임박했습니다", {
        description: "이번 거래일이 지나면 청약할 수 없습니다.",
      }),
  ],
  [
    "warning",
    () =>
      toast.warning("장 마감 5분 전입니다", {
        description: "미체결 예약은 폐장에 자동 취소됩니다.",
      }),
  ],
  [
    "error",
    () =>
      toast.error("잔고가 모자라 주문을 내지 못했습니다", {
        description: "원화 1,240,000원이 부족합니다.",
      }),
  ],
  ["loading", () => toast.loading("주문을 넣는 중입니다")],
  ["plain", () => toast("예약 주문을 취소했습니다")],
] as const;

function Buttons({ children }: { children?: React.ReactNode }) {
  return (
    <div className="flex min-h-72 flex-wrap items-start gap-2 bg-background p-6">
      {KINDS.map(([label, fire]) => (
        <Button key={label} variant="outline" size="sm" onClick={fire}>
          {label}
        </Button>
      ))}
      <Button variant="ghost" size="sm" onClick={() => toast.dismiss()}>
        전부 닫기
      </Button>
      {children}
    </div>
  );
}

/**
 * 버튼을 눌러 종류별로 띄워 본다. **4초 뒤 저절로 사라지고**, 모서리의 닫기 버튼으로
 * 바로 치우거나 옆·아래로 밀어 닫을 수도 있다 (수명 4000ms · 스와이프 45px 는 sonner 기본값).
 *
 * `success` 는 아이콘이 없다 — 문장이 이미 무슨 일이 일어났는지 말하고 있어 표시가 더하는
 * 것이 없다 (`sonner.tsx`).
 */
export const Playground: Story = {
  render: (args) => (
    <Buttons>
      <Toaster {...args} />
    </Buttons>
  ),
};

/**
 * 다크에서도 같은 잉크 면이다 — **반전이 아니다** (DESIGN.md). 제목은 면 위 전경색,
 * 설명은 그 70% 다. 라이트와 번갈아 보며 두 줄의 대비가 같은지 본다.
 */
export const Dark: Story = { ...Playground, globals: { theme: "dark" } };

/**
 * 4초가 지나도 남는다. 사라지는 것을 쫓아가며 볼 수 없으니 **디자인을 뜯어볼 때 이 스토리로
 * 연다** — 앱의 설정이 아니라 검토용이다.
 */
export const Persistent: Story = {
  args: { duration: Infinity },
  render: (args) => (
    <Buttons>
      <Toaster {...args} />
    </Buttons>
  ),
};

export const PersistentDark: Story = {
  ...Persistent,
  name: "Persistent (Dark)",
  globals: { theme: "dark" },
};

function Burst({ children }: { children?: React.ReactNode }) {
  return (
    <div className="flex min-h-96 flex-col items-start gap-2 bg-background p-6">
      <Button
        size="sm"
        onClick={() => {
          for (const [, fire] of KINDS) fire();
        }}
      >
        한꺼번에 띄우기
      </Button>
      <Button variant="ghost" size="sm" onClick={() => toast.dismiss()}>
        전부 닫기
      </Button>
      {children}
    </div>
  );
}

/**
 * **앱이 쓰는 쪽.** 앞의 하나만 온전히 보이고 나머지는 뒤에 겹쳐 그 존재만 알린다.
 * 올려 두면 펼쳐지고 그동안 4초 시계도 멈춘다.
 *
 * 한 Tick 에 몰려도 두셋이다 — 체결·알림·일정이 각각 **가장 최근 하나만** 토스트를 띄우기
 * 때문이다 (`market-live-provider.tsx` 의 `fills[0]` · `notifications[0]` · `events[0]`).
 */
export const Stacked: Story = {
  args: { duration: Infinity },
  render: (args) => (
    <Burst>
      <Toaster {...args} />
    </Burst>
  ),
};

/**
 * 견주기용. 셋을 한꺼번에 펼치면 오른쪽 아래가 세 배로 덮인다 — 토스트가 몰리는 순간이
 * 곧 **Tick 경계**라, 시세와 주문 패널을 가장 가리면 안 되는 때가 겹친다. 폰에서는 하단
 * 탭바까지 덮는다. 그래서 앱은 `Stacked` 로 둔다.
 */
export const Expanded: Story = {
  args: { duration: Infinity, expand: true },
  render: (args) => (
    <Burst>
      <Toaster {...args} />
    </Burst>
  ),
};

export const ExpandedDark: Story = {
  ...Expanded,
  name: "Expanded (Dark)",
  globals: { theme: "dark" },
};
