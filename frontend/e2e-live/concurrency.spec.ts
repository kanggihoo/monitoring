import type { Page } from "@playwright/test";

import {
  availableKrw,
  cancelOpenOrders,
  cheapestKrwInstrument,
  expectAmount,
  liquidateKrwPositions,
} from "./api";
import { diff, gte, orderAmount, parseMoney, scaled, unscaled } from "./money";
import {
  expect,
  expectPriceLive,
  gotoApp,
  openSession,
  openSessionApi,
  requireKrwInstrument,
  requireTrading,
  SESSION,
  statValue,
  test,
} from "./user-fixtures";

/**
 * 7. 데이터 정합성·중복 조작 (`docs/qa/user-checklist.md`).
 *
 * 가드는 코드에 있다(`order-ticket` 의 `submitting`). **있는 것과 동작하는 것은 다르다** —
 * 그래서 응답을 붙잡아 둔 채로 연타한다. 평범한 두 번의 클릭으로는 이 검증이 안 된다:
 * Playwright 가 actionability 를 기다리므로 버튼이 다시 살아난 뒤에 두 번째를 눌러
 * "정상적인 두 번의 주문" 이 되어 버린다.
 */

const QUANTITY = 10;
const HOLD_MS = 1_500;

/** 이 장이 만든 미체결 예약. */
let created: number[] = [];

test.beforeEach(() => {
  created = [];
});

test.afterEach(async ({ app }) => {
  await cancelOpenOrders(app, created);
});

/** 지정한 경로의 응답을 붙잡아 둔다. 그동안 화면은 `submitting` 이어야 한다. */
async function hold(page: Page, pattern: string, method: string) {
  await page.route(pattern, async (route) => {
    if (route.request().method() !== method) return route.fallback();
    await new Promise((resolve) => setTimeout(resolve, HOLD_MS));
    await route.continue();
  });
}

/** 나간 요청을 센다. 화면이 무엇을 막았는지는 **요청 수**가 말한다. */
function countRequests(
  page: Page,
  test: (url: string, method: string) => boolean
) {
  const seen: string[] = [];
  page.on("request", (request) => {
    if (test(request.url(), request.method())) seen.push(request.url());
  });
  return seen;
}

const isOrderPost = (url: string, method: string) =>
  method === "POST" && /\/api\/v1\/orders(\?|$)/.test(url);

const isExchangePost = (url: string, method: string) =>
  method === "POST" && /\/api\/v1\/accounts\/me\/exchanges(\?|$)/.test(url);

