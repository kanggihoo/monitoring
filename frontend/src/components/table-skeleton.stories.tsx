import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { TableSkeleton } from "@/components/table-skeleton";

/** 목록 화면의 `loading.tsx` 가 쓰는 표 골격. 열 수만 화면마다 다르다. */
const meta = {
  title: "Common/TableSkeleton",
  component: TableSkeleton,
  tags: ["autodocs"],
  argTypes: {
    columns: { control: { type: "number", min: 1, max: 8 } },
    rows: { control: { type: "number", min: 1, max: 20 } },
  },
  args: { columns: 4 },
  decorators: [
    (Story) => (
      <div className="w-[48rem] max-w-full rounded-lg border border-border bg-card">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof TableSkeleton>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 관리자 감사 로그 표와 같은 열 수. */
export const SixColumns: Story = { args: { columns: 6 } };

/** 행이 적을 때 — 내용이 들어올 때 접히는 높이가 작아야 한다. */
export const ThreeRows: Story = { args: { columns: 3, rows: 3 } };

export const Dark: Story = { globals: { theme: "dark" } };
