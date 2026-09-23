import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Badge } from "@/components/ui/badge";

const meta = {
  title: "UI/Badge",
  component: Badge,
  tags: ["autodocs"],
  argTypes: {
    variant: {
      control: "select",
      options: [
        "default",
        "secondary",
        "destructive",
        "outline",
        "ghost",
        "link",
      ],
    },
  },
  args: { children: "코스피" },
} satisfies Meta<typeof Badge>;

export default meta;

type Story = StoryObj<typeof meta>;

const VARIANTS = [
  "default",
  "secondary",
  "destructive",
  "outline",
  "ghost",
] as const;

export const Default: Story = {};

/** DESIGN.md 의 뱃지 radius 는 sm(6px) 이다. 기본 알약 모양은 rounded-sm 로 덮어 쓴다. */
export const Squared: Story = {
  args: { className: "rounded-sm", children: "거래정지" },
};

export const AllVariants: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-wrap items-center gap-2 bg-background p-6">
      {VARIANTS.map((v) => (
        <Badge key={v} variant={v} className="rounded-sm">
          {v}
        </Badge>
      ))}
    </div>
  ),
};

export const Dark: Story = {
  ...AllVariants,
  globals: { theme: "dark" },
};
