import { expect, test } from "vitest";

import { amendChange } from "./reserved-orders";

/** 지금 걸려 있는 예약. 계약이 지정가를 자릿수 붙인 문자열로 준다 (`ord.md`). */
const order = { quantity: 3, limitPrice: "40000.00" };

test("바뀐 칸만 보낸다", () => {
  expect(amendChange(order, { quantity: "5", limitPrice: "40000.00" })).toEqual(
    {
      quantity: 5,
    }
  );
  expect(amendChange(order, { quantity: "3", limitPrice: "41000" })).toEqual({
    limitPrice: 41000,
  });
  expect(amendChange(order, { quantity: "5", limitPrice: "41000" })).toEqual({
    quantity: 5,
    limitPrice: 41000,
  });
});

// 자릿수만 다른 같은 값을 정정으로 보내면, 손대지 않은 칸이 매번 서버로 나간다.
test("표기만 다른 같은 값은 바뀐 것이 아니다", () => {
  expect(amendChange(order, { quantity: "3", limitPrice: "40000" })).toEqual(
    {}
  );
  expect(amendChange(order, { quantity: "3", limitPrice: "40000.00" })).toEqual(
    {}
  );
});

// 지우는 도중의 입력을 그대로 보내면 서버가 거절하고, 사용자에게는 정정이 고장 난 것으로 보인다.
test("빈 칸과 0 이하는 그대로 둔다", () => {
  expect(amendChange(order, { quantity: "", limitPrice: "" })).toEqual({});
  expect(amendChange(order, { quantity: "0", limitPrice: "-1" })).toEqual({});
});

// 수량은 1주 단위 정수다 (기획 6.5.2). 소수 보유가 없어 금액 지정 주문도 없다.
test("수량은 내림한 정수로 보낸다", () => {
  expect(amendChange(order, { quantity: "5.9", limitPrice: "" })).toEqual({
    quantity: 5,
  });
});
