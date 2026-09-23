import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Skeleton } from "@/components/ui/skeleton";

const meta = {
  title: "UI/Skeleton",
  component: Skeleton,
  tags: ["autodocs"],
  parameters: { layout: "padded" },
  args: { className: "h-4 w-40" },
} satisfies Meta<typeof Skeleton>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 크기는 컴포넌트가 아니라 className 이 정한다. 텍스트 한 줄, 인풋, 버튼 자리. */
export const Sizes: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex w-80 flex-col gap-3 bg-background p-6">
      <Skeleton className="h-4 w-24" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-8 w-full" />
      <Skeleton className="h-8 w-20" />
    </div>
  ),
};

/** 실제 쓰임새. 들어올 내용과 같은 골격을 그려 레이아웃이 흔들리지 않게 한다. */
export const TableRows: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex w-full max-w-2xl flex-col gap-2 bg-background p-6">
      {Array.from({ length: 5 }, (_, row) => (
        <div key={row} className="flex gap-3">
          <Skeleton className="h-4 w-10" />
          <Skeleton className="h-4 w-32" />
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-16" />
        </div>
      ))}
    </div>
  ),
};

export const Dark: Story = {
  ...Sizes,
  globals: { theme: "dark" },
};
