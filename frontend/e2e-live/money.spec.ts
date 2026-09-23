import {
  anyUsdInstrument,
  availableKrw,
  availableUsd,
  cheapestKrwInstrument,
  ensureHolding,
  expectAmount,
  liquidateKrwPositions,
} from "./api";
import {
  diff,
  exchangeFee,
  exchangeReceive,
  gte,
  orderAmount,
  parseMoney,
  scaled,
  sellFee,
  unscaled,
} from "./money";
import {
  expect,
  gotoApp,
  openSessionApi,
  requireKrwInstrument,
  requireTrading,
  SESSION,
  test,
} from "./user-fixtures";

/**
 * 3. 매수 · 4. 매도 · 6. 환전 (`docs/qa/user-checklist.md`).
 *
 * **미리보기 검증과 결제 검증을 분리한다** (체크리스트 §실시간 환경에서의 단언 원칙).
 * 미리보기는 **응답 내부의 정합성**만 보고, 잔고 변화는 **그 주문의 체결 응답에 담긴 확정
 * 금액**과 비교한다. 둘을 맞대면 그사이 틱이 바뀐 정상 동작이 실패로 판정된다.
 */

const QUANTITY = 10;

test.describe("3. 매수 (시장가)", () => {
  test("U-BUY-01 매수 수수료가 0이다", async ({ app, page, guard }) => {
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );

    // **응답 내부 정합성만 본다.** 체결과 대조하지 않는다.
    expect(preview.appliedFeeRate, "적용 수수료율").toBe(0);
    expect(scaled(preview.estimatedFee, 0), "예상 수수료").toBe(BigInt(0));
    expect(preview.totalAmount, "총액 === 예상 금액").toBe(
      preview.estimatedAmount
    );

    // 화면도 같은 말을 해야 한다 — 수수료가 0 이면 곁말을 붙이지 않는다.
    guard.allowResponse({
      url: /\/api\/v1\/(users\/me|accounts|orders)/,
      status: [401],
    });
    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    await page.getByLabel("수량").fill(String(QUANTITY));
    await expect(page.getByText(/^예상 체결 금액/)).not.toContainText("수수료");
  });

  test("U-BUY-02 금액이 `기준가 × 수량` 과 같다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const { instrument } = await requireKrwInstrument(app, QUANTITY);

    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    // **화면이 부른 그 응답을 본다.** 따로 부르면 두 시점의 가격을 비교하게 된다.
    const [response] = await Promise.all([
      page.waitForResponse(
        (candidate) =>
          /\/api\/v1\/orders\/preview\?/.test(candidate.url()) &&
          candidate.url().includes(`quantity=${QUANTITY}`) &&
          candidate.status() === 200
      ),
      page.getByLabel("수량").fill(String(QUANTITY)),
    ]);
    const preview = (await response.json()).data as {
      referencePrice: string;
      estimatedAmount: string;
      totalAmount: string;
    };

    expect(
      orderAmount(
        preview.referencePrice,
        QUANTITY,
        instrument.priceScale,
        "KRW"
      ),
      "기준가 × 수량"
    ).toBe(preview.estimatedAmount);
    await expect(async () => {
      const shown = parseMoney(
        await page
          .getByText(/^예상 체결 금액/)
          .locator("xpath=following-sibling::*[1]")
          .innerText()
      );
      expect(shown, "화면의 예상 체결 금액").toBe(Number(preview.totalAmount));
    }).toPass({ timeout: 10_000 });
  });

  test("U-BUY-03 현금 감소분이 체결 확정액과 같다", async ({ app }) => {
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const before = await app.account();
    test.skip(
      !gte(availableKrw(before), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );

    const placed = await app.placeOrder({
      orderType: "MARKET",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: QUANTITY,
    });
    const execution = placed.execution!;
    expect(execution, "시장가 매수는 즉시 체결된다").toBeTruthy();

    const after = await app.account();
    const settled = unscaled(
      scaled(execution.amount, 0) + scaled(execution.fee, 0),
      0
    );
    // **미리보기 값과 비교하지 않는다** — 그 사이 틱이 바뀔 수 있다.
    expectAmount(
      diff(before.krwCash, after.krwCash, 0),
      settled,
      "KRW",
      "현금 감소분 === 체결 금액 + 수수료"
    );
    expect(scaled(execution.fee, 0), "매수 수수료").toBe(BigInt(0));
  });

  test("U-BUY-04 보유 수량이 정확히 늘어난다", async ({ app, page, guard }) => {
    void guard;
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );

    const before =
      (await app.positions()).find(
        (position) => position.instrumentId === instrument.instrumentId
      )?.quantity ?? 0;

    const { execution } = await app.placeOrder({
      orderType: "MARKET",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: QUANTITY,
    });

    const after = (await app.positions()).find(
      (position) => position.instrumentId === instrument.instrumentId
    );
    expect(after?.quantity, "보유 수량").toBe(before + execution!.quantity);

    await gotoApp(page, "/mypage");
    const row = page
      .getByRole("row")
      .filter({ hasText: instrument.name })
      .first();
    await expect(row, `${instrument.name} 줄`).toBeVisible({ timeout: 30_000 });
    await expect(row).toContainText(after!.quantity.toLocaleString("en-US"));
  });

  /** **"첫 줄"로 찾지 않는다** — 다른 주문이 끼어들 수 있다. */
  test("U-BUY-05 체결 한 줄이 그 주문과 일치한다", async ({ app }) => {
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), preview.totalAmount, 0),
      `원화 현금이 ${preview.totalAmount} 에 못 미친다`
    );

    const { execution } = await app.placeOrder({
      orderType: "MARKET",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: QUANTITY,
    });

    const listed = (await app.executions({ size: 50 })).content.find(
      (item) => item.executionId === execution!.executionId
    );
    expect(
      listed,
      `체결 ${execution!.executionId} 이 내역에 있다`
    ).toBeTruthy();
    expect(listed!.side, "방향").toBe("BUY");
    expect(listed!.quantity, "수량").toBe(execution!.quantity);
    expect(listed!.executedPrice, "체결가").toBe(execution!.executedPrice);
    expect(scaled(listed!.fee, 0), "수수료").toBe(BigInt(0));
  });

  /**
   * **전용 계정으로 돈다.** 전액 매수는 되돌릴 수 없고(체결은 불가역), 주 계좌에서 하면
   * 뒤 케이스와 다음 실행의 전제가 함께 사라진다. 그 계정은 지난 실행이 현금을 비워 둔
   * 채로 끝나므로 준비 단계가 보유를 되팔아 스스로 전제를 만든다.
   */
  test("U-BUY-06 주문 가능 금액 전액이 통과한다", async ({
    app,
    playwright,
  }) => {
    await requireTrading(app);
    const { context, client } = await openSessionApi(
      playwright.request,
      SESSION.spendAll
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
      // `availableCash` 는 **내림**이라 가격이 그대로면 이 수량은 반드시 통과해야 한다.
      const quantity = Number(
        scaled(unit.availableCash, 0) / scaled(unit.totalAmount, 0)
      );
      test.skip(
        quantity < 1,
        `전액으로 1주도 살 수 없다 (가용 ${unit.availableCash})`
      );

      const response = await client.placeOrderRaw({
        orderType: "MARKET",
        side: "BUY",
        instrumentId: instrument.instrumentId,
        quantity,
      });

      if (!response.ok()) {
        const body = await response.text();
        // **정상 거부를 실패로 보지 않는다** — 그사이 가격이 올라 잔고가 모자란 것이면
        // 정상이다. 사유가 잔고 부족이 아니면 그때는 결함이다.
        expect(body, `전액 주문 거부 사유 (${response.status()})`).toMatch(
          /부족|잔고|현금/
        );
        test.skip(true, `그사이 가격이 올라 전액 주문이 거부되었다: ${body}`);
      }

      const after = await client.account();
      expect(
        Number(availableKrw(after)),
        "남은 주문 가능 현금"
      ).toBeGreaterThanOrEqual(0);
    } finally {
      await context.dispose();
    }
  });

  /**
   * **아무 반응 없이 실패하지 않는다.** 달러가 0 인 계좌로 미국 종목을 사려 하면 부족분
   * 환전 확인이 떠야 한다 — 버튼만 잠그면 왜 잠겼는지도, 무엇을 해야 하는지도 말하지 못한다.
   */
  test("U-BUY-07 돈이 모자라면 안내를 받는다", async ({ app, page, guard }) => {
    await requireTrading(app);
    const instrument = await anyUsdInstrument(app);
    const before = await app.account();

    guard.allowConsole(/부족|shortfall/i);
    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);
    // 달러 잔고로는 결코 살 수 없는 수량을 넣는다.
    await page.getByLabel("수량").fill("100000");
    const buy = page.getByRole("button", { name: "매수 주문", exact: true });
    await expect(buy).toBeVisible();

    if (await buy.isEnabled()) {
      await buy.click();
      await expect(
        page.getByRole("alertdialog"),
        "부족분 환전 확인"
      ).toContainText("부족합니다");
      await page.getByRole("button", { name: "나중에" }).click();
    } else {
      // 잠겼다면 왜 잠겼는지가 같은 패널에 적혀 있어야 한다. **문구는 `123,456원 부족`
      // 이다** (`order-ticket.tsx` 의 `blockedReasons`) — 예전의 `주문 가능 현금` 안내가
      // 아니다. 얼마가 모자란지까지 적히므로 사용자가 다음에 할 일을 안다.
      await expect(page.getByText(/부족/)).toBeVisible();
    }

    const after = await app.account();
    expect(after.krwCash, "원화 현금이 움직이지 않았다").toBe(before.krwCash);
    expect(after.usdCash, "달러 현금이 움직이지 않았다").toBe(before.usdCash);
    expect(
      Number(after.usdCash),
      "현금이 음수가 되지 않는다"
    ).toBeGreaterThanOrEqual(0);
  });
});

