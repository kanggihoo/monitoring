#!/usr/bin/env node
/**
 * 화면마다 로딩 수치와 네트워크 요약을 한 표로 뽑는다 (pnpm perf).
 * 개발자 도구 네트워크 탭을 화면 수만큼 여는 대신 쓴다.
 *
 * 사전 조건: **프로덕션 빌드**가 떠 있어야 하고 (`pnpm build && pnpm start`),
 * 로그인 세션이 저장돼 있어야 한다 (`pnpm test:e2e:auth`).
 *
 *   pnpm build && PORT=3100 pnpm start
 *   BASE_URL=http://localhost:3100 pnpm perf
 *
 * `next dev` 에 대고 재면 번들이 최적화돼 있지 않고 라우트를 처음 열 때 컴파일까지 하므로
 * 프로덕션과 무관한 숫자가 나온다. 그래서 개발 서버가 감지되면 경고를 낸다.
 */
import { chromium } from "@playwright/test";
import { readFileSync } from "node:fs";

import { ROUTES } from "./routes.mjs";

const BASE_URL = process.env.BASE_URL ?? "http://localhost:3000";

/**
 * 저장된 세션이 **이 주소에 쓸 수 있는 것일 때만** 붙인다.
 *
 * 쿠키는 호스트에 묶인다 — `localhost` 로 로그인해 저장한 세션을 배포 서버에 물리면 조용히
 * 미인증이 되고, 보호 화면이 전부 `/login` 으로 튕긴 채 측정된다. 그 숫자는 로그인 화면의
 * 것이지 그 화면의 것이 아니다.
 */
function sessionFor(baseUrl) {
  const host = new URL(baseUrl).hostname;
  try {
    const state = JSON.parse(readFileSync(SESSION_FILE, "utf8"));
    const usable = state.cookies?.some(
      (cookie) =>
        host === cookie.domain ||
        host.endsWith(cookie.domain.replace(/^\./, "."))
    );
    return usable ? SESSION_FILE : undefined;
  } catch {
    return undefined; // 세션을 저장한 적이 없다. 공개 화면만 의미 있는 수치가 된다.
  }
}

const SESSION_FILE = "e2e/.auth/user.json";
const session = sessionFor(BASE_URL);

/** 레이아웃 이동(CLS)은 늦게도 일어난다. 로드 뒤 이만큼 더 지켜본다. */
const SETTLE_MS = 600;

/**
 * LCP·CLS 는 navigation timing 에 없다. 페이지 스크립트보다 먼저 관찰자를 심어야
 * `buffered: true` 로 이미 지나간 항목까지 받을 수 있다.
 */
const OBSERVE = () => {
  window.__perf = { lcp: 0, cls: 0 };
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) window.__perf.lcp = entry.startTime;
  }).observe({ type: "largest-contentful-paint", buffered: true });
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      // 사용자 입력 직후의 이동은 의도된 것이라 CLS 에서 뺀다.
      if (!entry.hadRecentInput) window.__perf.cls += entry.value;
    }
  }).observe({ type: "layout-shift", buffered: true });
};

/** 브라우저 안에서 돈다. 수치의 출처는 전부 표준 Performance API 다. */
const COLLECT = () => {
  const nav = performance.getEntriesByType("navigation")[0];
  const resources = performance.getEntriesByType("resource");
  const bytes = (entries) =>
    entries.reduce((sum, entry) => sum + (entry.transferSize || 0), 0);
  const slowest = resources.reduce(
    (worst, entry) => (entry.duration > (worst?.duration ?? 0) ? entry : worst),
    null
  );

  return {
    ttfb: nav ? nav.responseStart - nav.requestStart : 0,
    dcl: nav ? nav.domContentLoadedEventEnd : 0,
    load: nav ? nav.loadEventEnd : 0,
    lcp: window.__perf.lcp,
    cls: window.__perf.cls,
    requests: resources.length,
    bytes: bytes(resources) + (nav?.transferSize ?? 0),
    slowest: slowest && {
      name: slowest.name.replace(location.origin, ""),
      duration: slowest.duration,
    },
    // 개발 서버는 이 모듈을 항상 보낸다. 프로덕션 빌드에는 없다.
    isDev: resources.some((entry) => entry.name.includes("react-refresh")),
  };
};

const ms = (value) => `${Math.round(value)}ms`;
const kb = (value) => `${(value / 1024).toFixed(0)}kB`;
const pad = (value, width) => String(value).padStart(width);
const padEnd = (value, width) => String(value).padEnd(width);

