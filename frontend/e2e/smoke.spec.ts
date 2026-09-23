import { expect, test } from "@playwright/test";

// 설정이 실제로 동작하는지 확인하는 스모크. 백엔드 없이 next dev 와 목만 띄운다.
//
// **미인증 전제가 뒤집혔다** (ADR-0005). 이전 판은 세션 쿠키가 없으면 proxy 가 로그인 화면으로
// 보낸다고 단언했고, 그 단언이 "모든 화면이 로그인을 요구한다" 를 고정하고 있었다.
test("로그인하지 않아도 공개 화면에 진입한다", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveURL(/localhost:3000\/$/);
  // 첫 화면은 시세판이다. 종목 목록이 그려졌다는 것이 곧 공개 조회가 통과했다는 뜻이다.
  await expect(page.getByRole("list", { name: "종목 목록" })).toBeVisible();
  // 헤더로 좁힌다 — 개인 영역의 로그인 안내들이 같은 이름의 링크를 여럿 만든다.
  await expect(
    page.getByRole("banner").getByRole("link", { name: "로그인", exact: true })
  ).toBeVisible();
});

// 공개 경로만 열린다. 보호 경로는 종전대로 로그인 화면으로 간다.
test("로그인하지 않고 보호 화면에 들어가면 로그인 화면이 뜬다", async ({
  page,
}) => {
  await page.goto("/mypage");

  await expect(page).toHaveURL(/\/login$/);
  await expect(
    page.getByRole("link", { name: "Google로 로그인" })
  ).toBeVisible();
});
