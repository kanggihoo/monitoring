import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

// 포털로 붙는 팝업이라 autodocs 는 걸지 않는다 (`dialog.stories.tsx` 와 같은 이유).
//
// 쓰는 곳은 종목 목록의 정렬 고르개 하나다 — `size="sm"` 에 값이 늘 채워져 있다.
// 그룹·구분선·placeholder·disabled 는 쓰는 곳이 없어 담지 않는다.
const meta = {
  title: "UI/Select",
  component: Select,
  parameters: { layout: "centered" },
} satisfies Meta<typeof Select>;

export default meta;

type Story = StoryObj<typeof meta>;

const SORTS = [
  ["DEFAULT", "기본 순"],
  ["CHANGE_DESC", "등락률 높은 순"],
  ["CHANGE_ASC", "등락률 낮은 순"],
  ["VOLUME", "거래량 순"],
] as const;

export const Default: Story = {
  render: () => (
    <div className="w-44 bg-background p-6">
      <Select defaultValue="DEFAULT">
        <SelectTrigger size="sm" className="w-full" aria-label="정렬">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {SORTS.map(([value, label]) => (
            <SelectItem key={value} value={value}>
              {label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
