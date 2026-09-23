import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  AWARDS,
  AWARDS_TIED,
  clockSnapshot,
  EXTREME_LEADERBOARD,
  EXTREME_RANKING_ME,
  LEADERBOARD,
  POINT_LEADERBOARD,
  RANKING_ME,
} from "@/components/market/fixtures";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { RankingScreen } from "./ranking-screen";

/** 순서가 중요하다 — `/rankings/awards` 와 `/rankings/me` 가 `/rankings` 보다 먼저다. */
const ROUTES = {
  "/api/v1/rankings/awards": AWARDS,
  "/api/v1/rankings/me": RANKING_ME,
  "/api/v1/rankings": LEADERBOARD,
};

function Seed({ me = true }: { me?: boolean }) {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
    tickStore.setState({ tickSeq: 137, myRank: me ? RANKING_ME : null });
  }, [me]);
  return null;
}

const meta = {
  title: "Screens/Ranking/RankingScreen",
  component: RankingScreen,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="flex min-h-[44rem] bg-background p-3">
        <Seed />
        <Story />
      </div>
    ),
  ],
  args: { isLoggedIn: true },
  beforeEach: () => mockFetch(ROUTES),
} satisfies Meta<typeof RankingScreen>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 처음 열면 오늘 겨루는 판(일간)을 보여준다. 내 줄은 목록의 값으로 고정 줄에 선다. */
export const Default: Story = {};

/**
 * 암전(16:00~16:40). **화면이 가리는 것이 아니다** — 서버가 `content` 를 빈 배열로 준다
 * (기획 11.3.1). 내 수익률은 그대로 보이고 가려지는 것은 타인과의 비교뿐이다.
 */
export const Blackout: Story = {
  beforeEach: () =>
    mockFetch({
      ...ROUTES,
      "/api/v1/rankings/me": {
        ...RANKING_ME,
        seasonRank: null,
        blackout: true,
      },
      "/api/v1/rankings": {
        ...LEADERBOARD,
        blackout: true,
        content: [],
        totalElements: 0,
        totalPages: 0,
        hasNext: false,
      },
    }),
};

/**
 * 미인증. 개인 조회의 401 은 "다시 시도" 가 아니라 "로그인하세요" 다 — 공개 조회 실패와
 * 구별한다 (ADR-0005).
 */
export const Unauthenticated: Story = {
  args: { isLoggedIn: false },
  decorators: [
    (Story) => (
      <div className="flex min-h-[44rem] bg-background p-3">
        <Seed me={false} />
        <Story />
      </div>
    ),
  ],
  beforeEach: () => mockFetch({ ...ROUTES, "/api/v1/rankings/me": 401 }),
};

/**
 * **1위가 120명 동점인 일차.** 아무도 거래하지 않으면 전원이 0.00% 라 실제로 이렇게 된다.
 * 펴서 그리면 우측 열이 화면 밖으로 밀리므로 한 줄로 접고, 그래도 넘치면 열이 스크롤한다.
 */
export const TiedWinners: Story = {
  beforeEach: () =>
    mockFetch({ ...ROUTES, "/api/v1/rankings/awards": AWARDS_TIED }),
};

/** 시상이 아직 없는 시즌 첫날. */
export const NoAwards: Story = {
  beforeEach: () =>
    mockFetch({
      ...ROUTES,
      "/api/v1/rankings/awards": { daily: [], final: [] },
    }),
};

/** 공개 조회가 깨졌을 때. 다시 시도 버튼이 붙는다. */
export const Failed: Story = {
  beforeEach: () => mockFetch({ ...ROUTES, "/api/v1/rankings": 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };

/**
 * **자릿수가 터졌을 때.** 여기가 가장 먼저 무너지는 자리다 — 열 폭이 고정이고
 * (`ranking-screen.tsx` 의 `COLUMNS`) 값은 `whitespace-nowrap` 이라 접히지 않는다.
 *
 * 두 칸을 같이 본다. **수익률**은 폰 `4.5rem`(72px) · `md` `5rem`(80px) 이고,
 * **평가액**은 `평가액` 탭에서만 나오며 폰 `6rem`(96px) · `md` `8rem`(128px) 이다.
 * 탭을 `평가액` 으로 옮기면 한 줄에 둘이 같이 늘어나는 모습이 보인다.
 *
 * 자릿수만 따로 보려면 `Common/PriceChange` 의 `Overflowing` 이 같은 값을 실제 칸 폭에
 * 둘러 그린다.
 */
export const Extreme: Story = {
  beforeEach: () =>
    mockFetch({
      ...ROUTES,
      "/api/v1/rankings/me": EXTREME_RANKING_ME,
      "/api/v1/rankings": EXTREME_LEADERBOARD,
    }),
  decorators: [
    (Story) => (
      <div className="flex min-h-[44rem] bg-background p-3">
        <Seed me={false} />
        <Story />
      </div>
    ),
  ],
};

export const ExtremeDark: Story = {
  ...Extreme,
  name: "Extreme (Dark)",
  globals: { theme: "dark" },
};

/**
 * 포인트 합산 (`S15P21A605-250`). **다른 셋과 출처가 다른 트랙이다** — 폐장에 확정된
 * 일차의 순위를 점수로 바꿔 더한 값이라 등락 칸이 없고 암전도 걸리지 않는다.
 *
 * 수익률 칸의 라벨이 `일간 수익률 합` 인 것을 여기서 확인한다. `누적` 으로 읽히면
 * 100 % 뒤 −50 % 를 50 % 로 보게 된다 — 공지문 표기까지 못박은 자리다.
 */
export const Point: Story = {
  beforeEach: () =>
    mockFetch({ ...ROUTES, "/api/v1/rankings": POINT_LEADERBOARD }),
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(canvas.getByRole("tab", { name: "시즌 포인트" }));
  },
};
