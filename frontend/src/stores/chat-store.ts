"use client";

import { createStore } from "zustand/vanilla";

import type { ChatMessage } from "@/lib/market-types";

/** 채팅 스토어 — 사람이 말할 때 바뀐다. 게임 시계와 무관하다. 최근 200건만 든다. */
const CHAT_LIMIT = 200;

export type ChatState = {
  /** 오래된 것부터. 새 메시지는 뒤에 붙는다 — 화면이 그 순서로 읽는다. */
  messages: ChatMessage[];
  /** 히스토리를 한 번이라도 받았는지. 첫 조회 전과 "메시지가 없다" 를 가른다. */
  loaded: boolean;
  /**
   * 들고 있는 대화가 어느 일차의 것인가. **화면이 아니라 여기 있어야 한다** — 채팅창은
   * 화면을 옮기면 사라지는 컴포넌트이고, 그 안에 기억을 두면 마켓 → 랭킹 → 마켓 왕복마다
   * "일차가 바뀌었다" 로 읽혀 쌓아 둔 대화를 버린다.
   */
  seasonDay: number | null;
};

export type ChatActions = {
  receive: (message: ChatMessage) => void;
  /**
   * 히스토리를 들고 있는 것과 **시간순으로 합친다** (기획 12.1.3). 응답은 최신순이다 (`soc.md`).
   *
   * 입장 시와 재연결 시가 같은 함수다. **앞에 붙이지 않는 이유가 재연결이다** — 끊겨 있던
   * 동안 쌓인 메시지는 들고 있는 것보다 새것이라, 무조건 앞에 붙이면 `[3,4,1,2]` 가 된다.
   * `messageId` 는 발행 순서대로 늘어나므로(`OrderByIdDesc` 가 최신순인 근거) 그것으로 세운다.
   *
   * 같은 `messageId` 는 **조회 쪽을 남긴다** — 관리자 삭제가 그 경로로 들어온다.
   */
  hydrate: (items: ChatMessage[]) => void;
  /** 관리자 삭제. 행은 남기고 "삭제된 메시지" 로 바뀐다 (`adm.md`). */
  markDeleted: (messageId: number) => void;
  /**
   * 서버가 말한 일차를 맞춘다. **달라졌을 때만** 들고 있던 대화를 버린다 — 서버가 오늘
   * 것만 주므로 조회로는 지워지지 않고 (`hydrate` 는 합친다), 켜 둔 탭에만 어제 대화가 남는다.
   *
   * **`loaded` 도 되돌린다.** 새 일차의 첫 조회가 끝나기 전까지는 "대화가 없는 것"이
   * 아니라 "아직 모르는 것"이고, 화면이 그 둘을 다르게 그린다.
   *
   * @param seasonDay 서버 시계의 일차. 아직 모르면 `null` 이고 그때는 아무것도 하지 않는다
   */
  syncDay: (seasonDay: number | null) => void;
};

export function createChatStore() {
  return createStore<ChatState & ChatActions>()((set) => ({
    messages: [],
    loaded: false,
    seasonDay: null,

    receive(message) {
      set((state) => ({
        messages: [...state.messages, message].slice(-CHAT_LIMIT),
      }));
    },

    hydrate(items) {
      set((state) => {
        const merged = new Map(
          state.messages.map((item) => [item.messageId, item])
        );
        for (const item of items) {
          merged.set(item.messageId, item);
        }
        return {
          messages: [...merged.values()]
            .sort((a, b) => a.messageId - b.messageId)
            .slice(-CHAT_LIMIT),
          loaded: true,
        };
      });
    },

    syncDay(seasonDay) {
      if (seasonDay === null) return;
      set((state) =>
        state.seasonDay === seasonDay
          ? state
          : { ...state, messages: [], loaded: false, seasonDay }
      );
    },

    markDeleted(messageId) {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.messageId === messageId
            ? { ...message, deleted: true, content: null }
            : message
        ),
      }));
    },
  }));
}

export type ChatStore = ReturnType<typeof createChatStore>;

export const chatStore = createChatStore();
