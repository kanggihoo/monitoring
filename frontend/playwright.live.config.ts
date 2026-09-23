import { defineConfig, devices } from "@playwright/test";

// `.env.local` 을 읽는다. `LIVE_BASE_URL` 과 **`TEST_SUPPORT_API_KEY`** 가 거기 있고,
// 뒤엣것은 테스트 로그인이 dev 에 켜지면 `globalSetup` 이 쓴다 (`docs/qa/user-checklist.md`).
//
// **키 이름을 새로 만들지 않는다.** `.env.example` 이 이미 그 이름으로 의미를 정의해 뒀고,
// 같은 것을 두 이름으로 부르면 어느 쪽이 진짜인지 알 수 없게 된다.
//
// `package.json` 의 `mock` 스크립트가 `--env-file-if-exists` 로 하는 일과 같다. 파일이
// 없어도 조용히 넘어가야 한다 — 이 스위트의 공개 화면 부분은 키 없이도 돌기 때문이다.
process.loadEnvFile?.(".env.local");

/**
 * **배포된 dev 서버를 그대로 두드리는 E2E.** 목도 dev 서버도 띄우지 않는다 — 이미 떠 있는
 * 것에 붙는다 (`docs/qa/`).
 *
 * `playwright.config.ts` 와 **별도 파일인 이유**는 그쪽이 `webServer` 로 목과 `next dev` 를
 * 기동하기 때문이다. 같은 설정에 프로젝트만 추가하면 실서버 검증에도 로컬 서버가 딸려 뜬다.
 * 그쪽의 `testDir` 은 `e2e` 라 이 디렉터리를 보지 않으므로 CI 는 지금 그대로 돈다.
 */
export default defineConfig({
  testDir: "e2e-live",
  testMatch: "**/*.spec.ts",

  // 인증 스위트의 세션을 여기서 발급한다 (`docs/qa/user-checklist.md` §세션 준비).
  // **미인증만 돌릴 때는 아무 일도 하지 않는다** — 그쪽은 키 없이도 돌아야 한다.
  globalSetup: "./e2e-live/global-setup.ts",

  // 공용 서버의 같은 계좌를 여러 워커가 만지면 **전후 차이 단언이 전부 무의미해진다.**
  // 미인증 스위트만 돌 때도 직렬로 둔다 — 재생 시계가 전역이라 틱 대기가 서로를 흔든다.
  workers: 1,
  fullyParallel: false,

  // 배포된 dev 서버를 그대로 두드린다 — 화면 하나가 뜨는 데 로컬보다 오래 걸리고, 인증
  // 화면은 계좌·리더보드·채팅이 함께 온다. 기본 30초로는 느린 것을 고장으로 읽는다.
  timeout: 90_000,

  // **재시도하지 않는다.** 쓰기 케이스에서 재시도는 주문을 한 번 더 실행하고, 정합성을 보는
  // 스위트에서 그것은 정합성을 깨는 도구다. 간헐 실패는 단언을 고쳐서 없앤다.
  retries: 0,

  reporter: [["list"], ["json", { outputFile: "test-results/live.json" }]],

  use: {
    baseURL: process.env.LIVE_BASE_URL ?? "https://j15a605.p.ssafy.io:8443",
    trace: "on",
    video: "retain-on-failure",
    screenshot: "only-on-failure",
  },

  projects: [
    {
      // 미인증. `storageState` 를 주지 않는 것이 곧 미인증이다 — 별도 준비가 없다.
      name: "guest",
      use: { ...devices["Desktop Chrome"] },
      testMatch: "**/public*.spec.ts",
    },
    {
      // 인증 사용자 (`role === "USER"`). `global-setup` 이 적어 둔 주 세션을 문다 —
      // 로그아웃·세션 만료처럼 세션을 소진하는 케이스는 각자 다른 세션을 따로 연다.
      name: "user",
      use: {
        ...devices["Desktop Chrome"],
        storageState: "e2e-live/.auth/user-1.json",
      },
      // `release` 는 이번 배포에서 바뀐 개인 화면, `daily-reset` 은 `origin/develop` 이후에
      // 들어온 일차 초기화·마감 보유, `event` 는 관리자 재생 조작으로만 볼 수 있는 구간이다.
      // 맨 뒤엣것은 `REPLAY_SEEK=1` 과 관리자 세션이 없으면 스스로 건너뛴다.
      testMatch:
        /(?:^|[\\/])(account|money|reserved|concurrency|feature|boundary|release|daily-reset|event)\.spec\.ts$/,
    },
  ],
});
