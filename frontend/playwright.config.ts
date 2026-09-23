import { defineConfig, devices } from "@playwright/test";

import { TOUR_VERSION } from "./src/lib/tour";

const baseURL = "http://localhost:3000";

export default defineConfig({
  testDir: "e2e",
  testMatch: "**/*.spec.ts",

  // dev 서버는 라우트를 처음 열 때 컴파일한다. 그 시간이 `expect` 기본 타임아웃(5초)과
  // 겹치면 첫 네비게이션이 간헐적으로 터진다 — CI 에서만 한 번 다시 돈다.
  // 로컬은 0 이다: 고쳐야 할 실패를 재시도가 가려 주면 안 된다.
  retries: process.env.CI ? 1 : 0,

  /**
   * 튜토리얼을 **본 것으로 두고 시작한다.** 처음 온 사람에게는 마켓 화면 위로 안내가 떠
   * 화면을 덮는데 (`market-tour.tsx`), 그러면 클릭이 전부 덮개에 막혀 미인증 스펙이
   * 통째로 무너진다. 튜토리얼 자체는 단위 테스트가 본다.
   *
   * 판이 올라가면 이 값도 함께 올라간다 — 여기에 숫자를 적어 두면 다음 판에서 조용히
   * 어긋난다.
   */
  use: {
    baseURL,
    trace: "on-first-retry",
    storageState: {
      cookies: [],
      origins: [
        {
          origin: baseURL,
          localStorage: [
            { name: "flashback.tour.market", value: String(TOUR_VERSION) },
          ],
        },
      ],
    },
  },
  projects: [
    // 크로스브라우저가 목적이 아니라 라우팅·렌더 검증이 목적이라 chromium 하나만 둔다.
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
      testIgnore: "**/*.clock.spec.ts",
    },

    // 목의 시계는 프로세스 전역이다. 되감기와 배속이 모든 접속에 적용되고 되감기는 계좌까지
    // 초기화하므로, 시계를 건드리는 두 테스트가 동시에 돌면 둘 다 틀린다.
    //
    // 그래서 시계를 조작하는 스펙만 **워커 하나**로 묶고 나머지는 병렬을 유지한다 —
    // 전체를 직렬화하면 관계없는 테스트까지 벌준다.
    //
    // 워커마다 목을 하나씩 띄우는 방법은 불가능하다: 포트가 워커마다 달라야 하는데
    // rewrite 의 destination 은 dev 서버 기동 시점에 고정된다.
    {
      name: "mock-clock",
      // **일반 스펙이 전부 끝난 뒤에 돈다.** `workers: 1` 은 시계 스펙끼리만 직렬화하고
      // 프로젝트 사이는 병렬로 남는데, 되감기가 목의 전역 계좌를 초기화하므로 그 순간
      // 로그인 상태를 보던 일반 스펙이 세션을 잃는다.
      dependencies: ["chromium"],
      use: { ...devices["Desktop Chrome"] },
      testMatch: "**/*.clock.spec.ts",
      workers: 1,
      fullyParallel: false,
    },
  ],
  webServer: [
    // **목이 먼저다.** 서버 목록은 순서대로 기동하며 각 `url` 이 응답할 때까지 기다리는데,
    // 공개 화면이 열린 뒤로는 첫 화면(`/`)의 렌더가 종목 목록 조회를 하므로 목이 없으면
    // dev 서버의 준비 검사 자체가 통과하지 못한다 (ADR-0005).
    {
      command: "pnpm mock",
      url: "http://localhost:8081/api/v1/game/clock",
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
      env: { MOCK_STANDALONE: "1", MOCK_MARKET: "1" },
    },
    {
      // 프로덕션 빌드 검증은 별도로 `pnpm build` 가 담당한다. 여기서 build 까지
      // 돌리면 e2e 한 번에 수십 초가 붙어 아무도 안 돌리게 된다.
      command: "pnpm dev",
      url: baseURL,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      // 독립 모드로 띄운다. Spring 이 내려가 있어도 마켓 화면이 렌더돼야 프론트 회귀를
      // 잡는다 — 보호 라우트가 현재 사용자 조회를 통과해야 하기 때문이다.
      //
      // WS 주소를 함께 준다. rewrite 는 업그레이드 요청을 넘기지 못하므로 브라우저가 목에
      // 직결해야 하는데, 기본값은 현재 origin(:3000) 에서 유도되기 때문이다.
      env: {
        MOCK_STANDALONE: "1",
        // 마켓 덩어리를 목으로 되돌린다. 기본은 Spring 이고(원장 규칙 2) e2e 는 Spring 을
        // 띄우지 않으므로, 목을 쓰는 이쪽이 명시한다.
        MOCK_MARKET: "1",
        NEXT_PUBLIC_WS_URL: "ws://localhost:8081/ws",
      },
    },
  ],
});
