import WebSocket from "ws";

import { DENIED, expect, expectPriceLive, test } from "./fixtures";

/**
 * 미인증 권한 경계 (`docs/qa/guest-checklist.md` §4).
 *
 * **화면을 막는 것과 API 가 막히는 것은 다른 문제다.** UI 를 거치지 않는 직접 호출에서도
 * 거부되어야 하고, **거부 상태코드만으로는 부족하다** — 데이터가 바뀌지 않았는지까지 본다.
 */

const PERSONAL_READS = [
  "/api/v1/users/me",
  "/api/v1/accounts/me",
  "/api/v1/orders",
  "/api/v1/executions",
];

test.describe("4. 권한 경계 (UI 우회)", () => {
  /** 목은 세션 쿠키의 **존재만** 보지만 실서버는 값을 검증한다. 그 차이가 여기서 드러난다. */
  test("G-SEC-01 무효한 세션 쿠키로도 공개 화면은 열린다", async ({
    page,
    context,
    guard,
  }) => {
    guard.allowResponse({
      url: /\/api\/v1\/(users\/me|accounts|orders|executions)/,
      status: [401],
    });
    await context.addCookies([
      {
        name: "SESSION",
        value: "invalid-qa-session-value",
        url: test.info().project.use.baseURL!,
      },
    ]);

    await page.goto("/");

    await expect(page.getByRole("list", { name: "종목 목록" })).toBeVisible();
    await expectPriceLive(page);
    // 개인 영역은 로그인 안내로 갈린다 — 쿠키가 있다고 인증으로 취급하지 않는다.
    await expect(
      page.getByText("주문은 로그인 후에 넣을 수 있습니다")
    ).toBeVisible();
  });

  /** 쿠키 존재만으로 통과하면 1층(`proxy.ts`) 방어에만 의존한 것이다. */
  test("G-SEC-02 무효한 세션 쿠키로 보호 화면에 가면 로그인으로 간다", async ({
    page,
    context,
    guard,
  }) => {
    guard.allowResponse({ url: /\/api\/v1\//, status: [401] });
    await context.addCookies([
      {
        name: "SESSION",
        value: "invalid-qa-session-value",
        url: test.info().project.use.baseURL!,
      },
    ]);

    await page.goto("/mypage");

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText("총자산")).toHaveCount(0);
  });

  test("G-SEC-03 개인 조회 API 직접 호출이 거부된다", async ({ request }) => {
    for (const path of PERSONAL_READS) {
      const response = await request.get(path);

      expect(DENIED, `${path} 의 상태`).toContain(response.status());
      const body = await response.text();
      expect(body, `${path} 의 본문`).not.toContain("krwCash");
    }
  });

  /**
   * 미인증 연결은 열려 있다 (ADR-0005). **열린 것은 공개 구독까지다** — 개인 큐는 막혀야 한다.
   *
   * STOMP 프레임을 직접 만든다. 브라우저의 앱 코드는 애초에 개인 큐를 구독하지 않으므로
   * (`ws.ts` 의 주석) 화면으로는 이 방어선을 확인할 수 없다.
   */
  test("G-SEC-04 개인 큐 구독으로 개인 데이터가 오지 않는다", async () => {
    const base = new URL(test.info().project.use.baseURL!);
    const socket = new WebSocket(`wss://${base.host}/ws`);
    const frames: string[] = [];

    await new Promise<void>((resolve, reject) => {
      socket.on("open", resolve);
      socket.on("error", reject);
      setTimeout(() => reject(new Error("WS 연결 타임아웃")), 15_000);
    });
    socket.on("message", (data) => frames.push(data.toString()));

    const send = (frame: string) => socket.send(`${frame}\n\0`);
    send(`CONNECT\naccept-version:1.2\nhost:${base.host}\nheart-beat:0,0\n`);
    await new Promise((resolve) => setTimeout(resolve, 3_000));

    // 미인증 연결 자체는 성립해야 한다 (ADR-0005).
    expect(frames.join(""), "CONNECTED 프레임").toContain("CONNECTED");

    frames.length = 0;
    send(`SUBSCRIBE\nid:qa-personal\ndestination:/user/queue/me\n`);
    await new Promise((resolve) => setTimeout(resolve, 5_000));

    const received = frames.join("");
    // **개인 데이터가 배달되지 않는 것이 이 케이스의 본질이다.** 서버가 ERROR 로 끊든
    // 조용히 무시하든, MESSAGE 가 오지 않아야 한다.
    expect(received, `관측한 프레임: ${received.slice(0, 300)}`).not.toContain(
      "MESSAGE"
    );
    socket.close();
  });

  test("G-SEC-05 채팅 전송이 거부되고 메시지가 남지 않는다", async ({
    request,
  }) => {
    const marker = `qa-guest-${Date.now()}`;
    const before = await request.get("/api/v1/chat/messages?size=50");
    expect(before.status(), "채팅 조회는 공개다").toBe(200);

    const posted = await request.post("/api/v1/chat/messages", {
      data: { content: marker },
    });

    expect(DENIED, "전송 상태").toContain(posted.status());
    const after = await request.get("/api/v1/chat/messages?size=50");
    expect(await after.text(), "메시지 본문").not.toContain(marker);
  });

  test("G-SEC-06 관심종목 변경이 거부되고 반영되지 않는다", async ({
    request,
  }) => {
    const instruments = await request.get("/api/v1/market/instruments");
    const first = (await instruments.json()).data.instruments[0];

    const added = await request.put(
      `/api/v1/users/me/watchlist/${first.instrumentId}`
    );

    expect(DENIED, "변경 상태").toContain(added.status());
    const after = await request.get("/api/v1/market/instruments");
    const same = (await after.json()).data.instruments.find(
      (item: { instrumentId: number }) =>
        item.instrumentId === first.instrumentId
    );
    expect(same.watched, "watched 플래그").toBe(false);
  });
});
