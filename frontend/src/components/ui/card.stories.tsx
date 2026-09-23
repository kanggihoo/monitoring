import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

const meta = {
  title: "UI/Card",
  component: Card,
  tags: ["autodocs"],
  argTypes: { size: { control: "inline-radio", options: ["default", "sm"] } },
} satisfies Meta<typeof Card>;

export default meta;

type Story = StoryObj<typeof meta>;

const Sample = (args: React.ComponentProps<typeof Card>) => (
  <div className="bg-background p-6">
    <Card {...args} className="max-w-sm">
      <CardHeader>
        <CardTitle>3일차 브리핑</CardTitle>
        <CardDescription>2019.03 ~ 2021.09 · 31개월</CardDescription>
      </CardHeader>
      <CardContent className="text-foreground-subtle">
        이 구간에서 코스피와 코인 시장이 열린다. 개방 직후 폭락과 유동성 랠리가
        이어진다.
      </CardContent>
      <CardFooter className="text-xs text-muted-foreground">
        개장 11:00
      </CardFooter>
    </Card>
  </div>
);

export const Default: Story = { render: Sample };

export const Small: Story = { args: { size: "sm" }, render: Sample };

export const Dark: Story = { render: Sample, globals: { theme: "dark" } };
