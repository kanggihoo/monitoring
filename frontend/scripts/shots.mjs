#!/usr/bin/env node
/**
 * 주요 화면을 전부 캡처한다 (pnpm shots). 디자인을 눈으로 보고 고치기 위한 것이라
 * 비교·검증은 하지 않는다 — 그냥 최신 화면을 design-review/shots/ 에 덮어쓴다.
 *
 * 사용법:
 *   pnpm shots              기본: 로컬(localhost:3000) Desktop + Light 만 (가장 빠름)
 *   pnpm shots --all        전체: Desktop + Mobile, Light + Dark 모두 캡처
 *   pnpm shots --mobile     Mobile 뷰포트 포함
 *   pnpm shots --dark       Dark 모드 포함
 *   pnpm shots:dev          배포된 dev 서버를 대상으로 4종(웹·모바일 × 라이트·다크) 전부
 *
 * 세션을 얻는 길이 둘이다. 순서대로 시도한다:
 *   ① TEST_SUPPORT_API_KEY 가 있고 대상 서버에 테스트 지원이 켜져 있으면 k6-0001 로
 *      바로 로그인한다 (playwright.live.config.ts 의 globalSetup 과 같은 경로).
 *   ② 없으면 e2e/.auth/user.json (pnpm test:e2e:auth 로 사람이 직접 로그인해 만든 것).
 * 둘 다 없으면 미인증으로 찍는다 — 로그인 화면과 공개 화면만 의미가 있다.
 */
import { chromium, request as newRequest } from "@playwright/test";
import { existsSync, mkdirSync } from "node:fs";

import { ROUTES } from "./routes.mjs";

// TEST_SUPPORT_API_KEY 와 LIVE_BASE_URL 이 여기 있다. 없어도 조용히 넘어간다.
process.loadEnvFile?.(".env.local");

const args = process.argv.slice(2);
const has = (flag, env) => args.includes(flag) || process.env[env] === "1";

const isLive = has("--live", "SHOTS_LIVE") || has("--dev", "SHOTS_LIVE");
const hasAll = has("--all", "SHOTS_ALL");
const hasMobile = hasAll || has("--mobile", "SHOTS_MOBILE");
const hasDark = hasAll || has("--dark", "SHOTS_DARK");

const LIVE_URL = process.env.LIVE_BASE_URL ?? "https://j15a605.p.ssafy.io:8443";
const BASE_URL =
  process.env.BASE_URL ?? (isLive ? LIVE_URL : "http://localhost:3000");
const OUT =
  process.env.OUT ?? (isLive ? "design-review/dev" : "design-review/shots");

/** 배포 서버는 사설 인증서라 그냥 붙으면 막힌다. 로컬에서는 해가 없다. */
const ignoreHTTPSErrors = true;

const ALL_VIEWPORTS = {
  desktop: { name: "desktop", width: 1440, height: 900 },
  mobile: { name: "mobile", width: 390, height: 844 },
};

// 기본: Desktop, 플래그 지정 시 Mobile 추가
const VIEWPORTS = [
  ALL_VIEWPORTS.desktop,
  ...(hasMobile ? [ALL_VIEWPORTS.mobile] : []),
];

// 기본: Light, 플래그 지정 시 Dark 추가
const SCHEMES = ["light", ...(hasDark ? ["dark"] : [])];

/**
 * 테스트 지원 로그인으로 storageState 를 뽑는다. 키가 없거나 대상 서버가 그 경로를 닫아
 * 두었으면 **던지지 않고 null** 을 준다 — 캡처는 미인증으로라도 돌아야 한다.
 */
let role = null;

async function testSupportState(baseURL) {
  const apiKey = process.env.TEST_SUPPORT_API_KEY;
  if (!apiKey) return null;

  const context = await newRequest.newContext({ baseURL, ignoreHTTPSErrors });
  try {
    const login = await context.post("/api/v1/test-support/login", {
      headers: { "X-Test-Support-Key": apiKey },
      data: { name: process.env.SHOTS_TEST_USER ?? "k6-0001" },
    });
    if (!login.ok()) {
      console.warn(
        `테스트 로그인 실패 (${login.status()}) — 미인증으로 찍습니다.`
      );
      return null;
    }
    // 이 GET 이 XSRF 토큰까지 쿠키에 채운다 (global-setup.ts 의 같은 주석).
    const me = await context.get("/api/v1/users/me");
    if (!me.ok()) {
      console.warn(
        `세션이 유효하지 않습니다 (${me.status()}) — 미인증으로 찍습니다.`
      );
      return null;
    }
    const user = (await me.json()).data;
    console.log(
      `세션: test-support (${user?.nickname ?? "?"} · ${user?.role})`
    );
    role = user?.role;
    return await context.storageState();
  } finally {
    await context.dispose();
  }
}

