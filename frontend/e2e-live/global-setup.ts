import { type FullConfig, request as newRequest } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

/**
 * 인증 스위트의 세션 준비 (`docs/qa/user-checklist.md` §세션 준비).
 *
 * **사람이 구글에 로그인하지 않는다.** 체크리스트가 적어 둔 대로
 * `TEST_SUPPORT_ENABLED=true` 가 켜지면 `k6-0001`~`k6-0010` 을 코드가 각각 발급할 수 있고,
 * 그러면 **세션 격리가 공짜가 된다** — 로그아웃·세션 만료·타인 계정처럼 세션을 소진하거나
 * 둘 이상을 요구하는 케이스가 `B` 로 남지 않는다.
 *
 * `save-auth.mjs` 경로는 없애지 않는다. 그쪽은 `TEST_SUPPORT_ENABLED` 가 꺼진 서버에서
 * 여전히 유일한 수단이다 — 여기서는 켜져 있을 때의 길만 쓴다.
 */

/** 세션을 적을 곳. `e2e/.auth` 와 나란히 `.gitignore` 에 있다. */
export const AUTH_DIR = path.join(__dirname, ".auth");

/** 발급해 둘 세션 수. Test User 시드가 `k6-0001`~`k6-0010` 열 개다. */
export const SESSION_COUNT = 10;

/** 케이스가 세션을 이름이 아니라 **쓰임**으로 집는다. 번호를 직접 쓰면 둘이 겹친다. */
export const SESSION = {
  /** 대부분의 케이스가 쓰는 주 세션. `user` 프로젝트의 `storageState` 다. */
  main: 1,
  /** `U-SES-04` 가 소진한다. */
  logout: 2,
  /** `U-SES-05` 가 소진한다. */
  logoutBroadcast: 3,
  /** `U-CON-09` 가 서버 세션만 죽인다. */
  expiry: 4,
  /** `U-WL-03` 의 계정 B. */
  otherUser: 5,
  /** `U-BUY-06` 전액 매수 전용. 주 계좌의 현금을 비우지 않으려고 따로 둔다. */
  spendAll: 6,
  /** `U-FX-06`·`U-FX-07` 전용. 달러 비율을 크게 흔든다. */
  exchange: 7,
  /** `U-CON-05` 두 탭 경합 전용. 양쪽이 전액 주문을 준비한다. */
  race: 8,
  /** `U-INQ-04` 가 볼 **남의** 문의를 쓰는 계정. */
  inquiryOwner: 9,
} as const;

export const storageStatePath = (index: number) =>
  path.join(AUTH_DIR, `user-${index}.json`);

/**
 * `X-Test-Support-Key` 를 실어 `k6-000N` 으로 로그인한다.
 *
 * `TEST_USER_OFFSET` 으로 번호를 밀 수 있다. **로컬 백엔드에서는 `k6-0001` 이 관리자로
 * 승격돼 있는 경우가 많고**, 그러면 주 세션이 `ADMIN` 이라 아래 전제 검사에 걸려 스위트가
 * 통째로 못 돈다. `TEST_USER_OFFSET=1` 이면 `k6-0002`~`k6-0011` 을 쓴다.
 */
const testUserName = (index: number) =>
  `k6-${String(index + Number(process.env.TEST_USER_OFFSET ?? 0)).padStart(4, "0")}`;

/**
 * 이번 실행이 고른 프로젝트. **`config.projects` 로는 알 수 없다** — Playwright 1.62 의
 * `FullConfig.projects` 는 `--project` 로 걸러지지 않은 전체 목록이라, 그것만 보면
 * `--project guest` 실행도 인증 스위트를 포함한다고 읽는다. 실제로 그랬고, 그래서 키가
 * 만료된 날 **미인증 스위트가 통째로 못 돌았다**(키 없이 돌아야 하는 쪽이다).
 *
 * @returns 고른 프로젝트 이름들. 아무것도 고르지 않았으면 빈 배열(= 전부)
 */
