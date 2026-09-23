#!/usr/bin/env node
/**
 * 로그인한 브라우저에서 세션 쿠키를 꺼내 Playwright storageState 로 저장한다.
 *
 * 사용법
 *   1. 로그인할 서버를 띄운다 (로컬이면 pnpm dev, 배포 서버면 BASE_URL=https://... )
 *   2. node scripts/save-auth.mjs  (pnpm test:e2e:auth) — Chrome 이 열리면 로그인
 *
 * 왜 Playwright 로 띄우지 않고 Chrome 을 직접 실행하는가: Playwright 가 띄운 Chrome 은
 * 자동화 표시가 붙어 Google 이 로그인을 거부한다("이 브라우저는 안전하지 않을 수 있습니다").
 * 평범하게 실행한 Chrome 에 디버깅 포트로 붙기만 하면 그 검사에 걸리지 않는다.
 * 이미 9222 포트를 연 Chrome 이 있으면 그것을 그대로 쓴다.
 */
import { chromium } from "@playwright/test";
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

const CDP = "http://127.0.0.1:9222";
const BASE_URL = process.env.BASE_URL ?? "http://localhost:3000";
/**
 * 저장할 곳. **관리자 세션은 여기를 갈아 끼워 받는다** — 테스트 로그인은 `USER` 만 발급하므로
 * `ADMIN` 세션을 얻는 길이 이 스크립트뿐이다 (`e2e-live/event.spec.ts`).
 *
 *   AUTH_OUT=e2e-live/.auth/admin.json BASE_URL=https://... pnpm test:e2e:auth
 */
const OUT = process.env.AUTH_OUT ?? "e2e/.auth/user.json";
/** 전용 프로필. 임시 디렉터리에 두면 지워져 매번 다시 로그인해야 한다. */
const PROFILE = join(homedir(), ".cache", "a605-e2e-chrome");

// Chrome 136+ 는 기본 프로필(평소 쓰는 그 프로필)로는 디버깅 포트 연결을 거부한다.
// 그래서 전용 프로필을 쓸 수밖에 없다 — 대신 위치를 고정해 한 번 로그인하면 남는다.
const CHROME = {
  darwin: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  win32: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  linux: "google-chrome",
}[process.platform];

/** 이미 붙을 수 있으면 그 Chrome 을, 아니면 새로 띄워서 붙는다. */
async function connect() {
  const existing = await chromium.connectOverCDP(CDP).catch(() => null);
  if (existing) return existing;

  console.log("Chrome 을 띄웁니다...");
  spawn(
    process.env.CHROME_PATH ?? CHROME,
    [
      `--remote-debugging-port=${new URL(CDP).port}`,
      `--user-data-dir=${PROFILE}`,
      `${BASE_URL}/login`,
    ],
    { detached: true, stdio: "ignore" }
  ).unref();

  for (let i = 0; i < 30; i++) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    const browser = await chromium.connectOverCDP(CDP).catch(() => null);
    if (browser) return browser;
  }
  throw new Error(
    `Chrome(${CDP})에 붙지 못했습니다. Chrome 경로가 다르면 CHROME_PATH 로 지정하세요.`
  );
}

const browser = await connect();
const context = browser.contexts()[0];

// 붙은 Chrome 에 열린 탭이 없으면(창을 닫았거나 남의 Chrome 을 재사용한 경우) 로그인
// 화면을 열어준다. 이 탭에서 로그인해야 여기서 쿠키를 볼 수 있다.
if (context.pages().length === 0) {
  await context.newPage().then((page) => page.goto(`${BASE_URL}/login`));
}

console.log(`로그인을 기다립니다 (${BASE_URL}, 최대 5분)...`);

const deadline = Date.now() + 300_000;
let ok = false;
let lastReport = "";
while (Date.now() < deadline) {
  // 쿠키 존재로 판단하면 안 된다 — 백엔드는 미인증 방문자에게도 SESSION 을 심는다.
  const response = await context.request
    .get(`${BASE_URL}/api/v1/users/me`)
    .catch((error) => error);
  if (response.status?.() === 200) {
    ok = true;
    break;
  }

  // 왜 안 끝나는지 보이게 한다. 같은 상태가 반복되면 한 번만 찍는다.
  const report = response.status?.()
    ? `아직 미인증 (${response.status()})`
    : `서버에 붙지 못했습니다 (${BASE_URL})`;
  if (report !== lastReport) {
    console.log(report);
    lastReport = report;
  }
  if (context.pages().length === 0) {
    console.error("붙어 있던 탭이 모두 닫혔습니다. 다시 실행하세요.");
    process.exit(1);
  }
  await new Promise((resolve) => setTimeout(resolve, 1_000));
}

if (!ok) {
  console.error("5분 안에 로그인이 끝나지 않았습니다.");
  process.exit(1);
}

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(await context.storageState(), null, 2));
console.log(`저장했습니다: ${OUT}`);
await browser.close(); // CDP 연결만 끊는다. Chrome 창은 남으니 직접 닫으면 된다.
