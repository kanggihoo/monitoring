import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Header } from "@/components/header";
import {
  clockSnapshot,
  RANKING_ME,
  VALUATION,
} from "@/components/market/fixtures";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

/** 지표는 스토어에서 오므로 스토리가 시계·평가액·순위를 심는다. */
function SeedStores() {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
    tickStore.setState({
      tickSeq: 137,
      valuation: VALUATION,
      myRank: RANKING_ME,
      usdKrwRate: "1187.20",
    });
  }, []);
  return null;
}

const meta = {
  title: "Layout/Header",
  component: Header,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="bg-background p-3">
        <SeedStores />
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof Header>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Authenticated: Story = {
  args: {
    user: {
      id: 1,
      email: "hong@example.com",
      nickname: "홍길동",
      role: "USER",
      providers: ["google"],
    },
  },
};

/** 이메일이 없는 사용자도 Header 에서는 닉네임만 쓴다. */
export const AuthenticatedWithoutEmail: Story = {
  args: {
    user: {
      id: 2,
      email: null,
      nickname: "이메일없음",
      role: "USER",
      providers: ["ssafy"],
    },
  },
};

/** 관리자에게만 관리자 화면 링크가 보인다. 지금 상단 바의 유일한 진입 수단이다. */
export const Admin: Story = {
  args: {
    user: {
      id: 3,
      email: "admin@example.com",
      nickname: "관리자",
      role: "ADMIN",
      providers: ["google"],
    },
  },
};

/**
 * 샌드박스 안. 로그인 방법이 하나도 없다는 사실로 판정하며, 그때 안내 띠가 상단에 붙는다.
 * 권한이 USER 인 이유는 진입하면 대상이 Test User 이기 때문이다.
 */
export const InSandbox: Story = {
  args: {
    user: {
      id: 4,
      email: null,
      nickname: "샌드박스-관리자",
      role: "USER",
      providers: [],
    },
  },
};

/**
 * 미인증. **개인 지표(자산·현금·당일·순위)가 빈 값이 아니라 로그인 안내로 바뀐다** — 데이터가
 * 없는 것과 권한이 없는 것을 구별할 수 있어야 한다 (ADR-0005). 공개 지표(게임 시계·환율)는
 * 그대로 흐른다.
 */
export const Unauthenticated: Story = { args: { user: null } };
