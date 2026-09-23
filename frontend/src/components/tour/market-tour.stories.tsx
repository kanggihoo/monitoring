import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { forgetTour } from "@/lib/tour";

import { MARKET_TOUR, MarketTour } from "./market-tour";

/**
 * 실제 마켓 단계를 쓰는 배선. 자리는 마켓 화면의 `data-tour` 를 가리키므로, 스토리에서는
 * 같은 이름을 붙인 가짜 칸으로 대신한다.
 *
 * 저절로 뜨는 조건은 "본 적 없음" 이라 스토리마다 기억을 지운다 — 그러지 않으면 한 번 보고
 * 나서는 다시 뜨지 않는다.
 */
function MarketShell() {
  const boxes = [
    ["header", "헤더", "h-16"],
    ["instrument-list", "종목 목록", "h-40"],
    ["instrument-detail", "차트", "h-40"],
    ["order-ticket", "주문", "h-28"],
    ["leaderboard", "리더보드", "h-28"],
    ["news-ticker", "뉴스", "h-12"],
  ] as const;

  return (
    <div className="flex min-h-screen flex-col gap-3 bg-background p-3">
      {boxes.map(([target, label, height]) => (
        <div
          key={target}
          data-tour={target}
          className={`flex items-center rounded-xl border border-border bg-card px-4 text-sm ${height}`}
        >
          {label}
        </div>
      ))}
    </div>
  );
}

// 포털로 붙어 화면을 덮으므로 autodocs 는 걸지 않는다.
const meta = {
  title: "Layout/MarketTour",
  component: MarketTour,
  parameters: { layout: "fullscreen" },
  beforeEach: () => {
    forgetTour(MARKET_TOUR);
  },
  render: (args) => (
    <>
      <MarketShell />
      <MarketTour {...args} />
    </>
  ),
  args: { isLoggedIn: true },
} satisfies Meta<typeof MarketTour>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 처음 온 사람에게 저절로 뜬다. 여섯 단계를 끝까지 넘겨 본다. */
export const FirstVisit: Story = {};

/**
 * 미인증이 끝까지 봤을 때. 마지막 `완료` 뒤에 로그인 권유가 이어 뜬다 — 저절로 뜬 첫
 * 회차에만이다.
 */
export const UnauthenticatedCompletesTour: Story = {
  args: { isLoggedIn: false },
};

export const Dark: Story = { globals: { theme: "dark" } };
