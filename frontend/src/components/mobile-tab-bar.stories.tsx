import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { MobileTabBar } from "./mobile-tab-bar";

/**
 * 폰 전용이다 — `md` 이상에서는 `hidden` 이라 아무것도 보이지 않는다. 미리보기 프레임을
 * 360px 쯤으로 좁혀서 본다. 넓은 화면의 짝은 `Layout/AppSidebar` 다.
 */
const meta = {
  title: "Layout/MobileTabBar",
  component: MobileTabBar,
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/" } },
  },
  decorators: [
    (Story) => (
      <div className="h-96 bg-background">
        <Story />
      </div>
    ),
  ],
  args: { isAdmin: false, isLoggedIn: true },
} satisfies Meta<typeof MobileTabBar>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 다섯 칸 중 넷이 화면이고 마지막이 `더보기` 다. */
export const Market: Story = {};

/** 현재 화면이 바뀌면 켜지는 칸도 바뀐다. */
export const MyPage: Story = {
  parameters: { nextjs: { navigation: { pathname: "/mypage" } } },
};

/**
 * 더보기 안의 화면을 볼 때. **`더보기` 칸이 현재 위치를 대신 말한다** — 그러지 않으면
 * 뉴스를 보는 동안 어느 칸도 켜지지 않아 자기가 어디 있는지 알 수 없다.
 */
export const InsideMore: Story = {
  parameters: { nextjs: { navigation: { pathname: "/news" } } },
};

/**
 * 미인증. 개인 진입점이 사라지지 않고 자물쇠가 겹친다 — **누르기 전에** 로그인이 필요하다는
 * 사실이 보여야 한다 (ADR-0005). 누르면 화면을 넘기지 않고 안내를 얹는다.
 */
export const Unauthenticated: Story = { args: { isLoggedIn: false } };

/** 관리자 항목은 `더보기` 시트 안에 붙는다. 탭바의 다섯 칸은 늘어나지 않는다. */
export const Admin: Story = { args: { isAdmin: true } };

export const Dark: Story = { globals: { theme: "dark" } };
