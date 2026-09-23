import { expect, test } from "@playwright/test";

/**
 * 미인증 열람 (ADR-0005, `docs/specs/public-market-access.md`).
 *
 * 목 기반이다 — Spring 이 떠 있지 않아도 돌아야 하고, 두 탭 로그아웃은 **서버가 WS 를 끊어야**
 * 성립하므로 목의 종료 경로 위에서 확인한다.
 */

/** 목은 세션 쿠키의 **존재만** 본다. 값은 아무거나 되고, 지워지면 미인증이다. */
const sessionCookie = {
  name: "SESSION",
  value: "e2e",
  domain: "localhost",
  path: "/",
};

/**
 * 로그인 복귀 경로가 담기는 자리. `src/lib/login-return.ts` 의 키와 같은 문자열이며,
 * **브라우저에서 관측 가능한 것**이라 여기서 직접 읽고 쓴다.
 */
const RETURN_PATH_KEY = "loginReturnPath";

test.describe("미인증", () => {
  test("시세 화면에 진입해 WS 에 붙고 현재가를 받는다", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByTestId("selected-price")).not.toHaveText("—");
    // 미인증 핸드셰이크가 성립한다 — 이전 판은 401 로 끊겼다.
    await expect(page.getByTestId("ws-status")).toHaveAttribute(
      "data-ws-connected",
      "true"
    );
  });

  test("개인 진입점이 사라지지 않고 로그인 안내로 바뀐다", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");

    // 주문 — 읽는 영역은 그대로고 버튼 자리만 안내다.
    await expect(
      page.getByText("주문은 로그인 후에 넣을 수 있습니다")
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "매수" })).toHaveCount(0);

    // 채팅 — 읽기는 유지되고 입력창만 갈린다.
    await expect(page.getByRole("list", { name: "채팅" })).toBeVisible();
    await expect(
      page.getByRole("textbox", { name: "채팅 메시지" })
    ).toHaveCount(0);
    await expect(
      page.getByText("로그인하면 대화에 참여할 수 있습니다")
    ).toBeVisible();
  });

  test("관심종목 별을 누르면 로그인 안내가 뜨고 아무것도 담기지 않는다", async ({
    page,
  }) => {
    await page.goto("/");
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");

    const star = page
      .getByRole("button", { name: "관심 종목에 넣기 · 로그인 필요" })
      .first();
    await star.click();

    await expect(page.locator("[data-sonner-toast]")).toContainText(
      "관심 종목은 로그인 후에 담을 수 있습니다"
    );
    // 눌러도 담기지 않는다 — 로그인 전 행위를 실행하지 않는다.
    await expect(star).toHaveAttribute("aria-pressed", "false");
  });

  test("개인 메뉴를 누르면 화면을 잃지 않고 안내가 뜬다", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");

    await page.getByRole("link", { name: "내 계좌 · 로그인 필요" }).click();

    // **화면이 넘어가지 않는다.** 보던 시세판이 뒤에 그대로 남는다.
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole("alertdialog")).toContainText(
      "내 계좌는 로그인해야 볼 수 있는 메뉴입니다"
    );
    // 뒤 화면은 DOM 으로 본다. 열린 모달이 배경을 접근성상 비활성으로 만드는 것은 올바른
    // 동작이라 역할(role) 질의로는 잡히지 않는다 — 닫은 뒤를 보는 것은 다음 테스트가 맡는다.
    await expect(page.locator('ul[aria-label="종목 목록"]')).toBeAttached();
    // 열기만 해서는 담기지 않는다 — 그냥 닫고 떠난 사람의 경로가 남으면 안 된다.
    expect(
      await page.evaluate(() => sessionStorage.getItem("loginReturnPath"))
    ).toBeNull();
  });

  test("안내를 닫으면 보던 화면이 그대로 남는다", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "내 계좌 · 로그인 필요" }).click();

    await page.getByRole("button", { name: "계속 둘러보기" }).click();

    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");
  });

  /**
   * 주소를 직접 치는 경로는 모달이 낄 자리가 없어 여전히 `/login` 에 착지한다. 그때 담기는
   * 취소 경로가 보호 화면이면 돌아가기가 제자리를 맴돌므로, 그 값이 갈려 있는지 본다.
   */
  test("헤더 로그인으로 온 로그인 화면은 보던 화면으로 돌아간다", async ({
    page,
  }) => {
    await page.goto("/");
    await page
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
      .click();
    await expect(page).toHaveURL(/\/login$/);

    await page.getByRole("link", { name: "로그인하지 않고 돌아가기" }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole("list", { name: "종목 목록" })).toBeVisible();
  });

  test("공개 화면에서 로그인을 누르면 복귀 경로가 보던 화면이다", async ({
    page,
  }) => {
    await page.goto("/");
    await page
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
      .click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole("link", { name: "로그인하지 않고 돌아가기" })
    ).toHaveAttribute("href", "/");
  });

  /**
   * 수용 기준 15 — 보던 **종목**까지 돌아온다. 선택이 주소에 남지 않으면 로그인 뒤 첫 종목으로
   * 리셋된다.
   */
  test("종목을 고르면 주소에 남아 복귀 경로가 그 종목을 담는다", async ({
    page,
  }) => {
    await page.goto("/");
    await expect(page.getByTestId("selected-price")).not.toHaveText("—");

    // 목록의 두 번째 줄을 고른다 — 첫 줄은 기본 선택이라 변화를 볼 수 없다. 줄마다 별 버튼과
    // 종목 버튼이 있어 두 번째 버튼이 종목이다.
    await page
      .getByRole("list", { name: "종목 목록" })
      .locator("li")
      .nth(1)
      .getByRole("button")
      .nth(1)
      .click();

    await expect(page).toHaveURL(/\/\?instrumentId=\d+$/);
    const picked = new URL(page.url()).searchParams.get("instrumentId");

    await page
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
      .click();

    await expect(
      page.getByRole("link", { name: "로그인하지 않고 돌아가기" })
    ).toHaveAttribute("href", `/?instrumentId=${picked}`);
  });
});

