import { expect, test } from "vitest";

import { sellableQuantity } from "./quick-sell-dialog";

// 예약 매도로 잠긴 수량은 시장가 매도에 쓸 수 없다 (`ord.md`). 보유 수량을 상한으로 삼으면
// `전량` 을 눌렀을 때 서버가 409 로 막는다.
test("잠긴 수량을 뺀 만큼만 팔 수 있다", () => {
  expect(sellableQuantity({ quantity: 10, lockedQuantity: 0 })).toBe(10);
  expect(sellableQuantity({ quantity: 10, lockedQuantity: 4 })).toBe(6);
  expect(sellableQuantity({ quantity: 10, lockedQuantity: 10 })).toBe(0);
});