const fileState = existsSync("e2e/.auth/user.json")
  ? "e2e/.auth/user.json"
  : undefined;
const storageState = (await testSupportState(BASE_URL)) ?? fileState;
if (!storageState)
  console.warn("세션이 없습니다 — 미인증 화면만 의미가 있습니다.");

mkdirSync(OUT, { recursive: true });
console.log(`${BASE_URL} → ${OUT}`);

/** 세션이 있으면 `/login` 은 홈으로 튕긴다. 그 한 장만 미인증으로 따로 찍는다. */
const GUEST_ROUTES = ["/login"];

// **권한이 없으면 관리자 화면은 404 다.** 그 404 를 찍어 두면 다음 비교에서 "관리자 화면이
// 이렇게 생겼다" 로 읽힌다 — 찍지 않고 왜 빠졌는지 말하는 쪽이 낫다.
// 관리자 화면까지 담으려면 관리자 계정으로 `pnpm test:e2e:auth` 를 한 번 돌린다.
const canAdmin = role === "ADMIN" || !!fileState;
const AUTH_ROUTES = ROUTES.filter(
  (route) =>
    !(storageState && GUEST_ROUTES.includes(route)) &&
    (canAdmin || !route.startsWith("/admin"))
);
if (!canAdmin) {
  console.warn(
    "관리자 권한이 없어 /admin/* 는 건너뜁니다 (관리자 계정으로 `pnpm test:e2e:auth` 후 다시 실행)."
  );
}

const browser = await chromium.launch();

/**
 * 한 (테마 × 뷰포트 × 세션) 조합으로 주어진 화면을 전부 찍는다.
 * @param session storageState 또는 undefined(미인증)
 */
async function capture(scheme, viewport, session, routes) {
  if (routes.length === 0) return;

  const context = await browser.newContext({
    baseURL: BASE_URL,
    viewport: { width: viewport.width, height: viewport.height },
    colorScheme: scheme,
    ignoreHTTPSErrors,
    storageState: session,
  });

  // colorScheme 만으로도 다크가 걸리지만(globals.css 가 미디어 쿼리도 본다) 저장된
  // 선택이 있으면 그쪽이 이긴다. 첫 페인트 전에 같은 값을 박아 둘을 일치시킨다.
  // 튜토리얼은 처음 온 사람에게 저절로 떠서 화면을 덮으므로 본 것으로 표시한다.
  await context.addInitScript(
    ([theme, tourKey]) => {
      try {
        localStorage.setItem("theme", theme);
        localStorage.setItem(tourKey, "99");
      } catch {}
    },
    [scheme, "flashback.tour.market"]
  );

  const page = await context.newPage();
  const suffix = scheme === "dark" ? ".dark" : "";

  for (const route of routes) {
    const response = await page.goto(route, { waitUntil: "domcontentloaded" });
    // WS·폴링이 있는 화면은 networkidle 이 영영 오지 않는다. 기다려 보되 붙잡히지 않는다.
    await page
      .waitForLoadState("networkidle", { timeout: 8_000 })
      .catch(() => {});
    await page.waitForTimeout(Number(process.env.SHOTS_SETTLE ?? 1_500));

    await dismissDialog(page);

    // next dev 의 개발 도구 배지가 좌하단을 가린다.
    await page.addStyleTag({ content: "nextjs-portal { display: none }" });
    const name = route === "/" ? "home" : route.slice(1).replaceAll("/", "-");
    const file = `${name}.${viewport.name}${suffix}.png`;
    await page.screenshot({ path: `${OUT}/${file}`, fullPage: true });
    console.log(`${file}  ${response?.status()} ${page.url()}`);
  }

  await context.close();
}

/**
 * 개장 브리핑·부재 요약이 떠 있으면 화면 대신 모달이 찍힌다. 세션마다 한 번씩 뜨므로
 * 기준 이미지에서는 걷어낸다 — 두 모달 모두 **버튼이 하나**이고 그것이 곧 확인이다.
 * 확인은 서버에 기록되므로 같은 세션에서 다시 뜨지 않는다.
 */
async function dismissDialog(page) {
  const dialog = page.locator('[role="dialog"], [role="alertdialog"]').first();
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (!(await dialog.isVisible().catch(() => false))) return;

    const button = dialog.locator("button:visible").last();
    await button
      .click({ timeout: 2_000 })
      .catch(() => page.keyboard.press("Escape"));
    await page.waitForTimeout(600);
  }
}

for (const scheme of SCHEMES) {
  for (const viewport of VIEWPORTS) {
    await capture(scheme, viewport, storageState, AUTH_ROUTES);
    if (storageState) await capture(scheme, viewport, undefined, GUEST_ROUTES);
  }
}

await browser.close();
