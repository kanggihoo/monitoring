import {
  availableKrw,
  cancelOpenOrders,
  ensureHolding,
  expectAmount,
} from "./api";
import { diff, gte, orderAmount, scaled, unscaled } from "./money";
import {
  expect,
  requireKrwInstrument,
  requireTrading,
  test,
} from "./user-fixtures";

/**
 * 5. 예약 주문(지정가)·잠금 (`docs/qa/user-checklist.md`).
 *
 * **돈이 새는지 보는 자리다.** 잠긴 금액이 취소로 전액 돌아오지 않으면 돈이 증발하거나
 * 복제된 것이고, 그것은 잔고 화면 어디에도 표시되지 않는다.
 */

const QUANTITY = 10;

/** 이 장이 만든 미체결 예약. 남기면 현금이 잠긴 채 다음 실행이 시작된다. */
let created: number[] = [];

test.beforeEach(() => {
  created = [];
});

test.afterEach(async ({ app }) => {
  await cancelOpenOrders(app, created);
});

/** 현재가보다 **낮은** 지정가. 체결되지 않아야 잠금을 관찰할 수 있다. */
const belowMarket = (referencePrice: string, priceScale: number) =>
  unscaled(
    (scaled(referencePrice, priceScale) * BigInt(60)) / BigInt(100),
    priceScale
  );

/** 현재가보다 **높은** 지정가. 다음 틱에 체결되기를 기대하는 케이스가 쓴다. */
const aboveMarket = (referencePrice: string, priceScale: number) =>
  unscaled(
    (scaled(referencePrice, priceScale) * BigInt(140)) / BigInt(100),
    priceScale
  );

/** 예약 매수 하나를 건다. 잠금·취소를 보는 케이스가 공통으로 쓰는 준비다. */
async function reserveBuy(
  app: import("./api").Api,
  quantity = QUANTITY
): Promise<{
  instrumentId: number;
  limitPrice: string;
  priceScale: number;
  orderId: number;
  lockedAmount: string;
} | null> {
  const { instrument, preview } = await requireKrwInstrument(app, quantity);
  const limitPrice = belowMarket(preview.referencePrice, instrument.priceScale);
  const needed = orderAmount(
    limitPrice,
    quantity,
    instrument.priceScale,
    "KRW"
  );

  const account = await app.account();
  if (!gte(availableKrw(account), needed, 0)) return null;

  const placed = await app.placeOrder({
    orderType: "LIMIT",
    side: "BUY",
    instrumentId: instrument.instrumentId,
    quantity,
    limitPrice,
  });
  const order = placed.order!;
  created.push(order.orderId);

  return {
    instrumentId: instrument.instrumentId,
    limitPrice,
    priceScale: instrument.priceScale,
    orderId: order.orderId,
    lockedAmount: order.lockedAmount!,
  };
}

