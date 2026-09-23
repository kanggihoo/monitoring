import type {
  APIRequest,
  Browser,
  BrowserContext,
  Page,
} from "@playwright/test";

import { type Api, api, cheapestKrwInstrument } from "./api";
import {
  expect,
  expectPriceLive,
  expectWsConnected,
  test as base,
  tickSeq,
  TOUR_SEEN,
} from "./fixtures";
import { SESSION, storageStatePath } from "./global-setup";

/**
 * 인증 스위트의 장치 (`docs/qa/user-checklist.md`).
 *
 * 미인증 스위트의 `guard` 를 **그대로 물려받는다** — "예상하지 않은 오류만 실패" 라는
 * 원칙이 인증 구간에서 달라질 이유가 없다.
 */

export const test = base.extend<{ app: Api }>({
  /**
   * 미인증 스위트의 `guard` 에 **인증 화면의 공통 예외 한 줄**을 더해 둔다.
   *
   * 로그인한 사람이 화면에 들어서면 부재 요약 모달이 뜨고, 그것을 닫는 순간
   * `POST /accounts/me/absence-summary/ack` 가 나간다. 서버는 본문 없이 `204` 로 답하는데
   * `apiFetch` 가 그 응답의 본문을 읽지 않아 크롬이 같은 요청을 `net::ERR_ABORTED` 로 한 번
   * 더 보고한다 (관심종목 해제·예약 취소의 `DELETE` 에서도 같은 모양이다). **요청은 성공했고
   * 값도 반영된다** — 모달이 다시 뜨지 않는 것이 그 증거다.
   *
   * 이 한 줄을 케이스마다 적지 않는 이유는 **모든 인증 케이스가 같은 문을 지나기 때문**이다.
   * 케이스별로 적으면 74곳에 같은 예외가 흩어진다. 경로·메서드·상태를 좁혀 두므로 다른
   * 끊긴 요청은 그대로 실패로 잡힌다.
   */
  guard: async ({ guard }, runTest) => {
    guard.allowResponse(
      {
        url: /\/api\/v1\/accounts\/me\/absence-summary\/ack$/,
        method: "POST",
        status: [0],
      },
      // **알림 센터를 여는 순간 같은 모양이 한 번 더 나온다.** 일괄 읽음도 본문 없는 `204`
      // 라 크롬이 스트림 취소로 기록한다 — 읽음 처리는 정상이고(배지가 0 이 된다) 관측만
      // 남는 노이즈다. 경로·메서드를 좁혀 두므로 다른 끊긴 요청은 그대로 잡힌다.
      {
        url: /\/api\/v1\/notifications\/read$/,
        method: "POST",
        status: [0],
      }
    );
    await runTest(guard);
  },

  // `use` 라는 이름은 린트가 React 훅으로 읽는다 (`rules-of-hooks`). 위치로 넘어가므로
  // 이름만 바꾼다 — 미인증 fixture 와 같은 이유다.
  app: async ({ request }, runTest) => {
    await runTest(api(request));
  },
});

export { expect, expectPriceLive, expectWsConnected, SESSION, tickSeq };

/** 기본 URL. `page` 없이 컨텍스트를 직접 열 때 쓴다. */
export const baseURL = () =>
  test.info().project.use.baseURL ?? "https://j15a605.p.ssafy.io:8443";

/**
 * 주 세션이 아닌 세션으로 브라우저 컨텍스트를 연다.
 *
 * **로그아웃·세션 만료·타인 계정은 주 세션으로 확인할 수 없다.** 같은 `storageState` 를
 * 두 컨텍스트에 물려도 서버 세션은 하나라, 한쪽을 죽이면 뒤 케이스가 전부 죽는다
 * (체크리스트 §세션은 두 개가 필요하다). `global-setup` 이 열 개를 따로 발급해 둔다.
 */
export async function openSession(
  browser: Browser,
  index: number
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL: baseURL(),
    storageState: storageStatePath(index),
  });
  // **여기도 투어를 꺼야 한다.** 이 컨텍스트는 fixture 의 `page` 가 아니라 케이스가 직접
  // 연 것이라 auto fixture 가 닿지 않는다. 첫 방문자에게 뜨는 둘러보기 모달이 화면을 덮어
  // 사용자 메뉴조차 눌리지 않는다.
  await context.addInitScript(TOUR_SEEN);
  return { context, page: await context.newPage() };
}

