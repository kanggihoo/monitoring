import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

const meta = {
  title: "UI/Textarea",
  component: Textarea,
  tags: ["autodocs"],
  args: { placeholder: "무엇이 문제였는지 적어 주세요" },
} satisfies Meta<typeof Textarea>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => (
    <div className="flex w-80 flex-col gap-1.5 bg-background p-6">
      <Label htmlFor="content">내용</Label>
      <Textarea id="content" {...args} />
    </div>
  ),
};

/** 내용에 맞춰 높이가 늘어난다 (`field-sizing-content`). */
export const Filled: Story = {
  ...Default,
  args: {
    defaultValue:
      "로그인 버튼을 눌러도 아무 반응이 없습니다.\n다른 브라우저에서도 같습니다.",
  },
};

export const Invalid: Story = {
  ...Default,
  args: { "aria-invalid": true, defaultValue: "가".repeat(40) },
};

export const Disabled: Story = { ...Default, args: { disabled: true } };

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
