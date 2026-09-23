import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  cancelIpoSubscription,
  IpoShortfallError,
  subscribeIpo,
} from "@/lib/ipo-api";

/**
 * 청약. 보는 것은 **409 의 본문에서 부족분을 꺼내는 경로** 하나다 — 계약이 오류 응답에
 * `data` 를 싣는 예외를 여기서만 쓰기 때문이다 (`ipo.md`).
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
        // 204 는 본문을 가질 수 없다 — `Response` 생성자가 거부한다.
        new Response(status === 204 ? null : JSON.stringify(body), {
          status,
          headers: { "content-type": "application/json" },
        })
      );
    })
  );
  return calls;
}

const SHORTFALL_409 = {
  success: false,
  status: 409,
  message: "달러 잔고가 부족합니다.",
  data: {
    shortfall: "3200.00",
    shortfallExchange: { direction: "KRW_TO_USD", sendAmount: "3981191" },
  },
};

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("청약 신청", () => {
  // 본문이 없다 — 1인 1청약이고 청약 단위가 편성값이라 보낼 값이 없다 (`ipo.md`).
  it("본문 없이 POST 한다", async () => {
    const calls = stubFetch({ data: {} }, { status: 201 });

    await subscribeIpo(3, "USD");

    expect(calls[0]?.url).toBe("/api/v1/ipo/offerings/3/subscription");
    expect(calls[0]?.init?.method).toBe("POST");
    expect(calls[0]?.init?.body).toBeUndefined();
  });

  // 잔고 부족은 토스트가 아니라 환전 확인으로 가야 하므로 오류 종류가 갈린다.
  it("잔고 부족 409 는 부족분을 들고 나온다", async () => {
    stubFetch(SHORTFALL_409, { status: 409 });

    await expect(subscribeIpo(3, "USD")).rejects.toMatchObject({
      currency: "USD",
      shortfall: "3200.00",
      shortfallExchange: { direction: "KRW_TO_USD", sendAmount: "3981191" },
      message: "달러 잔고가 부족합니다.",
    });
  });

  // 마감 뒤 신청도 409 다. 이쪽은 환전으로 해결되지 않으므로 같은 상태 코드라도
  // 부족분이 없으면 보통의 실패다.
  it("환전 정보 없는 409 는 보통의 실패다", async () => {
    stubFetch({ message: "청약이 마감되었습니다." }, { status: 409 });

    const failure = subscribeIpo(3, "KRW");
    await expect(failure).rejects.toThrow("청약이 마감되었습니다.");
    await expect(failure).rejects.not.toBeInstanceOf(IpoShortfallError);
  });
});

describe("청약 취소", () => {
  it("DELETE 한다", async () => {
    const calls = stubFetch({}, { status: 204 });

    await cancelIpoSubscription(3);

    expect(calls[0]?.init?.method).toBe("DELETE");
  });

  it("마감 뒤 취소(409)는 문구를 올린다", async () => {
    stubFetch({ message: "청약이 마감되었습니다." }, { status: 409 });

    await expect(cancelIpoSubscription(3)).rejects.toThrow(
      "청약이 마감되었습니다."
    );
  });
});
