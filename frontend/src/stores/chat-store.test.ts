import { describe, expect, it } from "vitest";

import type { ChatMessage } from "@/lib/market-types";
import { createChatStore } from "@/stores/chat-store";

const message = (messageId: number): ChatMessage => ({
  messageId,
  nickname: `투자자${messageId}`,
  avatarId: 7,
  content: `메시지 ${messageId}`,
  sentAt: "2026-09-21T05:31:40Z",
  gameDate: "2022-04-15",
  withdrawn: false,
  deleted: false,
});

describe("채팅 히스토리", () => {
  // **응답은 최신순이다** (`OrderByIdDesc`). 그대로 넣으면 화면에 거꾸로 그려진다.
  it("최신순 응답을 뒤집어 오래된 것부터 담는다", () => {
    const store = createChatStore();

    store.getState().hydrate([message(3), message(2), message(1)]);

    expect(store.getState().messages.map((item) => item.messageId)).toEqual([
      1, 2, 3,
    ]);
    expect(store.getState().loaded).toBe(true);
  });

  // 구독이 먼저 붙고 조회가 나중에 끝나는 순서가 정상이다 (`ws.md`). 그 사이 도착한
  // 메시지가 히스토리에도 들어 있으면 같은 줄이 두 번 보인다.
  it("실시간으로 이미 받은 메시지를 두 번 담지 않는다", () => {
    const store = createChatStore();
    store.getState().receive(message(3));

    store.getState().hydrate([message(3), message(2), message(1)]);

    expect(store.getState().messages.map((item) => item.messageId)).toEqual([
      1, 2, 3,
    ]);
  });

  it("히스토리가 실시간 메시지 앞에 붙는다", () => {
    const store = createChatStore();
    store.getState().receive(message(9));

    store.getState().hydrate([message(2), message(1)]);

    expect(store.getState().messages.map((item) => item.messageId)).toEqual([
      1, 2, 9,
    ]);
  });

  /**
   * 재연결 재조회다. 들고 있는 것보다 **새것**이 조회로 들어온다 — 무조건 앞에 붙이던
   * 이전 판은 여기서 `[3,4,1,2]` 를 만들었다.
   */
  it("끊겨 있던 동안 쌓인 메시지가 들고 있던 것 뒤에 붙는다", () => {
    const store = createChatStore();
    store.getState().receive(message(1));
    store.getState().receive(message(2));

    // 재연결 뒤 재조회 — 최신순 응답에 단절 구간(3·4)과 이미 아는 것(2·1)이 함께 온다.
    store.getState().hydrate([message(4), message(3), message(2), message(1)]);

    expect(store.getState().messages.map((item) => item.messageId)).toEqual([
      1, 2, 3, 4,
    ]);
  });

  // 삭제는 조회에만 실려 온다 — 끊겨 있는 동안 지워진 메시지가 `chat.deleted` 를 놓친다.
  it("재조회가 들고 있던 같은 메시지의 삭제를 반영한다", () => {
    const store = createChatStore();
    store.getState().receive(message(1));

    store.getState().hydrate([{ ...message(1), deleted: true, content: null }]);

    expect(store.getState().messages).toHaveLength(1);
    expect(store.getState().messages[0]).toMatchObject({
      messageId: 1,
      deleted: true,
    });
  });

  /**
   * 채팅의 "오늘"은 게임 일차다. 서버가 그 일차만 내려주므로 (`soc.md`) 조회로는 어제 대화가
   * 지워지지 않고, 켜 둔 탭에만 남는다.
   */
  it("일차가 바뀌면 들고 있던 대화를 버린다", () => {
    const store = createChatStore();
    store.getState().syncDay(1);
    store.getState().hydrate([message(2), message(1)]);

    store.getState().syncDay(2);

    expect(store.getState().messages).toEqual([]);
    expect(store.getState().loaded).toBe(false);
  });

  /**
   * **같은 일차면 아무것도 하지 않는다.** 채팅창은 화면을 옮길 때마다 다시 마운트되므로,
   * "일차가 왔다" 를 곧 "바뀌었다" 로 읽으면 마켓 → 랭킹 → 마켓 왕복마다 쌓아 둔 대화가
   * 날아간다 (조회가 실패하면 통째로 사라진다).
   */
  it("같은 일차로 다시 맞춰도 대화를 유지한다", () => {
    const store = createChatStore();
    store.getState().syncDay(1);
    store.getState().hydrate([message(2), message(1)]);

    store.getState().syncDay(1);

    expect(store.getState().messages.map((item) => item.messageId)).toEqual([
      1, 2,
    ]);
    expect(store.getState().loaded).toBe(true);
  });

  // 시계가 붙기 전에는 일차를 모른다. 그것을 "바뀌었다" 로 읽으면 첫 조회 결과를 버린다.
  it("일차를 아직 모르면 아무것도 하지 않는다", () => {
    const store = createChatStore();
    store.getState().syncDay(1);
    store.getState().hydrate([message(1)]);

    store.getState().syncDay(null);

    expect(store.getState().messages).toHaveLength(1);
    expect(store.getState().seasonDay).toBe(1);
  });

  // 행을 지우지 않는다 — 커서가 가리키는 지점이 사라지면 안 되기 때문이다 (`adm.md`).
  it("관리자 삭제는 본문만 비운다", () => {
    const store = createChatStore();
    store.getState().receive(message(1));

    store.getState().markDeleted(1);

    expect(store.getState().messages[0]).toMatchObject({
      messageId: 1,
      deleted: true,
      content: null,
    });
  });
});
