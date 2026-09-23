import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Card, CardContent } from "@/components/ui/card";

import { EmptyCard, PageFooter } from "./page-footer";

const meta = {
  title: "Screens/MyPage/PageFooter",
  component: PageFooter,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="w-[36rem] max-w-full bg-background p-6">
        <Card size="sm">
          <CardContent className="py-6 text-center text-sm text-muted-foreground">
            표가 들어갈 자리
          </CardContent>
          <Story />
        </Card>
      </div>
    ),
  ],
  args: {
    meta: { page: 1, totalPages: 3, totalElements: 48, hasNext: true },
    unit: "건",
    onPage: () => {},
  },
} satisfies Meta<typeof PageFooter>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 가운데 페이지. 앞뒤가 모두 열려 있다. */
export const Middle: Story = {};

export const FirstPage: Story = {
  args: { meta: { page: 0, totalPages: 3, totalElements: 48, hasNext: true } },
};

export const LastPage: Story = {
  args: { meta: { page: 2, totalPages: 3, totalElements: 48, hasNext: false } },
};

/**
 * 한 페이지에 다 들어가면 **아무것도 그리지 않는다** — 버튼만 남겨 두면 끝인지 아닌지를
 * 눌러 보고서야 안다. 이 스토리는 빈 바닥이 정답이다.
 */
export const SinglePage: Story = {
  args: { meta: { page: 0, totalPages: 1, totalElements: 7, hasNext: false } },
};

/** 표가 아직 없을 때의 한 칸. 첫 조회 전과 빈 목록을 문구로 가른다. */
export const Empty: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <>
      <EmptyCard>체결 내역을 불러오는 중입니다.</EmptyCard>
      <EmptyCard>아직 체결된 거래가 없습니다.</EmptyCard>
    </>
  ),
};

export const Dark: Story = { globals: { theme: "dark" } };
