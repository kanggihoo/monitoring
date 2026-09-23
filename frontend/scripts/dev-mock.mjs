#!/usr/bin/env node
/**
 * Spring 없이 화면 전체를 띄운다 (pnpm dev:mock).
 *
 * 기본 `pnpm dev` 는 **Spring 을 본다** (원장의 기본값). Spring 이 없거나, 있어도 데이터가
 * 비어 화면을 못 보는 경우 — 지금은 계좌 생성이 미구현이라 랭킹·복기·주문이 전부 비어 있다 —
 * 이 명령으로 목만 보고 돌린다.
 *
 * 두 프로세스를 띄운다. 목 서버(:8081)와 dev 서버(:3000)이며 **환경변수 셋이 양쪽에 같이**
 * 들어가야 한다:
 *
 * - `MOCK_STANDALONE=1` · `MOCK_MARKET=1` — 원장이 그 경로를 목으로 돌린다. 목 서버도 같은
 *   원장을 읽어 "이건 내 것" 을 판정하므로 **양쪽이 같은 값을 봐야 한다**.
 * - `NEXT_PUBLIC_WS_URL` — rewrite 는 업그레이드 요청을 넘기지 못해 브라우저가 목에 직결
 *   해야 한다. 기본값은 현재 origin(:3000)에서 유도되므로 여기서 지목한다.
 *
 * `playwright.config.ts` 의 webServer 둘과 같은 구성이다. 한쪽만 고치면 e2e 와 갈린다.
 *
 * **목이 답하지 않는 경로는 그대로 Spring 으로 간다** (원장의 캐치올). 채팅 히스토리·알림·
 * 공모 목록이 그렇고, Spring 이 없으면 그 화면만 빈다.
 */
import { spawn } from "node:child_process";

const MOCK_ENV = { MOCK_STANDALONE: "1", MOCK_MARKET: "1" };

/**
 * 목을 먼저 띄운다. dev 서버가 먼저 뜨면 첫 화면의 서버 렌더가 종목 목록을 찾다가 실패해
 * 빈 화면이 한 번 지나간다 (ADR-0005 의 공개 조회).
 *
 * 준비를 기다리지는 않는다 — 목은 1초 안에 뜨고, 늦어도 브라우저를 열 때쯤엔 살아 있다.
 * 기다리는 코드를 넣으면 이 스크립트가 헬스체크를 갖게 되고 그것을 또 관리해야 한다.
 */
const isWin = process.platform === "win32";

const children = [
  spawn("pnpm", ["mock"], {
    stdio: "inherit",
    shell: isWin,
    env: { ...process.env, ...MOCK_ENV },
  }),
  spawn("pnpm", ["exec", "next", "dev"], {
    stdio: "inherit",
    shell: isWin,
    env: {
      ...process.env,
      ...MOCK_ENV,
      NEXT_PUBLIC_WS_URL: "ws://localhost:8081/ws",
    },
  }),
];

/**
 * 하나가 죽으면 나머지도 데려간다. 목만 남으면 :8081 을 물고 있어 다음 실행이 **조용히**
 * 옛 목에 붙는다 — 그 목은 이번 환경변수를 모른다.
 */
let closing = false;
function closeAll(code) {
  if (closing) return;
  closing = true;
  for (const child of children) child.kill("SIGTERM");
  process.exit(code ?? 0);
}

for (const child of children) {
  child.on("exit", (code) => closeAll(code ?? 0));
}
// Ctrl+C 는 두 자식에게도 전달되지만, 목이 SIGINT 를 무시하는 경우까지 여기서 막는다.
process.on("SIGINT", () => closeAll(0));
process.on("SIGTERM", () => closeAll(0));