const browser = await chromium.launch();
const rows = [];
let devDetected = false;

for (const route of ROUTES) {
  // 화면마다 컨텍스트를 새로 판다. 하나를 공유하면 폰트·청크가 캐시돼 첫 화면만 무겁게
  // 나오고 나머지는 공짜로 보인다 — 화면끼리 비교가 안 된다. 여기 숫자는 전부 첫 방문이다.
  const context = await browser.newContext({
    baseURL: BASE_URL,
    storageState: session,
  });
  await context.addInitScript(OBSERVE);
  const page = await context.newPage();

  const failures = [];
  const onResponse = (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${new URL(response.url()).pathname}`);
  };
  const errors = [];
  const onConsole = (message) => {
    if (message.type() === "error") errors.push(message.text().slice(0, 80));
  };

  page.on("response", onResponse);
  page.on("console", onConsole);

  const response = await page.goto(route, { waitUntil: "load" });
  await page.waitForTimeout(SETTLE_MS);
  const metrics = await page.evaluate(COLLECT);
  // **착지한 곳이 요청한 곳인지 본다.** 미인증으로 보호 화면을 부르면 `/login` 이 200 으로
  // 열려서 상태코드만으로는 구분되지 않는다 — 그러면 로그인 화면의 수치가 그 화면의 것으로
  // 표에 앉는다.
  const landed = new URL(page.url()).pathname;
  await context.close();

  devDetected ||= metrics.isDev;
  rows.push({
    route,
    landed: landed === route ? null : landed,
    status: response?.status() ?? 0,
    ...metrics,
    failures,
    errors,
  });
}

await browser.close();

// ── 표 ────────────────────────────────────────────────────────────────
const W = Math.max(...ROUTES.map((r) => r.length), 5);
console.log(`\n${BASE_URL}\n`);
console.log(
  [
    padEnd("화면", W + 2),
    pad("TTFB", 7),
    pad("DCL", 7),
    pad("LCP", 7),
    pad("CLS", 6),
    pad("요청", 5),
    pad("전송", 7),
  ].join("  ")
);
console.log("─".repeat(W + 50));

for (const row of rows) {
  const broken = row.failures.length || row.errors.length;
  console.log(
    [
      padEnd((row.landed ? "→ " : broken ? "! " : "  ") + row.route, W + 2),
      pad(ms(row.ttfb), 7),
      pad(ms(row.dcl), 7),
      pad(ms(row.lcp), 7),
      pad(row.cls.toFixed(3), 6),
      pad(row.requests, 5),
      pad(kb(row.bytes), 7),
    ].join("  ")
  );
}

console.log("\n가장 느린 자원");
for (const row of rows) {
  if (row.slowest)
    console.log(
      `  ${padEnd(row.route, W)}  ${pad(ms(row.slowest.duration), 7)}  ${row.slowest.name}`
    );
}

const redirected = rows.filter((row) => row.landed);
if (redirected.length) {
  console.log(
    `\n→ 표시된 ${redirected.length}개 화면은 다른 곳에 착지했다. 그 화면의 수치가 아니다.`
  );
  for (const row of redirected)
    console.log(`  ${padEnd(row.route, W)} → ${row.landed}`);
  if (!session)
    console.log(
      `  (${BASE_URL} 에 쓸 수 있는 세션이 없다. BASE_URL=${BASE_URL} pnpm test:e2e:auth)`
    );
}

const failed = rows.filter((r) => r.failures.length || r.errors.length);
if (failed.length)
  console.log(
    `\n! 표시된 ${failed.length}개 화면은 오류 페이지를 잰 값이라 수치가 의미 없다.`
  );

const broken = rows.filter((r) => r.failures.length || r.errors.length);
if (broken.length) {
  console.log("\n실패한 요청 · 콘솔 오류");
  for (const row of broken) {
    for (const failure of row.failures)
      console.log(`  ${row.route}  ${failure}`);
    for (const error of row.errors)
      console.log(`  ${row.route}  [console] ${error}`);
  }
}

if (devDetected) {
  console.log(
    "\n⚠  개발 서버로 측정했다. 번들이 최적화돼 있지 않고 첫 요청에 컴파일이 섞여 있어" +
      "\n   프로덕션과 무관한 숫자다. `pnpm build && pnpm start` 에 대고 다시 잴 것."
  );
}
