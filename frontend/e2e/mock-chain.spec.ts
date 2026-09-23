import { expect, test } from "@playwright/test";

/**
 * 목 체인 관통. 브라우저가 상대경로를 부르고 목이 답하는지, 그리고 rewrite 와 `originFor`
 * 가 같은 원장을 보는지 확인한다.
 *
 * 목은 세션 쿠키의 **존재만** 검사하므로 값은 아무거나 된다 — 실제 세션 검증은 Spring 의
 * 일이고 그것은 독립 모드가 꺼진 일상 개발에서 확인한다.
 */
test.use({
  storageState: {
    cookies: [
      {
        name: "SESSION",
        value: "e2e",
        domain: "localhost",
        path: "/",
        expires: -1,
        httpOnly: true,
        secure: false,
        sameSite: "Lax",
      },
    ],
    origins: [],
  },
});

test("브라우저의 상대경로 호출이 목에 닿는다", async ({ request }) => {
  const response = await request.get("/api/v1/game/clock");

  expect(response.ok()).toBe(true);
  const body = await response.json();

  // 기존 성공 봉투 그대로다 (`_common.md §3`).
  expect(body.success).toBe(true);
  expect(body.data).toMatchObject({
    seasonDay: 1,
    phase: expect.any(String),
    gameMonth: expect.any(String),
    tickSeq: expect.any(Number),
  });
  // 1일차 재생 구간은 2020.01~2022.03 이다.
  expect(body.data.gameMonth >= "2020-01").toBe(true);
  expect(body.data.gameMonth <= "2022-03").toBe(true);
});

test("원장에 없는 경로는 목이 소유하지 않는다", async ({ request }) => {
  // 목에 직결해서 확인한다. dev 서버를 거치면 rewrite 가 Spring 으로 넘기므로
  // Spring 이 내려간 상태에서는 원장 판정이 아니라 연결 실패를 보게 된다.
  const response = await request.get(
    "http://localhost:8081/api/v1/admin/users"
  );

  expect(response.status()).toBe(404);
  expect((await response.json()).message).toContain("원장");
});
