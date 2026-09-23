import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const meta = {
  title: "UI/Input",
  component: Input,
  tags: ["autodocs"],
  args: { placeholder: "수량" },
} satisfies Meta<typeof Input>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => (
    <div className="flex w-64 flex-col gap-1.5 bg-background p-6">
      <Label htmlFor="qty">수량</Label>
      <Input id="qty" {...args} />
    </div>
  ),
};

export const Invalid: Story = {
  ...Default,
  args: { placeholder: "수량", "aria-invalid": true, defaultValue: "9999" },
};

export const Disabled: Story = { ...Default, args: { disabled: true } };

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
