import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { PageLinks } from "@/components/page-links";

/** 목록의 앞뒤 이동. 페이지 번호를 나열하지 않고 현재/전체만 적는다. */
const meta = {
  title: "Common/PageLinks",
  component: PageLinks,
  tags: ["autodocs"],
  args: {
    basePath: "/admin/users",
    page: 2,
    hasNext: true,
    totalPages: 5,
    query: { q: "kim" },
  },
  decorators: [
    (Story) => (
      <div className="w-[32rem] max-w-full">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof PageLinks>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Middle: Story = {};

/** 첫 페이지 — 이전 링크 자리가 비고 다음만 남는다. */
export const First: Story = { args: { page: 0 } };

/** 마지막 페이지 — 다음 링크 자리가 빈다. */
export const Last: Story = { args: { page: 4, hasNext: false } };

/** 한 쪽뿐이면 아무것도 그리지 않는다. */
export const SinglePage: Story = {
  args: { page: 0, hasNext: false, totalPages: 1 },
};

export const Dark: Story = { globals: { theme: "dark" } };
