import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { Phase } from "@/lib/market-types";
import { chatStore } from "@/stores/chat-store";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

import { ChatPanel } from "./chat-panel";
import { ChatPanelV2 } from "./chat-panel.v2";
import { clockSnapshot } from "./fixtures";

const MESSAGES = [
  ["PanicBuyer", "아 또 던졌다…"],
  ["Satoshi_Fan", "ETH 여기가 바닥"],
  ["느린손", "하이닉스 지금 들어가면 늦었나"],
  ["리플레이어", "브리핑에 환율 얘기 있었음"],
] as const;

/** 스토어를 읽는 컴포넌트라 스토리가 스토어에 메시지를 심는다. */
function seed(count: number) {
  chatStore.setState({
    messages: MESSAGES.slice(0, count).map(([nickname, content], i) => ({
      messageId: i + 1,
      nickname,
      avatarId: (i % 8) + 1,
      content,
      sentAt: new Date().toISOString(),
      gameDate: "2020-08-14",
      withdrawn: false,
      deleted: i === 2 && count > 3,
    })),
  });
}

const meta = {
  title: "Screens/Market/ChatPanel",
  component: ChatPanel,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="h-96 w-80 rounded-lg bg-card p-3">
        <Story />
      </div>
    ),
  ],
  args: { className: "h-full" },
} satisfies Meta<typeof ChatPanel>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 셋째 메시지는 관리자가 지운 것 — 행은 남고 문구만 바뀐다. */
export const Live: Story = {
  render: (args) => {
    useEffect(() => seed(4), []);
    return <ChatPanel {...args} />;
  },
};

export const Empty: Story = {
  render: (args) => {
    useEffect(() => seed(0), []);
    return <ChatPanel {...args} />;
  },
};

export const Dark: Story = { ...Live, globals: { theme: "dark" } };

// --- v2 시안 --------------------------------------------------------------
//
// 조회·전송·쿨다운·스크롤은 v1 그대로다 (`chat-panel.v2.tsx` 가 v1 을 감싸기만 한다).
// 달라지는 것은 **조회가 끝났는데 대화가 없을 때** 그 자리에 무엇을 놓는가 하나뿐이다.

/**
 * 대기 판정은 `useOpeningWait()` 가 한다 — 스토어의 `awaitingFirstPublish` 와 게임 시계를
 * 본다. 스토리도 같은 자리에 값을 심어 **실제 배선을 그대로 태운다** (문구를 손으로 넘기지
 * 않는다).
 */
function seedOpening({ awaiting, phase }: { awaiting: boolean; phase: Phase }) {
  seed(0);
  chatStore.setState({ loaded: true });
  sessionStore.getState().applyClock(clockSnapshot({ phase }));
  sessionStore.getState().setConnected(true);
  tickStore.setState({ awaitingFirstPublish: awaiting });
}

/**
 * 개장 직후 (11:05~첫 종가). 빈 대화창이 **왜 비었는지**를 말한다 — 지금은 이 자리가 완전한
 * 백지라 고장과 구별되지 않는다.
 *
 * 카운트다운은 일부러 붙이지 않았다. 채팅은 첫 종가와 무관하게 지금 바로 쓸 수 있는 칸이라,
 * 남은 시간을 얹으면 "그때까지 못 쓴다" 로 읽힌다.
 */
export const V2Opening: Story = {
  name: "V2 (개장 직후)",
  render: (args) => {
    useEffect(() => seedOpening({ awaiting: true, phase: "TRADING" }), []);
    return <ChatPanelV2 {...args} />;
  },
};

/**
 * 개장 대기가 끝난 뒤의 빈 대화창. **v1 과 똑같이 아무것도 적지 않는다** — 그때 비어 있는
 * 것은 기다리는 중이어서가 아니라 그냥 조용한 것이고, 안내를 계속 띄우면 잔소리가 된다.
 */
export const V2Quiet: Story = {
  name: "V2 (개장 뒤 · 조용함)",
  render: (args) => {
    useEffect(() => seedOpening({ awaiting: false, phase: "TRADING" }), []);
    return <ChatPanelV2 {...args} />;
  },
};

/** 대화가 있으면 v1 과 완전히 같다. */
export const V2Live: Story = {
  name: "V2 (대화 있음)",
  render: (args) => {
    useEffect(() => {
      seedOpening({ awaiting: true, phase: "TRADING" });
      seed(4);
    }, []);
    return <ChatPanelV2 {...args} />;
  },
};

export const V2OpeningDark: Story = {
  ...V2Opening,
  name: "V2 (개장 직후) Dark",
  globals: { theme: "dark" },
};