test.describe("7. 데이터 정합성·중복 조작", () => {
  test("U-CON-01 요청 진행 중 추가 클릭이 무시된다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );
    const before = (await app.executions({ size: 1 })).totalElements;

    const posted = countRequests(page, isOrderPost);
    await hold(page, "**/api/v1/orders", "POST");
    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    await page.getByLabel("수량").fill(String(QUANTITY));

    const buy = page.getByRole("button", { name: "매수 주문", exact: true });
    await expect(buy).toBeEnabled();
    await buy.click({ noWaitAfter: true });
    // **붙잡힌 동안** 두 번 더 누른다. `force` 가 actionability 대기를 건너뛴다.
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await buy.click({ force: true, noWaitAfter: true });
    }
    await page.waitForTimeout(HOLD_MS + 2_000);

    expect(posted, "나간 주문 요청").toHaveLength(1);
    expect((await app.executions({ size: 1 })).totalElements, "체결 건수").toBe(
      before + 1
    );
  });

  /** U-CON-01 이 본 검증이고 이쪽은 보조다. 지연 없이 눌러도 한 건이어야 한다. */
  test("U-CON-02 빠른 연타에도 한 건만 들어간다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );
    const before = (await app.executions({ size: 1 })).totalElements;
    const beforeCash = availableKrw(account);

    const posted = countRequests(page, isOrderPost);
    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    await page.getByLabel("수량").fill(String(QUANTITY));
    const buy = page.getByRole("button", { name: "매수 주문", exact: true });
    await expect(buy).toBeEnabled();

    await Promise.all([
      buy.click({ force: true, noWaitAfter: true }),
      buy.click({ force: true, noWaitAfter: true }),
    ]);
    await page.waitForTimeout(3_000);

    expect(posted.length, "나간 주문 요청").toBe(1);
    expect((await app.executions({ size: 1 })).totalElements, "체결 건수").toBe(
      before + 1
    );
    // 현금 감소분도 1회분이어야 한다.
    const execution = (await app.executions({ size: 1 })).content[0]!;
    expectAmount(
      diff(beforeCash, availableKrw(await app.account()), 0),
      unscaled(scaled(execution.amount, 0) + scaled(execution.fee, 0), 0),
      "KRW",
      "현금 감소분은 1회분"
    );
  });

  test("U-CON-03 요청 진행 중 추가 환전 클릭이 무시된다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const send = "100000";
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), send, 0),
      `원화 현금이 ${send} 에 못 미친다`
    );
    const before = (await app.exchangeHistory(1)).totalElements;

    const posted = countRequests(page, isExchangePost);
    await hold(page, "**/api/v1/accounts/me/exchanges", "POST");
    await gotoApp(page, "/mypage");
    await page.getByLabel(/보낼 금액/).fill(send);

    const button = page.getByRole("button", { name: "환전하기" });
    await expect(button).toBeEnabled({ timeout: 15_000 });
    await button.click({ noWaitAfter: true });
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await button.click({ force: true, noWaitAfter: true });
    }
    await page.waitForTimeout(HOLD_MS + 2_000);

    expect(posted, "나간 환전 요청").toHaveLength(1);
    expect((await app.exchangeHistory(1)).totalElements, "환전 이력 건수").toBe(
      before + 1
    );
    expectAmount(
      diff(account.krwCash, (await app.account()).krwCash, 0),
      send,
      "KRW",
      "원화 감소분은 1회분"
    );
  });

  /** **에러 바운더리로 넘어가지 않는다.** 두 번째는 무시되거나 안내로 끝나야 한다. */
  test("U-CON-04 취소 연타가 화면을 깨지 않는다", async ({
    app,
    page,
    guard,
  }) => {
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const limitPrice = unscaled(
      (scaled(preview.referencePrice, instrument.priceScale) * BigInt(60)) /
        BigInt(100),
      instrument.priceScale
    );
    const needed = orderAmount(
      limitPrice,
      QUANTITY,
      instrument.priceScale,
      "KRW"
    );
    const beforeCash = availableKrw(await app.account());
    test.skip(!gte(beforeCash, needed, 0), "예약을 걸 원화 현금이 없다");

    const placed = await app.placeOrder({
      orderType: "LIMIT",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: QUANTITY,
      limitPrice,
    });
    created.push(placed.order!.orderId);

    // 두 번째 취소는 이미 사라진 예약을 지우려는 것이라 409·404 가 정상이다.
    //
    // **실측:** 성공한 취소(`204`)도 크롬이 같은 요청을 `net::ERR_ABORTED` 로 한 번 더
    // 보고한다 — `apiFetch` 가 본문 없는 응답의 스트림을 읽지 않아서다. 관심종목 해제에서도
    // 같은 모양이 나온다. 예약은 정상 취소되고 잠금도 돌아오므로 기능 결함은 아니지만,
    // 이것을 실패로 두면 취소하는 케이스가 자기 자신 때문에 실패한다. `status: [0]` 이
    // `requestfailed` 한 줄을 가리킨다.
    guard.allowResponse({
      url: /\/api\/v1\/orders\/\d+$/,
      method: "DELETE",
      status: [0, 403, 404, 409],
    });
    guard.allowConsole(/취소|409|404/);

    await hold(page, "**/api/v1/orders/*", "DELETE");
    await gotoApp(page, "/mypage?tab=reserved");
    const row = page
      .getByRole("row")
      .filter({ hasText: instrument.name })
      .first();
    await expect(row).toBeVisible({ timeout: 30_000 });

    // 취소는 한 번 묻는다 — 잘못 눌러 예약이 사라지는 일이 없게. 그 확인 버튼을 연타한다.
    await row.getByRole("button", { name: "취소", exact: true }).click();
    const confirm = page
      .getByRole("alertdialog")
      .getByRole("button", { name: "예약 취소" });
    await expect(confirm, "취소 확인").toBeVisible();

    await confirm.click({ noWaitAfter: true });
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await confirm.click({ force: true, noWaitAfter: true });
    }
    await page.waitForTimeout(HOLD_MS + 3_000);

    await expect(
      page.getByText("문제가 발생했습니다"),
      "에러 바운더리"
    ).toHaveCount(0);
    // 잠금 반환은 1회분이다 — 두 번 돌아오면 돈이 복제된 것이다.
    await expect(async () => {
      expectAmount(
        availableKrw(await app.account()),
        beforeCash,
        "KRW",
        "취소 후 가용 현금 === 등록 전 값"
      );
    }).toPass({ timeout: 20_000 });
  });

  /**
   * 진짜 동시성은 보장할 수 없다. **둘 다 성공하지는 않는다**와 **현금이 음수가 되지
   * 않는다** 까지만 단언한다.
   */
  test("U-CON-05 잔고 초과 동시 주문 중 하나가 거부된다", async ({
    app,
    playwright,
  }) => {
    await requireTrading(app);
    const { context, client } = await openSessionApi(
      playwright.request,
      SESSION.race
    );

    try {
      await liquidateKrwPositions(client);
      // 전용 계정 쪽도 휴장을 만날 수 있다. 여기서는 `client` 를 쓰므로 래퍼가 아니라
      // 직접 받아 보고, 열린 종목이 없으면 사유를 남기고 넘어간다.
      const pick = await cheapestKrwInstrument(client, 1);
      test.skip(
        pick === null,
        "지금 시장가로 살 수 있는 원화 종목이 없다 (종목별 휴장)"
      );
      const { instrument } = pick!;
      const unit = await client.previewOrder({
        orderType: "MARKET",
        side: "BUY",
        instrumentId: instrument.instrumentId,
        quantity: 1,
      });
      const quantity = Number(
        scaled(unit.availableCash, 0) / scaled(unit.totalAmount, 0)
      );
      test.skip(
        quantity < 1,
        `전액으로 1주도 살 수 없다 (가용 ${unit.availableCash})`
      );

      const body = {
        orderType: "MARKET" as const,
        side: "BUY" as const,
        instrumentId: instrument.instrumentId,
        quantity,
      };
      const [first, second] = await Promise.all([
        client.placeOrderRaw(body),
        client.placeOrderRaw(body),
      ]);

      const succeeded = [first, second].filter((response) => response.ok());
      expect(
        succeeded.length,
        `두 전액 주문의 결과: ${first.status()} / ${second.status()}`
      ).toBeLessThan(2);

      const after = await client.account();
      expect(
        Number(availableKrw(after)),
        "현금이 음수가 되지 않는다"
      ).toBeGreaterThanOrEqual(0);
    } finally {
      await context.dispose();
    }
  });

  test("U-CON-06 뒤로 가기로 재제출되지 않는다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );

    await gotoApp(page, "/ranking");
    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    await page.getByLabel("수량").fill(String(QUANTITY));
    await page.getByRole("button", { name: "매수 주문", exact: true }).click();
    await expect(page.locator("[data-sonner-toast]")).toBeVisible({
      timeout: 20_000,
    });
    const after = (await app.executions({ size: 1 })).totalElements;

    await page.goBack();
    await page.goForward();
    await page.waitForTimeout(3_000);

    expect((await app.executions({ size: 1 })).totalElements, "체결 건수").toBe(
      after
    );
  });

  test("U-CON-07 주문 직후 새로고침해도 값이 같다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );

    await app.placeOrder({
      orderType: "MARKET",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: QUANTITY,
    });

    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });
    const cash = parseMoney(await statValue(page, "원화 현금").innerText());

    await page.reload();
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });

    expect(parseMoney(await statValue(page, "원화 현금").innerText())).toBe(
      cash
    );
  });

  /** **조용히 실패하지 않는다.** 쿠키가 사라졌으면 그것을 사람이 알아야 한다. */
  test("U-CON-08 쿠키가 사라진 상태에서 주문하면 안내를 받는다", async ({
    app,
    page,
    context,
    guard,
  }) => {
    await requireTrading(app);
    const { instrument } = await requireKrwInstrument(app, QUANTITY);
    guard.allowResponse({ url: /\/api\/v1\//, status: [401, 403] });
    guard.allowConsole(/.*/);

    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    await page.getByLabel("수량").fill(String(QUANTITY));
    const before = (await app.executions({ size: 1 })).totalElements;

    await context.clearCookies();
    await page.getByRole("button", { name: "매수 주문", exact: true }).click();

    // 토스트든 로그인 화면이든, 무언가가 일어나야 한다.
    await expect(async () => {
      const toast = await page.locator("[data-sonner-toast]").count();
      const login = /\/login$/.test(page.url());
      const prompt = await page
        .getByText("주문은 로그인 후에 넣을 수 있습니다")
        .count();
      expect(toast + prompt + (login ? 1 : 0), "안내가 뜬다").toBeGreaterThan(
        0
      );
    }).toPass({ timeout: 20_000 });

    expect(
      (await app.executions({ size: 1 })).totalElements,
      "체결이 늘지 않는다"
    ).toBe(before);
  });

  /**
   * **`clearCookies()` 와 다른 상황이다** — 쿠키가 있으니 1층(`proxy.ts`)은 통과하고
   * 2층에서 걸려야 한다. 전용 세션으로 돌려 주 세션을 죽이지 않는다.
   */
  test("U-CON-09 쿠키는 남았지만 서버 세션이 죽은 상태를 처리한다", async ({
    app,
    browser,
    playwright,
  }) => {
    await requireTrading(app);
    // 세션 둘을 열고 화면을 두 번 태운다. 기본 시간으로는 모자란다.
    test.setTimeout(180_000);
    const { instrument } = await requireKrwInstrument(app, QUANTITY);
    const { context, page } = await openSession(browser, SESSION.expiry);

    try {
      await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
      await expectPriceLive(page);

      // **다른 컨텍스트에서** 같은 세션을 로그아웃한다. 쿠키는 이 브라우저에 그대로 남는다.
      const other = await openSessionApi(playwright.request, SESSION.expiry);
      const state = await other.context.storageState();
      const token = state.cookies.find(
        (cookie) => cookie.name === "XSRF-TOKEN"
      )!.value;
      const loggedOut = await other.context.post("/api/auth/logout", {
        headers: { "X-XSRF-TOKEN": token },
      });
      expect(loggedOut.status(), "로그아웃").toBeLessThan(400);
      await other.context.dispose();

      // **버튼이 사라지는 것도 정상 전환이다.** 세션이 죽은 것을 화면이 알아채면 주문
      // 자리가 로그인 안내로 바뀐다 — 그때 버튼을 계속 기다리면 정상 동작에서 시간이
      // 다 간다. 아직 버튼이 있을 때만 눌러 본다.
      // 화면이 전환되는 **도중**이면 입력창과 버튼이 손에서 빠져나간다. 그것 자체가
      // 기대하는 전환이므로, 못 눌렀다고 기다리지 않고 짧게 시도만 하고 넘어간다.
      await page
        .getByLabel("수량")
        .fill(String(QUANTITY), { timeout: 10_000 })
        .catch(() => undefined);
      await page
        .getByRole("button", { name: "매수 주문", exact: true })
        .click({ noWaitAfter: true, timeout: 10_000 })
        .catch(() => undefined);

      await expect(async () => {
        const toast = await page.locator("[data-sonner-toast]").count();
        const login = /\/login$/.test(page.url());
        const prompt = await page
          .getByText("주문은 로그인 후에 넣을 수 있습니다")
          .count();
        const gone = (await page.getByText("Total assets").count()) === 0;
        expect(
          toast + prompt + (login ? 1 : 0) + (gone ? 1 : 0),
          "로그인 안내로 전환된다"
        ).toBeGreaterThan(0);
      }).toPass({ timeout: 60_000 });

      // 개인 데이터가 화면에 남지 않는다.
      await expect(page.getByText("Total assets")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });
});