test.describe("4. 매도 (시장가)", () => {
  test("U-SEL-01 매도 수수료가 0.15% 다", async ({ app }) => {
    const clock = await requireTrading(app);
    test.skip(
      clock.feeFree,
      "무료 타임이라 수수료가 0 이다 (U-SEL-02 가 본다)"
    );
    const instrument = await ensureHolding(app, QUANTITY);
    test.skip(instrument === null, `보유 ${QUANTITY}주를 만들 현금이 없다`);

    const preview = await app.previewOrder({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: QUANTITY,
    });

    expect(preview.appliedFeeRate, "적용 수수료율").toBe(0.15);
    expect(preview.estimatedFee, "수수료 === round(금액 × 0.15%)").toBe(
      sellFee(preview.estimatedAmount, preview.currency)
    );
    expect(preview.totalAmount, "수령액 === 금액 − 수수료").toBe(
      unscaled(
        scaled(preview.estimatedAmount, 0) - scaled(preview.estimatedFee, 0),
        0
      )
    );
  });

  test("U-SEL-02 무료 타임에는 수수료가 0이다", async ({ app }) => {
    const clock = await requireTrading(app);
    test.skip(
      !clock.feeFree,
      "무료 타임이 아니다 (매일 12:00 · 10분간). 환경 의존이라 상시 `B` 가 허용된다"
    );
    const instrument = await ensureHolding(app, QUANTITY);
    test.skip(instrument === null, `보유 ${QUANTITY}주를 만들 현금이 없다`);

    const preview = await app.previewOrder({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: QUANTITY,
    });

    expect(preview.feeFree, "무료 타임 표시").toBe(true);
    expect(preview.appliedFeeRate, "적용 수수료율").toBe(0);
    expect(scaled(preview.estimatedFee, 0), "수수료").toBe(BigInt(0));
  });

  test("U-SEL-03 현금 증가분이 체결 확정 수령액과 같다", async ({ app }) => {
    await requireTrading(app);
    const instrument = await ensureHolding(app, QUANTITY);
    test.skip(instrument === null, `보유 ${QUANTITY}주를 만들 현금이 없다`);
    const before = await app.account();

    const { execution } = await app.placeOrder({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: QUANTITY,
    });

    const after = await app.account();
    expectAmount(
      diff(after.krwCash, before.krwCash, 0),
      unscaled(scaled(execution!.amount, 0) - scaled(execution!.fee, 0), 0),
      "KRW",
      "현금 증가분 === 체결 금액 − 수수료"
    );
  });

  test("U-SEL-04 실현손익의 부호가 맞다", async ({ app }) => {
    await requireTrading(app);
    const instrument = await ensureHolding(app, QUANTITY);
    test.skip(instrument === null, `보유 ${QUANTITY}주를 만들 현금이 없다`);
    const position = (await app.positions()).find(
      (item) => item.instrumentId === instrument!.instrumentId
    )!;
    const avgPrice = position.avgPrice;

    const { execution } = await app.placeOrder({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: QUANTITY,
    });

    const listed = (await app.executions({ size: 50 })).content.find(
      (item) => item.executionId === execution!.executionId
    )!;
    expect(listed.realizedPnl, "매도의 실현손익은 값이 있다").not.toBeNull();

    const scale = instrument!.priceScale;
    const higher =
      scaled(listed.executedPrice, scale) > scaled(avgPrice, scale);
    const lower = scaled(listed.executedPrice, scale) < scaled(avgPrice, scale);
    if (higher) {
      expect(
        Number(listed.realizedPnl),
        `체결가 ${listed.executedPrice} > 평단 ${avgPrice}`
      ).toBeGreaterThan(0);
    } else if (lower) {
      expect(
        Number(listed.realizedPnl),
        `체결가 ${listed.executedPrice} < 평단 ${avgPrice}`
      ).toBeLessThan(0);
    }

    // **매수 항목은 `realizedPnl === null`** 이다. 같은 목록에서 함께 본다.
    const buys = (await app.executions({ size: 50 })).content.filter(
      (item) => item.side === "BUY"
    );
    for (const buy of buys) {
      expect(buy.realizedPnl, `매수 ${buy.executionId} 의 실현손익`).toBeNull();
    }
  });

  test("U-SEL-05 보유 수량을 넘겨 팔 수 없다", async ({ app, page, guard }) => {
    void guard;
    await requireTrading(app);
    const instrument = await ensureHolding(app, QUANTITY);
    test.skip(instrument === null, `보유 ${QUANTITY}주를 만들 현금이 없다`);
    const held = (await app.positions()).find(
      (item) => item.instrumentId === instrument!.instrumentId
    )!.quantity;

    await gotoApp(page, `/?instrumentId=${instrument!.instrumentId}`);
    // **방향을 먼저 고른다.** 매수·매도는 탭이고 제출 버튼은 고른 쪽 이름을 쓴다
    // (`order-ticket.tsx`) — 매수에 서 있는 채로 `매도 주문` 을 찾으면 영원히 없다.
    // 방향을 바꾸면 수량이 비워지므로 그다음에 적는다.
    await page.getByRole("tab", { name: "매도", exact: true }).click();
    await page.getByLabel("수량").fill(String(held + 1));
    const sell = page.getByRole("button", { name: "매도 주문", exact: true });

    if (await sell.isEnabled()) {
      const response = await app.placeOrderRaw({
        orderType: "MARKET",
        side: "SELL",
        instrumentId: instrument!.instrumentId,
        quantity: held + 1,
      });
      expect(response.ok(), "보유 초과 매도는 거부된다").toBe(false);
    } else {
      await expect(sell, "보유 초과면 매도 버튼이 잠긴다").toBeDisabled();
    }

    const after = (await app.positions()).find(
      (item) => item.instrumentId === instrument!.instrumentId
    );
    expect(
      after?.quantity ?? 0,
      "보유가 음수가 되지 않는다"
    ).toBeGreaterThanOrEqual(0);
  });
});

