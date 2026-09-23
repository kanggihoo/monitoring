import { expect, test as base } from "@playwright/test";

/**
 * 실서버 스위트의 공통 장치 (`docs/qa/`).
 *
 * **"예상하지 않은 오류만 실패"가 원칙이다.** 모든 오류를 실패로 보면 오프라인·권한 거부
 * 케이스가 자기 자신 때문에 실패한다. 그래서 케이스가 허용 목록을 **좁게** 선언하고
 * (`guard.allowResponse` · `guard.allowConsole`), 그 밖의 것만 모아 종료 시 실패시킨다.
 */

/** 허용할 응답 한 줄. 경로·메서드·상태코드를 모두 좁게 적는다. */
export type AllowedResponse = {
  url: RegExp;
  method?: string;
  status?: number[];
};

export type Guard = {
  allowResponse: (...rules: AllowedResponse[]) => void;
  allowConsole: (...patterns: RegExp[]) => void;
  /** 지금까지 모인 예상 밖 오류. 중간 확인이 필요한 케이스만 쓴다. */
  unexpected: string[];
};

/** 거부를 기대하는 케이스가 공통으로 쓰는 상태코드. */
export const DENIED = [401, 403];

/** 우리 서버가 아닌 곳. 여기로 나간 요청의 성패는 우리 화면의 건강이 아니다. */
const ANALYTICS_HOSTS =
  /^https:\/\/(?:www\.google-analytics\.com|www\.googletagmanager\.com|[a-z]\.clarity\.ms|www\.clarity\.ms)\//;

/**
 * 첫 방문자에게 저절로 뜨는 **둘러보기 모달을 끈다.** 그 모달은 화면 전체를 덮어
 * (`role="dialog"`, `aria-modal`) 아래에 있는 것을 누를 수 없게 만든다 — 실측으로
 * `G-MKT-05`·`G-PRV-02`·`G-PRV-03`·`G-LGN-01` 이 90초를 기다리다 죽었다.
 *
 * **케이스마다 닫지 않는다.** 모든 케이스가 같은 문을 지나고, 닫는 순서를 케이스가 알아야
 * 할 이유가 없다. 본 적 있는 사람으로 들어가면 아예 뜨지 않는다 (`src/lib/tour.ts` 가
 * `localStorage` 에 판 번호를 적는다). 투어 자체를 보는 케이스는 이 값을 지우고 연다.
 */
export const TOUR_SEEN = `try{localStorage.setItem("flashback.tour.market","1")}catch(e){}`;

export const test = base.extend<{ guard: Guard; tourSeen: void }>({
  // **fixture 의 `page` 에만 걸린다.** 케이스가 `openSession` 으로 직접 연 컨텍스트에는
  // 따로 걸어야 한다 (`user-fixtures.ts`) — 걸지 않으면 그 창에서 투어가 떠 사용자 메뉴가
  // 눌리지 않는다 (실측: `U-SES-04`·`U-SES-05` 가 그래서 통째로 멎었다).
  tourSeen: [
    async ({ page }, runTest) => {
      await page.addInitScript(TOUR_SEEN);
      await runTest();
    },
    { auto: true },
  ],

  // 두 번째 인자의 이름이 `use` 면 린트가 React 훅 호출로 읽는다 (`rules-of-hooks`).
  // Playwright 는 위치로 넘기므로 이름만 바꾸면 된다.
  guard: async ({ page }, runTest) => {
    const responses: AllowedResponse[] = [];
    const consoles: RegExp[] = [];
    const unexpected: string[] = [];

    const allowedResponse = (url: string, method: string, status: number) =>
      responses.some(
        (rule) =>
          rule.url.test(url) &&
          (!rule.method || rule.method === method) &&
          (!rule.status || rule.status.includes(status))
      );

    page.on("pageerror", (error) => {
      // 미처리 예외는 허용 목록이 없다. 화면이 죽는 것에 "예상한" 은 없다.
      unexpected.push(`pageerror: ${error.message}`);
    });

    page.on("console", (message) => {
      if (message.type() !== "error") return;
      const text = message.text();
      if (consoles.some((pattern) => pattern.test(text))) return;
      unexpected.push(`console.error: ${text}`);
    });

    page.on("response", (response) => {
      const status = response.status();
      // 403 도 잡는다 — 위조 방지 토큰 누락의 신호이고, 권한 경계 케이스는 자기가 기대하는
      // 403 만 허용 목록에 넣는다.
      if (status < 500 && status !== 403) return;
      const request = response.request();
      if (allowedResponse(response.url(), request.method(), status)) return;
      unexpected.push(`${status} ${request.method()} ${response.url()}`);
    });

    page.on("requestfailed", (request) => {
      // **응답이 아예 없는 실패는 response 핸들러에 잡히지 않는다.** 오프라인 케이스가
      // 이것을 허용 목록으로 넘긴다.
      //
      // 취소된 RSC 프리페치는 오류가 아니다. Next 라우터는 링크가 보일 때 다음 화면을 미리
      // 받아 두고 화면이 넘어가면 그것을 **취소한다** — `?_rsc=` 가 붙은 `ERR_ABORTED` 는
      // 그 정상 동작의 흔적이다. 이것을 실패로 보면 링크가 많은 화면일수록 더 자주 터진다.
      const aborted = request.failure()?.errorText === "net::ERR_ABORTED";
      if (aborted && /[?&]_rsc=/.test(request.url())) return;
      // **분석 비콘도 오류가 아니다.** GA·Clarity 는 화면을 떠나는 순간에도 이벤트를 쏘고,
      // 브라우저는 그 요청을 취소로 기록한다 — 우리 서버와 무관한 남의 집 요청이라
      // 성패가 화면의 건강을 말해 주지 않는다. 케이스마다 적지 않는 이유는 **모든 화면이
      // 같은 스크립트를 싣기 때문**이다 (`components/analytics.tsx`).
      if (aborted && ANALYTICS_HOSTS.test(request.url())) return;
      if (allowedResponse(request.url(), request.method(), 0)) return;
      unexpected.push(
        `requestfailed: ${request.method()} ${request.url()} (${request.failure()?.errorText})`
      );
    });

    await runTest({
      allowResponse: (...rules) => responses.push(...rules),
      allowConsole: (...patterns) => consoles.push(...patterns),
      unexpected,
    });

    expect(unexpected, "예상하지 않은 오류").toEqual([]);
  },
});

