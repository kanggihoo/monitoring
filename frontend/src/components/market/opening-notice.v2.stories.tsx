import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { chatStore } from "@/stores/chat-store";
import { sessionStore } from "@/stores/session-store";
import type { Series } from "@/stores/tick-store";

import { ChatPanel } from "./chat-panel";
import { clockSnapshot, INSTRUMENTS, LEADERBOARD } from "./fixtures";
import { InstrumentList } from "./instrument-list";
import { Leaderboard } from "./leaderboard";
import { OpeningNoticeV2, type OpeningSlot } from "./opening-notice.v2";
import { PriceChart } from "./price-chart";

/**
 * 첫 시즌 첫날 11:00~11:05 는 브리핑 구간이고, 11:05 에 재생이 시작돼도 첫 종가까지 약
 * 35초가 더 걸린다. 그 사이 마켓 화면의 네 칸이 전부 빈다 — 이 시안은 그 빈 칸에 무엇을
 * 놓을지를 정한다.
 *
 * 남은 초는 스토리가 직접 넘긴다. 실제로는 게임 시계에서 온다
 * (`use-game-clock.ts` 의 `transition.seconds` · `nextCloseSeconds`).
 */
const meta = {
  title: "Screens/Market/OpeningNotice",
  component: OpeningNoticeV2,
  parameters: { layout: "fullscreen" },
  args: {
    slot: "chart",
    phase: "TRADING",
    seconds: 31,
    countdownLabel: "첫 종가까지",
  },
} satisfies Meta<typeof OpeningNoticeV2>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 통째로 덮는 칸. 종목 목록은 덮지 않으므로 여기 없다. */
const PANEL_SLOTS: OpeningSlot[] = ["chart", "ranking", "chat"];

/** 한 칸. 컨트롤로 `slot` 을 바꿔 가며 문구를 본다. */
export const Single: Story = {
  render: (args) => (
    <div className="flex min-h-64 bg-background p-6">
      <Card size="sm" className="w-80">
        <OpeningNoticeV2 {...args} />
      </Card>
    </div>
  ),
};

/**
 * 재생이 시작됐고 첫 종가를 기다리는 구간 (11:05~).
 *
 * 위는 **덮는 칸**(`panel`) 셋이다. 문구가 서로 부딪히지 않는지 본다 — **채팅에만
 * 카운트다운이 없다.** 첫 종가와 무관하게 지금 바로 쓸 수 있는 칸이라, 남은 시간을 붙이면
 * "그때까지 못 쓴다" 로 읽힌다.
 *
 * 아래는 **얹는 줄**(`strip`) 하나다. 종목 목록은 덮지 않는다 — 실제로 목록 위에 얹힌
 * 모습은 `화면 — 시안 (v2)` 에 있다.
 */
export const FirstTick: Story = {
  parameters: { controls: { disable: true } },
  render: (args) => (
    <div className="flex flex-col gap-3 bg-background p-6">
      <div className="grid gap-3 md:grid-cols-3">
        {PANEL_SLOTS.map((slot) => (
          <Card key={slot} size="sm" className="h-52">
            <OpeningNoticeV2 {...args} slot={slot} />
          </Card>
        ))}
      </div>
      <Card size="sm" className="w-72 p-2">
        <OpeningNoticeV2 {...args} slot="instruments" variant="strip" />
      </Card>
    </div>
  ),
};

/**
 * 아직 재생 전 (11:00~11:05). 기다리는 것이 달라 제목이 갈린다 — 앞은 "아직 시작 전",
 * 뒤는 "시작했고 첫 값을 만드는 중"이다. 11:05 가 지났는지를 화면에서 알 수 있어야 한다.
 */
export const Briefing: Story = {
  ...FirstTick,
  args: { phase: "BRIEFING", seconds: 218, countdownLabel: "개장까지" },
};

/**
 * 관리자가 재생을 멈춘 구간. **`00:00` 으로 두지 않는다** — 그 표기는 "곧 바뀐다" 와
 * "멈춰서 안 바뀐다" 를 구별하지 못한다 (`header-live.tsx` 와 같은 판단).
 */
export const Paused: Story = { ...FirstTick, args: { seconds: null } };

/** 값이 곧 들어오는 마지막 몇 초. */
export const AboutToOpen: Story = { ...FirstTick, args: { seconds: 3 } };

// --- 화면 전체 ------------------------------------------------------------
//
// 칸 하나가 아니라 **그때의 마켓 화면**을 본다. 아래 둘은 같은 자리·같은 데이터(시세 없음)
// 이고 빈 칸을 무엇으로 채우는지만 다르다.

