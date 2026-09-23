import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  clockSnapshot,
  INSTRUMENTS,
  POSITIONS,
  VALUATION,
} from "@/components/market/fixtures";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { AccountTabs } from "./account-tabs";

/**
 * 탭 아래의 화면들이 각자 조회한다. 한 곳에서 다 심어야 탭을 눌러 가며 볼 수 있다 —
 * 각 화면의 상태별 모습은 그 컴포넌트의 스토리에 있다.
 */
const ROUTES = {
  "/api/v1/executions": {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  },
  "/api/v1/accounts/me/exchanges/preview": 503,
  "/api/v1/accounts/me/exchanges": {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  },
  "/api/v1/orders": { asOfTickSeq: 137, orders: [] },
};

function Seed() {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
    tickStore.setState({
      tickSeq: 137,
      valuation: VALUATION,
      positions: {
        asOfTickSeq: 137,
        asOfGameDate: "2020-08-14",
        usdKrwRate: "1187.20",
        positions: POSITIONS,
      },
      usdKrwRate: "1187.20",
    });
  }, []);
  return null;
}

/** 서버가 채워 넘기는 카드 자리. 실제 내용은 페이지가 만든다. */
const CARD = (title: string) => (
  <Card size="sm">
    <CardHeader>
      <CardTitle>{title}</CardTitle>
    </CardHeader>
    <CardContent className="py-8 text-sm text-muted-foreground">
      서버가 채우는 자리
    </CardContent>
  </Card>
);

const meta = {
  title: "Screens/MyPage/AccountTabs",
  component: AccountTabs,
  parameters: { layout: "fullscreen" },
  beforeEach: () => mockFetch(ROUTES),
  decorators: [
    (Story) => (
      <div className="flex h-[46rem] bg-background p-3">
        <Seed />
        <Story />
      </div>
    ),
  ],
  args: {
    initialTab: "assets",
    instruments: INSTRUMENTS,
    profile: CARD("프로필"),
    inquiry: CARD("문의 내역"),
  },
} satisfies Meta<typeof AccountTabs>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 기본 탭. 탭을 바꾸면 주소의 `?tab=` 도 따라간다 — 다시 읽지는 않는다. */
export const Assets: Story = {};

/** `거래 내역` 탭에서만 오른쪽에 체결·환전 전환이 하나 더 붙는다. */
export const Filled: Story = { args: { initialTab: "filled" } };

export const Reserved: Story = { args: { initialTab: "reserved" } };

/**
 * 계정 관리. 헤더 메뉴가 `?tab=profile` 로 여는 탭이고 **탭 줄에는 나오지 않는다** —
 * 그래서 눌린 탭 없이 줄만 남는다. 줄을 지우지 않는 것은 한 번 눌러 계좌로 돌아갈 길이기
 * 때문이다.
 */
export const Profile: Story = { args: { initialTab: "profile" } };

export const Dark: Story = { globals: { theme: "dark" } };