export { expect };

/** 재생 시계. 구간에 따라 건너뛸 케이스가 읽는다. */
export type Clock = {
  phase: "BRIEFING" | "TRADING" | "SETTLING" | "REVIEW_ONLY";
  tickSeq: number | null;
  nextTickAt: string | null;
  feeFree: boolean;
  leaderboardBlackout: boolean;
};

/**
 * WS 가 붙었는가. **텍스트가 아니라 속성으로 본다** — 헤더 배지를 걷으면서 `연결됨` 이라는
 * 글자가 사라졌고(`S15P21A605-259`), 지금 그 사실은 `data-ws-connected` 에만 있다
 * (`components/header-live.tsx`).
 */
export const expectWsConnected = (
  scope: import("@playwright/test").Page | import("@playwright/test").Locator,
  timeout = 30_000
) =>
  expect(scope.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-connected",
    "true",
    { timeout }
  );

/**
 * 지금 화면이 아는 Tick 번호. **텍스트가 아니라 속성이다** — `연결됨` 과 같은 이유로
 * (`S15P21A605-259`) 헤더에서 글자를 걷고 `data-ws-tick-seq` 만 남겼다. 아직 첫 틱이
 * 안 왔으면 빈 값이라 `0` 이다.
 */
export async function tickSeq(
  scope: import("@playwright/test").Page | import("@playwright/test").Locator
): Promise<number> {
  const value = await scope
    .getByTestId("ws-status")
    .getAttribute("data-ws-tick-seq");
  return Number(value ?? "") || 0;
}

/**
 * 현재가가 값으로 선다. **개장 전에는 `—` 가 정상이다** — 첫 가격은 1일차 개장에 서고
 * 그때까지 화면은 `개장 준비` 라고 적는다. 그 상태를 실패로 보고하면 시즌을 새로 적재한
 * 날마다 스위트가 통째로 빨개진다. 환경 상태이므로 `B` 로 남긴다.
 */
export async function expectPriceLive(page: import("@playwright/test").Page) {
  const clock = await readClock(page.request);
  test.skip(clock.tickSeq === null, "아직 첫 가격이 서지 않았다 (개장 준비)");
  await expect(page.getByTestId("selected-price")).not.toHaveText("—");
}

/**
 * 시계를 읽는다. **`page.request` 가 아니라 별도 요청이다** — 시계 조회가 `guard` 의 응답
 * 수집에 섞이면 케이스가 보는 화면의 요청과 구분되지 않는다.
 */
export async function readClock(
  request: import("@playwright/test").APIRequestContext
): Promise<Clock> {
  const response = await request.get("/api/v1/game/clock");
  expect(response.status(), "시계 조회").toBe(200);
  return (await response.json()).data as Clock;
}
