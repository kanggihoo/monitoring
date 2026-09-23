import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";

const meta = {
  title: "UI/Button",
  component: Button,
  tags: ["autodocs"],
  // cva 의 variant/size 는 react-docgen 이 유니온을 추론하지 못해 Controls 가 비어 있다.
  // 직접 선언해야 패널에서 조합을 바꿔볼 수 있다.
  argTypes: {
    variant: {
      control: "select",
      options: [
        "default",
        "outline",
        "secondary",
        "ghost",
        "destructive",
        "link",
      ],
    },
    size: {
      control: "select",
      options: [
        "default",
        "xs",
        "sm",
        "lg",
        "icon",
        "icon-xs",
        "icon-sm",
        "icon-lg",
      ],
    },
  },
  args: { children: "저장" },
} satisfies Meta<typeof Button>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Outline: Story = { args: { variant: "outline" } };

export const Secondary: Story = { args: { variant: "secondary" } };

export const Ghost: Story = { args: { variant: "ghost" } };

export const Destructive: Story = { args: { variant: "destructive" } };

export const Link: Story = { args: { variant: "link" } };

export const Small: Story = { args: { size: "sm" } };

export const Large: Story = { args: { size: "lg" } };

export const Disabled: Story = { args: { disabled: true } };

// 토큰이 실제로 반영됐는지 라이트/다크를 한 화면에서 비교하려면 개별 스토리로는 부족하다.
export const AllVariants: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-col gap-6 bg-background p-6">
      <div className="flex flex-wrap items-center gap-3">
        {(
          [
            "default",
            "outline",
            "secondary",
            "ghost",
            "destructive",
            "link",
          ] as const
        ).map((v) => (
          <Button key={v} variant={v}>
            {v}
          </Button>
        ))}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        {(["xs", "sm", "default", "lg"] as const).map((s) => (
          <Button key={s} size={s}>
            {s}
          </Button>
        ))}
        <Button disabled>disabled</Button>
      </div>
    </div>
  ),
};
