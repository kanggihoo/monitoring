import { expect, test } from "@playwright/test";

/**
 * 독립 모드 — 기존 백엔드가 꺼져 있어도 마켓 화면이 뜬다.
 *
 * 보호 라우트가 현재 사용자 조회를 통과해야 렌더되므로, 이것이 없으면 목이 완벽해도 마켓
 * 화면이 로그인으로 튄다. `playwright.config.ts` 가 dev 서버와 목을 `MOCK_STANDALONE=1`
 * 로 띄운다.
 *
 * Spring 이 아예 떠 있지 않은 채로 도는 것이 이 파일의 힘이다 — 어느 경로든 원장을 잘못
 * 읽어 `BACKEND_ORIGIN` 으로 갔다면 연결 실패로 즉시 드러난다.
 */

const SESSION = {
  cookies: [
    {
      name: "SESSION",
      value: "e2e",
      domain: "localhost",
      path: "/",
      expires: -1,
      httpOnly: true,
      secure: false,
      sameSite: "Lax" as const,
    },
  ],
  origins: [],
};

test.describe("세션이 있을 때", () => {
  test.use({ storageState: SESSION });

  test("서버 컴포넌트와 브라우저가 같은 백엔드를 본다", async ({
    page,
    request,
  }) => {
    // 브라우저 경로 — 상대경로를 rewrite 가 잇는다.
    const listed = await request.get("/api/v1/market/instruments");
    expect(listed.ok()).toBe(true);
    const first = (await listed.json()).data.instruments[0].name as string;

    // 서버 경로 — 마켓 화면의 서버 컴포넌트가 readBackend → originFor 로 종목 메타를 받아
    // 그린다. 스크립트가 돌기 전의 HTML 이라 여기 이름이 있으면 SSR 이 만든 것이다.
    // 두 경로가 같은 이름을 보면 원장이 하나라는 뜻이다.
    const ssr = await (await request.get("/")).text();
    expect(ssr).toContain(first);

    // 그리고 브라우저가 실제로 받아 화면을 채운다. 현재가는 브라우저의 시세 조회
    // (`market-api.ts`)에서만 오므로, 채워졌다는 것이 그 경로가 살아 있다는 뜻이다.
    await page.goto("/");
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");
  });

  test("STOMP 연결이 확립된다", async ({ page }) => {
    await page.goto("/");

    // 연결까지만 본다. **Tick 도착은 `mock-tick.clock.spec.ts` 가 검증한다** — 배속 1 에서
    // 한 틱을 기다리면 최대 36초이고, 그 대기는 dev 서버 컴파일 시간과 겹쳐 불안정하다.
    // 시계를 조작해 결정론을 얻는 스펙은 워커 하나로 묶은 별도 프로젝트로 간다.
    await expect(page.getByTestId("ws-status")).toHaveAttribute(
      "data-ws-connected",
      "true"
    );
  });

  test("첫 조회가 위조 방지 토큰 쿠키를 심는다", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");

    const cookies = await page.context().cookies();
    const csrf = cookies.find((cookie) => cookie.name === "XSRF-TOKEN");

    expect(csrf).toBeDefined();
    // 스크립트가 읽어야 하므로 HttpOnly 가 아니다 — apiFetch 가 헤더로 돌려보낸다.
    expect(csrf?.httpOnly).toBe(false);
  });
});

// **이전 판은 같은 요청에 401 을 단언했다.** ADR-0005 가 그 결정을 뒤집었고, 목의 허용 목록이
// 백엔드 `SecurityConfig` 와 어긋나면 목에서는 통과하고 실서버에서 깨진다.
test("세션 쿠키가 없어도 공개 조회는 200 이다", async ({ request }) => {
  const response = await request.get("/api/v1/game/clock", {
    headers: { cookie: "" },
  });

  expect(response.status()).toBe(200);
});

test("세션 쿠키가 없으면 개인 조회는 401 이다", async ({ request }) => {
  const response = await request.get("/api/v1/rankings/me", {
    headers: { cookie: "" },
  });

  expect(response.status()).toBe(401);
  expect((await response.json()).message).toContain("로그인");
});

/**
 * **공개 조회의 401 을 세션 만료로 안내하지 않는다** (ADR-0005). 이전 판은 이 경로가 개인
 * 조회였으므로 "세션이 만료되었습니다" 를 단언했다. 이제 게임 시계는 공개이므로 그 401 은
 * 세션이 아니라 우리 쪽 경계 설정이 어긋난 것이고, 로그인하라고 안내하면 로그인해도
 * 고쳐지지 않는 것을 시키게 된다.
 */
test("공개 조회가 401 을 주면 세션 만료로 표시하지 않는다", async ({
  page,
}) => {
  await page.route("**/api/v1/game/clock", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ message: "로그인이 필요합니다." }),
    })
  );

  await page.goto("/");

  const toast = page.locator("[data-sonner-toast]");
  await expect(toast).toContainText("(401)");
  await expect(toast).not.toContainText("세션이 만료");
});

/**
 * 개인 조회의 401 은 세션이 끝났다는 뜻이다. 그 영역만 로그인 안내로 바뀌고 공개 목록은 남는다.
 */
test("개인 조회가 401 을 주면 그 영역만 로그인 안내가 된다", async ({
  page,
}) => {
  await page
    .context()
    .addCookies([
      { name: "SESSION", value: "e2e", domain: "localhost", path: "/" },
    ]);
  await page.route("**/api/v1/rankings/me", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ message: "로그인이 필요합니다." }),
    })
  );

  await page.goto("/ranking");

  await expect(page.getByText("내 순위는 로그인 후에 보입니다")).toBeVisible();
  // 공개 목록은 그대로다 — 개인 조회 하나가 실패해도 화면 전체가 죽지 않는다.
  await expect(page.getByText("순위", { exact: true })).toBeVisible();
});

test("위조 방지 토큰 없이 상태 변경은 403 이다", async ({ request }) => {
  const response = await request.patch("/api/v1/market/prices", {
    headers: { cookie: "SESSION=e2e" },
  });

  expect(response.status()).toBe(403);
  expect((await response.json()).message).toContain("위조 방지 토큰");
});