/** 마켓 화면의 자리 배치를 옮긴 껍데기. 실제 격자는 `market-screen.tsx` 가 정한다. */
function Shell({
  instruments,
  chart,
  ranking,
  chat,
}: Record<"instruments" | "chart" | "ranking" | "chat", React.ReactNode>) {
  return (
    <div className="grid h-[42rem] grid-cols-[18rem_minmax(0,1fr)_20rem] gap-3 bg-background p-3">
      <Card size="sm" className="flex min-h-0 flex-col">
        <CardHeader className="shrink-0">
          <CardTitle>종목</CardTitle>
        </CardHeader>
        {instruments}
      </Card>

      <Card size="sm" className="flex min-h-0 flex-col">
        <CardHeader className="shrink-0">
          <CardTitle>삼성전자</CardTitle>
        </CardHeader>
        {chart}
      </Card>

      <div className="flex min-h-0 flex-col gap-3">
        {ranking}
        <Card size="sm" className="flex min-h-0 flex-1 flex-col">
          <CardHeader className="shrink-0">
            <CardTitle>채팅</CardTitle>
          </CardHeader>
          {chat}
        </Card>
      </div>
    </div>
  );
}

/** 시세가 아직 없는 상태의 차트 시리즈 — 종목은 정해졌고 종가만 비었다. */
const EMPTY_SERIES: Series = {
  instrumentId: 15,
  currency: "KRW",
  priceScale: 0,
  points: [],
};

/** 채팅 칸은 스토어를 읽는다. **조회는 끝났고 메시지가 없는** 상태를 심는다. */
function SeedChat() {
  useEffect(() => {
    chatStore.setState({ messages: [], loaded: true });
    sessionStore.getState().applyClock(clockSnapshot());
    sessionStore.getState().setConnected(true);
  }, []);
  return null;
}

/**
 * **지금 (v1).** 같은 순간에 네 칸이 서로 다른 말을 하거나 아무 말도 하지 않는다 — 종목은
 * 값이 전부 `—`, 차트는 작은 회색 한 줄, 채팅은 완전한 빈 칸, 리더보드는 빈 목록이다.
 * 넷 다 사실이지만 **언제 시작되는지는 아무도 말하지 않는다.**
 *
 * 실제 컴포넌트에 빈 데이터를 먹인 것이라, 여기 보이는 것이 지금 코드의 모습 그대로다.
 */
export const ScreenNow: Story = {
  name: "화면 — 지금 (v1)",
  parameters: { controls: { disable: true } },
  render: () => (
    <>
      <SeedChat />
      <Shell
        instruments={
          <InstrumentList
            instruments={INSTRUMENTS}
            prices={{}}
            selectedId={15}
            onSelect={() => {}}
            className="min-h-0 flex-1"
          />
        }
        chart={
          <CardContent className="min-h-0 flex-1">
            <PriceChart
              series={EMPTY_SERIES}
              changeRate={undefined}
              className="h-full"
            />
          </CardContent>
        }
        ranking={
          <Leaderboard
            board={{ ...LEADERBOARD, content: [] }}
            me={null}
            className="h-64 shrink-0"
          />
        }
        chat={
          <CardContent className="min-h-0 flex-1">
            <ChatPanel className="h-full" />
          </CardContent>
        }
      />
    </>
  ),
};

/**
 * **시안 (v2).** 네 칸이 같은 모양으로 "무엇을 기다리는 중이고 몇 초 남았는지"를 말한다.
 *
 * **종목 목록은 덮지 않는다.** 실제 목록을 그대로 두고 값 자리의 `—` 를 설명하는 한 줄만
 * 위에 얹는다 — 이 목록은 상장 예정 종목의 값 자리에 `—` 를 두지 않는데, "같은 자리에 같은
 * 빗금이 서면 '값을 못 받았다' 로 읽힌다" 는 이유에서다 (`instrument-list.tsx`). 첫 종가
 * 전의 `—` 도 같은 처지다.
 *
 * 채팅 칸은 안내만 그렸다. 실제로는 아래에 입력줄(또는 로그인 안내)이 그대로 남으므로
 * 안내가 차지하는 높이는 이보다 작다.
 */
export const ScreenV2: Story = {
  name: "화면 — 시안 (v2)",
  parameters: { controls: { disable: true } },
  render: (args) => (
    <Shell
      instruments={
        // **목록을 그대로 둔다.** 이름·갈래·관심 별은 첫 종가와 무관하게 이미 제 값이다.
        <div className="flex min-h-0 flex-1 flex-col gap-2 px-3 pb-3">
          <OpeningNoticeV2 {...args} slot="instruments" variant="strip" />
          <InstrumentList
            instruments={INSTRUMENTS}
            prices={{}}
            selectedId={15}
            onSelect={() => {}}
            className="min-h-0 flex-1"
          />
        </div>
      }
      chart={<OpeningNoticeV2 {...args} slot="chart" />}
      ranking={
        <Card size="sm" className="flex h-64 shrink-0 flex-col">
          <CardHeader className="shrink-0">
            <CardTitle>리더보드</CardTitle>
          </CardHeader>
          <OpeningNoticeV2 {...args} slot="ranking" />
        </Card>
      }
      chat={<OpeningNoticeV2 {...args} slot="chat" />}
    />
  ),
};

export const ScreenV2Dark: Story = {
  ...ScreenV2,
  name: "화면 — 시안 (v2) Dark",
  globals: { theme: "dark" },
};
