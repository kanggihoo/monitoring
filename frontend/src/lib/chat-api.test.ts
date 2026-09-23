import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  ChatCooldownError,
  fetchChatHistory,
  sendChatMessage,
} from "@/lib/chat-api";

/**
 * 채팅. **쿨다운(429)이 다른 실패와 갈려야 한다** — 계약이 상태 표에 없는 코드를 여기서만
 * 예외로 둔 이유가 그것이다 (`soc.md`): 409 로 뭉뚱그리면 "3초 뒤 다시" 와 "열람 전용이라
 * 불가" 를 화면이 구분하지 못한다.
 */

function stubResponse(response: Response) {
  const calls: { url: string; init?: RequestInit }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return Promise.resolve(response);
    })
  );
  return calls;
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("히스토리", () => {
  it("커서를 주지 않으면 쿼리에 없다", async () => {
    const calls = stubResponse(json({ data: { items: [] } }));

    await fetchChatHistory();

    expect(calls[0]?.url).toBe("/api/v1/chat/messages?size=100");
  });

  it("커서를 주면 실린다", async () => {
    const calls = stubResponse(json({ data: { items: [] } }));

    await fetchChatHistory("eyJpIjo5MTIwNH0=", 50);

    expect(calls[0]?.url).toContain("cursor=eyJpIjo5MTIwNH0%3D");
    expect(calls[0]?.url).toContain("size=50");
  });
});

describe("전송", () => {
  it("본문에 content 만 싣는다", async () => {
    const calls = stubResponse(json({ data: {} }, 201));

    await sendChatMessage("지금 들어가는 거 아니지…");

    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({
      content: "지금 들어가는 거 아니지…",
    });
  });

  // 금칙어는 차단이 아니라 마스킹이다 (기획 12.2.2) — 보낸 문장이 아니라 **저장된
  // 문장**이 돌아온다. 화면은 그것을 그려야 한다.
  it("마스킹된 응답을 그대로 돌려준다", async () => {
    stubResponse(json({ data: { messageId: 1, content: "** 개폭락" } }, 201));

    await expect(sendChatMessage("ㅅㅂ 개폭락")).resolves.toMatchObject({
      content: "** 개폭락",
    });
  });

  it("429 는 Retry-After 의 초를 들고 나온다", async () => {
    stubResponse(
      new Response("", { status: 429, headers: { "Retry-After": "2" } })
    );

    await expect(sendChatMessage("연타")).rejects.toMatchObject({
      retryAfterSeconds: 2,
    });
  });

  // 헤더가 없어도 화면이 남은 시간을 말할 수 있어야 한다 — 계약의 쿨다운이 3초다.
  it("Retry-After 가 없으면 3초로 본다", async () => {
    stubResponse(new Response("", { status: 429 }));

    await expect(sendChatMessage("연타")).rejects.toMatchObject({
      retryAfterSeconds: 3,
    });
  });

  it("열람 전용 409 는 쿨다운이 아니다", async () => {
    stubResponse(json({ message: "열람 전용 시간입니다." }, 409));

    const failure = sendChatMessage("안녕");
    await expect(failure).rejects.toThrow("열람 전용 시간입니다.");
    await expect(failure).rejects.not.toBeInstanceOf(ChatCooldownError);
  });
});