function selectedProjects(): string[] {
  const names: string[] = [];
  const argv = process.argv;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]!;
    if (arg.startsWith("--project="))
      names.push(arg.slice("--project=".length));
    else if (arg === "--project" && argv[index + 1])
      names.push(argv[index + 1]!);
  }
  return names;
}

export default async function globalSetup(config: FullConfig) {
  // **미인증만 돌릴 때는 키를 요구하지 않는다.** 고른 프로젝트에 `user` 가 없으면 발급할
  // 세션도 없다. `config.projects` 는 보조로만 쓴다 — 설정에 `user` 자체가 없는 경우다.
  const selected = selectedProjects();
  if (selected.length > 0 && !selected.includes("user")) return;
  if (!config.projects.some((project) => project.name === "user")) return;

  const baseURL =
    process.env.LIVE_BASE_URL ?? "https://j15a605.p.ssafy.io:8443";
  const apiKey = process.env.TEST_SUPPORT_API_KEY;

  if (!apiKey) {
    throw new Error(
      "TEST_SUPPORT_API_KEY 가 없습니다. `frontend/.env.local` 에 넣고 다시 실행하세요 " +
        "(`docs/qa/user-checklist.md` §세션 준비)."
    );
  }

  fs.mkdirSync(AUTH_DIR, { recursive: true });

  for (let index = 1; index <= SESSION_COUNT; index += 1) {
    const context = await newRequest.newContext({
      baseURL,
      ignoreHTTPSErrors: true,
    });

    const login = await context.post("/api/v1/test-support/login", {
      headers: { "X-Test-Support-Key": apiKey },
      data: { name: testUserName(index) },
    });
    if (!login.ok()) {
      throw new Error(
        `테스트 로그인 실패 (${testUserName(index)}): ${login.status()} ${await login.text()}. ` +
          "서버의 TEST_SUPPORT_ENABLED 와 키를 확인하세요."
      );
    }

    // **이 GET 이 없으면 모든 쓰기가 403 이다.** 백엔드는 쿠키 인증의 상태 변경 요청에
    // `X-XSRF-TOKEN` 을 요구하고(`SecurityConfig`), 그 토큰은 조회 한 번을 거쳐야
    // `XSRF-TOKEN` 쿠키로 내려온다. 저장하는 세션에 토큰까지 함께 담아 둔다.
    const me = await context.get("/api/v1/users/me");
    if (!me.ok()) {
      throw new Error(
        `세션이 유효하지 않습니다 (${testUserName(index)}): ${me.status()}`
      );
    }

    if (index === SESSION.main) {
      await assertPreconditions(context, me);
    }

    await context.storageState({ path: storageStatePath(index) });
    await context.dispose();
  }
}

/**
 * 체크리스트 §실행 전 확인. **`role === "USER"` 를 여기서 막지 않으면 10장이 전부 거짓
 * 통과한다** — 관리자 권한으로 돌린 권한 경계 케이스는 아무것도 증명하지 못한다.
 */
async function assertPreconditions(
  context: import("@playwright/test").APIRequestContext,
  me: import("@playwright/test").APIResponse
) {
  const role = (await me.json()).data?.role;
  if (role !== "USER") {
    throw new Error(
      `주 세션의 권한이 USER 가 아닙니다 (role=${role}). 관리자 권한으로 돌리면 ` +
        "10장 권한 경계가 전부 거짓 통과합니다."
    );
  }

  const account = await context.get("/api/v1/accounts/me");
  if (!account.ok()) {
    throw new Error(
      `시즌 계좌가 없습니다: ${account.status()} ${await account.text()}`
    );
  }

  // 거래 구간은 **중단 사유가 아니다.** 3~7장이 각자 `test.skip(이유)` 로 `B` 를 남긴다.
  const clock = await context.get("/api/v1/game/clock");
  if (!clock.ok()) {
    throw new Error(`재생 시계를 읽지 못했습니다: ${clock.status()}`);
  }
}