test.describe("6. 환전", () => {
  const SEND_KRW = "1000000";

  test("U-FX-01 환전 요율이 0.05% 다", async ({ app }) => {
    await requireTrading(app);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), SEND_KRW, 0),
      `원화 현금이 ${SEND_KRW} 에 못 미친다`
    );

    const quote = await app.previewExchange("KRW_TO_USD", SEND_KRW);

    expect(quote.feeRate, "요율").toBe(0.05);
    // `round(1000000 × 0.05/100, 0자리)` = 500. **보내는 통화 자릿수**로 반올림한다.
    expect(quote.fee, "수수료").toBe(exchangeFee(SEND_KRW, "KRW_TO_USD"));
    expect(quote.fee, "수수료 실값").toBe("500");
  });

  test("U-FX-02 수수료가 보내는 통화로 부과된다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), SEND_KRW, 0),
      `원화 현금이 ${SEND_KRW} 에 못 미친다`
    );

    await gotoApp(page, "/mypage");
    await page.getByLabel(/보낼 금액/).fill(SEND_KRW);
    const fee = page
      .getByText("수수료", { exact: true })
      .locator("xpath=following-sibling::*[1]");
    // 원화를 보내므로 수수료도 **원화**다. 받는 통화가 아니다.
    await expect(fee, "KRW→USD 의 수수료 단위").toContainText("원");
    await expect(fee).not.toContainText("$");

    await page.getByRole("tab", { name: "달러 → 원화" }).click();
    await page.getByLabel(/보낼 금액/).fill("10.00");
    await expect(fee, "USD→KRW 의 수수료 단위").toContainText("$");
  });

  test("U-FX-03 수령액이 나누기 공식과 맞다 (KRW→USD)", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), SEND_KRW, 0),
      `원화 현금이 ${SEND_KRW} 에 못 미친다`
    );

    /**
     * **화면이 받은 응답과 대조한다.** 테스트가 따로 부른 미리보기와 견주면 그 사이에 틱이
     * 흐른 순간 값이 갈린다 — 환율은 게임 거래일마다 바뀌고 이 재생에서는 거래일이 곧
     * 틱이다 (실측: 화면 `831.25` vs 테스트가 받은 `833.19`). 정상 동작을 실패로 읽던
     * 자리다.
     */
    const previews: { rate: string; fee: string; receiveAmount: string }[] = [];
    page.on("response", async (response) => {
      if (!/\/exchanges\/preview/.test(response.url())) return;
      const body = await response.json().catch(() => null);
      if (body?.data) previews.push(body.data);
    });

    await gotoApp(page, "/mypage");
    await page.getByLabel(/보낼 금액/).fill(SEND_KRW);

    await expect(async () => {
      const quote = previews.at(-1);
      expect(quote, "화면이 받은 미리보기").toBeTruthy();

      // **곱하기가 아니다.** `floor((1000000 − 500) ÷ rate, 2)`. 같은 응답 안에서 본다.
      expect(
        exchangeReceive(SEND_KRW, quote!.fee, quote!.rate, "KRW_TO_USD"),
        `수령액 (rate=${quote!.rate})`
      ).toBe(quote!.receiveAmount);

      const shown = parseMoney(
        await page
          .getByText("받는 금액", { exact: true })
          .locator("xpath=following-sibling::*[1]")
          .innerText()
      );
      expect(shown, "화면의 받는 금액").toBeCloseTo(
        Number(quote!.receiveAmount),
        2
      );
    }).toPass({ timeout: 20_000 });
  });

  test("U-FX-04 수령액이 곱하기 공식과 맞다 (USD→KRW)", async ({ app }) => {
    await requireTrading(app);
    const account = await app.account();
    const send = "10.00";
    test.skip(
      !gte(availableUsd(account), send, 2),
      `달러 현금이 ${send} 에 못 미친다 (보유 ${account.usdCash})`
    );

    const quote = await app.previewExchange("USD_TO_KRW", send);

    // 수수료는 **달러 2자리 반올림**, 수령액은 원화 0자리 **내림**.
    expect(quote.fee, "수수료").toBe(exchangeFee(send, "USD_TO_KRW"));
    expect(
      exchangeReceive(send, quote.fee, quote.rate, "USD_TO_KRW"),
      `수령액 (rate=${quote.rate})`
    ).toBe(quote.receiveAmount);
  });

  test("U-FX-05 미리보기와 실행 결과가 같다", async ({ app }) => {
    await requireTrading(app);
    const account = await app.account();
    test.skip(
      !gte(availableKrw(account), SEND_KRW, 0),
      `원화 현금이 ${SEND_KRW} 에 못 미친다`
    );

    const quote = await app.previewExchange("KRW_TO_USD", SEND_KRW);
    const done = await app.exchange("KRW_TO_USD", SEND_KRW);

    // **`rate` 도 함께 대조한다** — 환율 기준일이 넘어갔으면 값이 갈릴 수 있고, 그때는
    // 결함이 아니라 재수행 대상이다.
    test.skip(
      done.rate !== quote.rate,
      `환율 기준일이 넘어갔다 (미리보기 ${quote.rate} → 실행 ${done.rate})`
    );
    expect(done.receiveAmount, "실행 수령액 === 미리보기 수령액").toBe(
      quote.receiveAmount
    );
    expect(done.fee, "실행 수수료").toBe(quote.fee);
  });

  /** **전용 계정으로 돈다.** 달러 비율을 크게 흔들어 주 계좌의 전제를 바꾸지 않는다. */
  test("U-FX-06 양쪽 통화가 정확히 움직인다", async ({ app, playwright }) => {
    await requireTrading(app);
    const { context, client } = await openSessionApi(
      playwright.request,
      SESSION.exchange
    );

    try {
      const before = await client.account();
      test.skip(
        !gte(availableKrw(before), SEND_KRW, 0),
        `원화 현금이 ${SEND_KRW} 에 못 미친다 (보유 ${before.krwCash})`
      );

      const done = await client.exchange("KRW_TO_USD", SEND_KRW);
      const after = await client.account();

      expectAmount(
        diff(before.krwCash, after.krwCash, 0),
        done.sendAmount,
        "KRW",
        "원화 감소분 === 보낸 금액"
      );
      expectAmount(
        diff(after.usdCash, before.usdCash, 2),
        done.receiveAmount,
        "USD",
        "달러 증가분 === 받은 금액"
      );
    } finally {
      await context.dispose();
    }
  });

  /**
   * **1원이 모자라 다시 막히면 반올림이 두 곳에서 일어난 것이다** — 사용자는 환전을 무한
   * 반복하게 된다. `sendAmount` 를 역산하지 않고 서버가 준 값을 **그대로** 쓴다.
   */
  test("U-FX-07 부족분 환전 직후 같은 주문이 통과한다", async ({
    app,
    playwright,
  }) => {
    await requireTrading(app);
    const { context, client } = await openSessionApi(
      playwright.request,
      SESSION.exchange
    );

    try {
      // 달러를 원화로 모두 보내 **부족 상태를 만든다.**
      const start = await client.account();
      if (scaled(availableUsd(start), 2) > BigInt(0)) {
        await client.exchange("USD_TO_KRW", availableUsd(start));
      }

      const instrument = await anyUsdInstrument(client);
      const preview = await client.previewOrder({
        orderType: "MARKET",
        side: "BUY",
        instrumentId: instrument.instrumentId,
        quantity: 1,
      });
      test.skip(
        preview.shortfallExchange === null,
        `부족분이 생기지 않았다 (shortfall=${preview.shortfall})`
      );

      const request = preview.shortfallExchange!;
      const account = await client.account();
      test.skip(
        !gte(availableKrw(account), request.sendAmount, 0),
        `부족분 환전에 필요한 ${request.sendAmount} 원이 없다`
      );

      // 서버가 준 `sendAmount` 를 **그대로** 보낸다.
      await client.exchange(request.direction, request.sendAmount);

      const retried = await client.placeOrderRaw({
        orderType: "MARKET",
        side: "BUY",
        instrumentId: instrument.instrumentId,
        quantity: 1,
      });
      expect(
        retried.ok(),
        `부족분 환전 직후 같은 주문: ${retried.status()} ${await retried.text()}`
      ).toBe(true);
    } finally {
      await context.dispose();
    }
  });
});
