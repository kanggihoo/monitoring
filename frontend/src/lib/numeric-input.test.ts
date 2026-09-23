import { describe, expect, it } from "vitest";

import {
  AMOUNT_MAX_DIGITS,
  normalizeDecimal,
  normalizeQuantity,
  QUANTITY_MAX,
} from "@/lib/numeric-input";

describe("normalizeQuantity", () => {
  it("숫자가 아닌 글자를 지운다", () => {
    expect(normalizeQuantity("1a2-3")).toBe("123");
    expect(normalizeQuantity("1e5")).toBe("15");
  });

  it("앞자리 0 을 지우고, 0 만 남으면 빈 칸이 된다", () => {
    expect(normalizeQuantity("007")).toBe("7");
    expect(normalizeQuantity("000")).toBe("");
  });

  it("서버가 받는 최대 수량까지는 그대로 통과시킨다", () => {
    expect(normalizeQuantity(String(QUANTITY_MAX))).toBe(String(QUANTITY_MAX));
    expect(normalizeQuantity("1000000000")).toBe("1000000000");
  });

  it("서버 상한을 넘으면 상한 값으로 끊는다", () => {
    // 자릿수로 자르지 않는다 — `30억` 이 `300,000,000` 이 되면 친 것과 다른 수량이다.
    expect(normalizeQuantity("3000000000")).toBe(String(QUANTITY_MAX));
    expect(normalizeQuantity("1".repeat(20))).toBe(String(QUANTITY_MAX));
    // 상한 안에서는 친 값과 `Number` 가 읽은 값이 같다 — 칸의 숫자와 계산이 어긋나지 않는다.
    expect(String(Number(normalizeQuantity("1".repeat(20))))).toBe(
      String(QUANTITY_MAX)
    );
  });
});

describe("normalizeDecimal", () => {
  it("소수점은 하나만 남긴다", () => {
    expect(normalizeDecimal("1.2.3", 2)).toBe("1.23");
    expect(Number(normalizeDecimal("1.2.3", 2))).not.toBeNaN();
  });

  it("음수 부호와 지수 표기를 받지 않는다", () => {
    expect(normalizeDecimal("-100", 2)).toBe("100");
    expect(normalizeDecimal("1e5", 2)).toBe("15");
    expect(normalizeDecimal("abc", 2)).toBe("");
  });

  it("소수 자릿수를 scale 로 끊는다", () => {
    expect(normalizeDecimal("199.7777", 2)).toBe("199.77");
    // 원화에는 보조 단위가 없다. 소수점 아래를 아예 받지 않는다.
    expect(normalizeDecimal("1000.55", 0)).toBe("1000");
    expect(normalizeDecimal("1000.", 0)).toBe("1000");
  });

  it("치는 도중의 소수점을 남긴다", () => {
    expect(normalizeDecimal("1000.", 2)).toBe("1000.");
  });

  it("소수점 앞의 0 하나는 남긴다", () => {
    expect(normalizeDecimal("0.5", 2)).toBe("0.5");
    expect(normalizeDecimal("00.5", 2)).toBe("0.5");
    expect(normalizeDecimal("007", 2)).toBe("7");
  });

  it("정수부 자릿수 상한을 넘기지 않는다", () => {
    const value = normalizeDecimal("1".repeat(AMOUNT_MAX_DIGITS + 5), 2);
    expect(value).toHaveLength(AMOUNT_MAX_DIGITS);
    expect(String(Number(value))).toBe(value);
  });
});