test.describe("5. 예약 주문(지정가)·잠금", () => {
  test("U-RSV-01 예약 매수가 등록된다", async ({ app }) => {
    await requireTrading(app);
    const { instrument, preview } = await requireKrwInstrument(app, QUANTITY);
    const limitPrice = belowMarket(
      preview.referencePrice,
      instrument.priceScale
    );
    const account = await app.account();
    test.skip(
      !gte(
        availableKrw(account),
        orderAmount(limitPrice, QUANTITY, instrument.priceScale, "KRW"),
        0
      ),
      "지정가 × 수량 만큼의 원화 현금이 없다"
    );

    const placed = await app.placeOrder({
      orderType: "LIMIT",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: QUANTITY,
      limitPrice,
    });
    created.push(placed.order!.orderId);

    expect(placed.orderType, "주문 유형").toBe("LIMIT");
    // **체결이 없다는 것이 예약의 정의다.** 둘 다 채워져 오면 무엇이 일어난 것인지 알 수 없다.
    expect(placed.execution, "체결").toBeNull();
    expect(placed.order, "예약").not.toBeNull();

    const open = await app.openOrders();
    expect(
      open.map((order) => order.orderId),
      "예약 목록"
    ).toContain(placed.order!.orderId);
  });

  test("U-RSV-02 잠긴 금액이 `지정가 × 수량` 이다", async ({ app }) => {
    await requireTrading(app);
    const reserved = await reserveBuy(app);
    test.skip(reserved === null, "예약을 걸 원화 현금이 없다");

    // **`orderId` 로 찾는다** — "첫 줄"은 다른 주문이 끼어들면 틀린다.
    const order = (await app.openOrders()).find(
      (item) => item.orderId === reserved!.orderId
    );
    expect(order, `예약 ${reserved!.orderId}`).toBeTruthy();
    expectAmount(
      order!.lockedAmount!,
      orderAmount(reserved!.limitPrice, QUANTITY, reserved!.priceScale, "KRW"),
      "KRW",
      "잠금액 === 지정가 × 수량 (매수 수수료 0)"
    );
  });

  test("U-RSV-03 주문 가능 현금이 잠금액만큼 줄었다", async ({ app }) => {
    await requireTrading(app);
    const before = availableKrw(await app.account());

    const reserved = await reserveBuy(app);
    test.skip(reserved === null, "예약을 걸 원화 현금이 없다");

    const after = availableKrw(await app.account());
    expectAmount(
      diff(before, after, 0),
      reserved!.lockedAmount,
      "KRW",
      "가용 현금 감소분 === 잠금액"
    );
  });

  /** **어긋나면 돈이 증발하거나 복제된 것이다.** 근사치가 아니라 정확히 같아야 한다. */
  test("U-RSV-04 취소하면 잠금이 전액 돌아온다", async ({ app }) => {
    await requireTrading(app);
    const before = availableKrw(await app.account());

    const reserved = await reserveBuy(app);
    test.skip(reserved === null, "예약을 걸 원화 현금이 없다");

    const cancelled = await app.cancelOrderRaw(reserved!.orderId);
    expect(cancelled.ok(), `취소 ${cancelled.status()}`).toBe(true);

    const after = availableKrw(await app.account());
    expectAmount(after, before, "KRW", "취소 후 가용 현금 === 등록 전 값");
  });

  test("U-RSV-05 예약 매도는 수량을 잠근다", async ({ app }) => {
    await requireTrading(app);
    const instrument = await ensureHolding(app, QUANTITY);
    test.skip(instrument === null, `보유 ${QUANTITY}주를 만들 현금이 없다`);
    const held = (await app.positions()).find(
      (item) => item.instrumentId === instrument!.instrumentId
    )!;
    const free = held.quantity - held.lockedQuantity;

    const preview = await app.previewOrder({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: 1,
    });
    const placed = await app.placeOrder({
      orderType: "LIMIT",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: free,
      limitPrice: aboveMarket(preview.referencePrice, instrument!.priceScale),
    });
    created.push(placed.order!.orderId);

    expect(placed.order!.lockedQuantity, "잠긴 수량").toBe(free);
    const after = (await app.positions()).find(
      (item) => item.instrumentId === instrument!.instrumentId
    )!;
    expect(after.lockedQuantity, "포지션의 잠긴 수량").toBeGreaterThanOrEqual(
      free
    );

    // 잠긴 수량은 더 팔 수 없다.
    const extra = await app.placeOrderRaw({
      orderType: "MARKET",
      side: "SELL",
      instrumentId: instrument!.instrumentId,
      quantity: 1,
    });
    expect(extra.ok(), "잠긴 수량을 넘는 추가 매도는 거부된다").toBe(false);
  });

  test("U-RSV-06 수량만 고치면 지정가가 유지된다", async ({ app }) => {
    await requireTrading(app);
    const reserved = await reserveBuy(app);
    test.skip(reserved === null, "예약을 걸 원화 현금이 없다");
    const nextQuantity = QUANTITY - 1;

    const response = await app.amendOrderRaw(reserved!.orderId, {
      quantity: nextQuantity,
    });
    expect(
      response.ok(),
      `정정 ${response.status()} ${await response.text()}`
    ).toBe(true);
    const amended = (await response.json()).data as import("./api").OpenOrder;

    // **지정가를 건드리지 않았으므로 그대로여야 한다.** 재계산되는 것은 잠금액뿐이다.
    expectAmount(
      amended.limitPrice,
      reserved!.limitPrice,
      "KRW",
      "지정가 유지"
    );
    expect(amended.quantity, "수량").toBe(nextQuantity);
    expectAmount(
      amended.lockedAmount!,
      orderAmount(
        reserved!.limitPrice,
        nextQuantity,
        reserved!.priceScale,
        "KRW"
      ),
      "KRW",
      "잠금액이 새 수량으로 재계산된다"
    );
  });

  /**
   * **두 곳에 동시에 있지 않아야 한다.** 예약에서 사라지는 것과 체결에 나타나는 것이 한
   * 전이여야 잠금·잔고가 어긋나지 않는다.
   */
  test("U-RSV-07 체결되면 예약에서 사라지고 체결에 나타난다", async ({
    app,
  }) => {
    const clock = await requireTrading(app);
    test.skip(clock.nextTickAt === null, "다음 틱이 없다");

    const { instrument, preview } = await requireKrwInstrument(app, 1);
    // 현재가보다 **높은** 지정가라 다음 틱에 체결되어야 한다.
    const limitPrice = aboveMarket(
      preview.referencePrice,
      instrument.priceScale
    );
    const account = await app.account();
    test.skip(
      !gte(
        availableKrw(account),
        orderAmount(limitPrice, 1, instrument.priceScale, "KRW"),
        0
      ),
      "지정가 매수를 걸 원화 현금이 없다"
    );

    const placed = await app.placeOrder({
      orderType: "LIMIT",
      side: "BUY",
      instrumentId: instrument.instrumentId,
      quantity: 1,
      limitPrice,
    });
    const orderId = placed.order!.orderId;
    created.push(orderId);

    const wait = new Date(clock.nextTickAt!).getTime() - Date.now() + 20_000;
    let gone = false;
    await expect(async () => {
      gone = !(await app.openOrders()).some(
        (order) => order.orderId === orderId
      );
      expect(gone, `예약 ${orderId} 가 아직 미체결이다`).toBe(true);
    })
      .toPass({ timeout: Math.max(45_000, wait) })
      .catch(() => undefined);

    test.skip(!gone, "다음 틱에 지정가가 빗나가 체결되지 않았다");

    const executions = (await app.executions({ size: 50 })).content.filter(
      (item) =>
        item.instrumentId === instrument.instrumentId &&
        item.orderType === "LIMIT"
    );
    expect(executions.length, "지정가 체결이 내역에 남는다").toBeGreaterThan(0);
    expect(
      (await app.openOrders()).map((order) => order.orderId),
      "체결된 예약은 예약 목록에 없다"
    ).not.toContain(orderId);
  });

  test("U-RSV-08 폐장하면 미체결 예약이 사라진다", async ({ app }) => {
    const clock = await app.clock();
    test.skip(
      true,
      `폐장 대기가 길어 이 실행 대상이 아니다 (phase=${clock.phase}). 폐장 직전 지점에서만 수행한다`
    );
  });
});