test("로그인이 끝나면 보던 화면으로 돌아간다", async ({ page, context }) => {
  // 로그인을 시작한 상태를 재현한다 — 백엔드는 고정 주소로 복귀시키고 그 다음 한 걸음을
  // 프론트가 놓는다. 여기서 보는 것은 그 한 걸음이다.
  await context.addCookies([sessionCookie]);
  await page.addInitScript(([key, path]) => sessionStorage.setItem(key, path), [
    RETURN_PATH_KEY,
    "/?instrumentId=3",
  ] as const);

  await page.goto("/");

  await expect(page).toHaveURL(/\/\?instrumentId=3$/);
  // 한 번 쓰면 지워진다 — 다음 로그인이 엉뚱한 화면으로 가지 않는다.
  expect(
    await page.evaluate((key) => sessionStorage.getItem(key), RETURN_PATH_KEY)
  ).toBeNull();
});

/**
 * 한 탭에서 로그아웃하면 다른 탭도 미인증이 된다. **서버가 WS 를 끊는 것이 그 신호를 겸한다** —
 * 탭 간 전파를 위한 별도 채널을 두지 않는다 (ADR-0005).
 */
test("두 탭에서 로그인한 뒤 한 탭에서 로그아웃하면 다른 탭도 미인증이 된다", async ({
  context,
}) => {
  await context.addCookies([sessionCookie]);
  const first = await context.newPage();
  const second = await context.newPage();
  await first.goto("/");
  await second.goto("/");
  await expect(first.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-connected",
    "true"
  );
  await expect(second.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-connected",
    "true"
  );
  await expect(
    second.getByRole("button", { name: /목플레이어/ })
  ).toBeVisible();

  await first.getByRole("button", { name: /목플레이어/ }).click();
  await first.getByRole("menuitem", { name: "로그아웃" }).click();

  // 다른 탭이 종료 사유를 받고 서버 재조회로 미인증으로 전환된다.
  await expect(
    second
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
  ).toBeVisible();
  await expect(
    second.getByText("내 자산과 순위는 로그인 후에 보입니다")
  ).toBeVisible();
  // 공개 열람은 계속된다 — 로그아웃이 화면을 빼앗지 않는다.
  await expect(second).toHaveURL(/\/$/);
  await expect(second.getByTestId("selected-price")).not.toHaveText("—");
});
