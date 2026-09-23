import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  amendOrder,
  cancelOrder,
  fetchExchangePreview,
  fetchExchanges,
  fetchExecutions,
  fetchOrderPreview,
  placeOrder,
  submitExchange,
} from "@/lib/order-api";

/**
 * 주문·환전 계층 검사. **돈이 지나가는 경로라 요청 본문이 곧 계약이다.**
 *
 * 보는 것은 세 가지다: 보내면 안 되는 것을 안 보내는가(시장가의 지정가), 서버가 준 값을
 * 손대지 않고 넘기는가(환전 금액), 실패 문구를 어디서 고르는가.
 */

type Call = { url: string; init?: RequestInit };

function stubFetch(body: unknown, init: { status?: number } = {}): Call[] {
  const calls: Call[] = [];
  const status = init.status ?? 200;

  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, requestInit?: RequestInit) => {
      calls.push({ url, init: requestInit });
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { "content-type": "application/json" },
        })
      );
    })
  );

  return calls;
}

/** 마지막 호출의 JSON 본문. */
const sentBody = (call?: Call): Record<string, unknown> =>
  JSON.parse(String(call?.init?.body ?? "{}")) as Record<string, unknown>;

const MARKET_ORDER = {
  orderType: "MARKET",
  side: "BUY",
  instrumentId: 3,
  quantity: 10,
} as const;

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("주문 등록", () => {
  // 서버는 시장가의 `limitPrice` 를 무시하지만, 보내면 요청만 보고는 어느 유형인지 읽을 수
  // 없어 로그를 되짚을 때 오해가 생긴다.
  it("시장가에 지정가를 싣지 않는다", async () => {
    const calls = stubFetch({ data: {} }, { status: 201 });

    await placeOrder({ ...MARKET_ORDER, limitPrice: 40000 });

    expect(sentBody(calls[0])).not.toHaveProperty("limitPrice");
  });

  it("예약은 지정가를 싣는다", async () => {
    const calls = stubFetch({ data: {} }, { status: 201 });

    await placeOrder({
      ...MARKET_ORDER,
      orderType: "LIMIT",
      limitPrice: 40000,
    });

    expect(sentBody(calls[0])).toMatchObject({
      orderType: "LIMIT",
      limitPrice: 40000,
    });
  });

  // 필드별 이유가 가장 구체적이라 `message` 보다 먼저다 (`api.md`).
  it("실패 문구는 errors[0].reason 을 고른다", async () => {
    stubFetch(
      {
        message: "요청이 올바르지 않습니다.",
        errors: [
          { field: "quantity", reason: "수량은 1주 이상이어야 합니다." },
        ],
      },
      { status: 400 }
    );

    await expect(placeOrder(MARKET_ORDER)).rejects.toThrow(
      "수량은 1주 이상이어야 합니다."
    );
  });

  it("본문이 비어 있어도 기본 문구로 던진다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 500 })))
    );

    await expect(placeOrder(MARKET_ORDER)).rejects.toThrow(
      "주문에 실패했습니다."
    );
  });
});

describe("주문 미리보기", () => {
  it("시장가 질의에 limitPrice 가 없다", async () => {
    const calls = stubFetch({ data: {} });

    await fetchOrderPreview({ ...MARKET_ORDER, limitPrice: 40000 });

    expect(calls[0]?.url).not.toContain("limitPrice");
  });

  it("예약 질의에는 limitPrice 가 있다", async () => {
    const calls = stubFetch({ data: {} });

    await fetchOrderPreview({
      ...MARKET_ORDER,
      orderType: "LIMIT",
      limitPrice: 40000,
    });

    expect(calls[0]?.url).toContain("limitPrice=40000");
  });
});

describe("체결 내역", () => {
  // `seasonDay` 는 대시보드의 `오늘 매매` 만 쓴다. 없을 때 빈 값으로 보내면 서버가 그것을
  // 필터로 읽는다.
  it("seasonDay 를 주지 않으면 쿼리에 없다", async () => {
    const calls = stubFetch({ data: { content: [] } });

    await fetchExecutions(0, 20);

    expect(calls[0]?.url).toBe("/api/v1/executions?page=0&size=20");
  });

  it("seasonDay 를 주면 실린다", async () => {
    const calls = stubFetch({ data: { content: [] } });

    await fetchExecutions(0, 100, 3);

    expect(calls[0]?.url).toContain("&seasonDay=3");
  });
});

describe("환전", () => {
  // **미리보기가 준 금액을 그대로 보낸다.** 자릿수를 다듬거나 다시 계산하면 서버의 두 번
  // 반올림과 갈려, 환전 직후에도 잔고가 모자라 주문이 다시 막힌다 (`acc.md`).
  it("sendAmount 를 손대지 않고 amount 로 옮긴다", async () => {
    const calls = stubFetch({ data: {} }, { status: 201 });

    await submitExchange({ direction: "KRW_TO_USD", sendAmount: "1771674" });

    expect(sentBody(calls[0])).toEqual({
      direction: "KRW_TO_USD",
      amount: "1771674",
    });
  });

  it("미리보기는 방향과 금액을 쿼리로 보낸다", async () => {
    const calls = stubFetch({ data: {} });

    await fetchExchangePreview("USD_TO_KRW", "1000.00");

    expect(calls[0]?.url).toContain("direction=USD_TO_KRW");
    expect(calls[0]?.url).toContain("amount=1000.00");
  });

  it("이력은 페이지를 붙인다", async () => {
    const calls = stubFetch({ data: { content: [] } });

    await fetchExchanges(2, 20);

    expect(calls[0]?.url).toBe("/api/v1/accounts/me/exchanges?page=2&size=20");
  });

  it("잔고 부족(409)의 문구를 그대로 올린다", async () => {
    stubFetch({ message: "원화 잔고가 부족합니다." }, { status: 409 });

    await expect(
      submitExchange({ direction: "KRW_TO_USD", sendAmount: "999999999" })
    ).rejects.toThrow("원화 잔고가 부족합니다.");
  });
});

describe("정정·취소", () => {
  // 정정은 **바뀐 칸만** 보낸다. 손대지 않은 칸까지 실어 보내면 서버가 매번 두 값을 다시
  // 검증하고, 값이 같아도 정정 이력이 남는다 (`ord.md`).
  it("정정은 받은 변경만 싣는다", async () => {
    const calls = stubFetch({ data: {} });

    await amendOrder(7, { quantity: 5 });

    expect(sentBody(calls[0])).toEqual({ quantity: 5 });
    expect(calls[0]?.init?.method).toBe("PATCH");
  });

  it("취소는 DELETE 다", async () => {
    const calls = stubFetch({ data: {} });

    await cancelOrder(7);

    expect(calls[0]?.url).toBe("/api/v1/orders/7");
    expect(calls[0]?.init?.method).toBe("DELETE");
  });
});
