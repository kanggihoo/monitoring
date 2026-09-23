import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Stat } from "./stat";

const meta = {
  title: "Common/Stat",
  component: Stat,
  tags: ["autodocs"],
  args: { label: "매출 성장률", value: "+18.2%", hint: "2019년 기준" },
} satisfies Meta<typeof Stat>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => (
    <div className="bg-card p-6">
      <Stat {...args} />
    </div>
  ),
};

export const Row: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="grid grid-cols-3 gap-4 bg-card p-6">
      <Stat label="매출" value="8.2조" />
      <Stat label="영업이익률" value="12.4%" />
      <Stat label="부채비율" value="61%" hint="해당 시점까지" />
    </div>
  ),
};

export const Dark: Story = { ...Row, globals: { theme: "dark" } };