/** 같은 것을 API 로만 쓸 때. 화면이 필요 없는 준비·검증이 이쪽이다. */
export async function openSessionApi(request: APIRequest, index: number) {
  const context = await request.newContext({
    baseURL: baseURL(),
    storageState: storageStatePath(index),
    ignoreHTTPSErrors: true,
  });
  return { context, client: api(context) };
}

/**
 * 3~7장의 공통 전제. **거래 구간이 아니면 `test.skip(이유)` 로 `B` 를 남긴다** — 환경
 * 상태를 코드 결함으로 보고하지 않는다 (체크리스트 §수행 메모).
 */
export async function requireTrading(app: Api) {
  const clock = await app.clock();
  test.skip(
    clock.phase !== "TRADING",
    `거래 구간이 아니다 (phase=${clock.phase})`
  );
  return clock;
}

/**
 * 로그인한 사람이 화면에 들어설 때 맞는 **문(門) 모달**을 닫는다.
 *
 * 개장 브리핑과 부재 요약은 확인하고 들어가는 문이라 버튼이 하나다. 열려 있는 동안 배경은
 * `aria-hidden` 이 되고 — 그것이 올바른 동작이다 — **`getByRole` 질의가 전부 0 건이 된다.**
 * 미인증 스위트가 이 문제를 만나지 않는 이유는 두 모달이 로그인한 사람에게만 뜨기 때문이다.
 *
 * 재생이 빠르게 흐르므로 화면을 옮길 때마다 다시 뜰 수 있다. 그래서 `goto` 마다 부른다.
 */
export async function dismissEntryDialogs(page: Page) {
  for (let round = 0; round < 6; round += 1) {
    const enter = page
      .getByRole("alertdialog")
      .getByRole("button", { name: /^(확인|확인하고 입장)$/ });

    if (await enter.count()) {
      await enter.first().click();
      // **0 이 되기를 기다리지 않는다.** 둘이 함께 떠 있는 순간이 있어(부재 요약과 개장
      // 브리핑이 같은 틱에 온다) 하나를 닫아도 0 이 아니고, 그때 15초를 기다리다 죽었다.
      // 하나를 닫았으면 그것으로 이 회차는 끝이고, 남은 것은 다음 회차가 닫는다.
      await page.waitForTimeout(1_000);
      continue;
    }

    // **늦게 뜨는 것도 있다.** 브리핑은 시계가 구간을 넘는 순간에 오므로 화면이 다 그려진
    // 뒤에 나타날 수 있다. 한 번은 기다려 보고, 두 번 연속 비면 더 없는 것이다.
    if (round === 0) {
      await page.waitForTimeout(1_500);
      continue;
    }
    return;
  }
}

/** 화면을 열고 문 모달까지 치운다. 인증 스위트의 `page.goto` 는 전부 이쪽이다. */
export async function gotoApp(page: Page, route: string) {
  const response = await page.goto(route);
  await dismissEntryDialogs(page);
  return response;
}

/**
 * 거래에 쓸 원화 종목. **열린 것이 없으면 `test.skip`** 한다 — 종목별 휴장은 환경 상태이지
 * 코드 결함이 아니다 (`cheapestKrwInstrument` 의 주석).
 */
export async function requireKrwInstrument(app: Api, quantity = 10) {
  const pick = await cheapestKrwInstrument(app, quantity);
  test.skip(
    pick === null,
    `지금 시장가로 살 수 있는 원화 종목이 없다 (종목별 휴장)`
  );
  return pick!;
}

/** 화면의 `라벨 → 값` 한 쌍을 읽는다. `Stat`·`Metric` 이 둘 다 이 모양이다. */
export const statValue = (page: Page, label: string) =>
  page
    .getByText(label, { exact: true })
    .first()
    .locator("xpath=following-sibling::*[1]");

/** 이 스위트가 만든 것임을 알아볼 수 있게 하는 꼬리표. 공용 dev 서버라 필요하다. */
export const marker = (prefix: string) =>
  `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
