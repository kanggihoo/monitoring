import { expect, test } from "vitest";

import { instrumentTags } from "./labels";

/**
 * 분류 꼬리표는 `시장 · 자산 유형 · 업종` 인데, 이 셋이 늘 다른 말인 것은 아니다.
 * 겹치면 하나만 남기고 업종이 없으면 그 자리를 비운다 — 그 두 규칙만 여기서 잡는다.
 */

test("꼬리표_시장·유형·업종이 모두 다르면_셋을 잇는다", () => {
  expect(
    instrumentTags({
      category: "NASDAQ",
      assetType: "STOCK",
      sector: "정보기술",
    })
  ).toBe("미국 · 주식 · 정보기술");
});

test("꼬리표_코인처럼 시장과 유형이 같은 말이면_한 번만 쓴다", () => {
  expect(
    instrumentTags({
      category: "CRYPTO",
      assetType: "CRYPTO",
      sector: "디파이",
    })
  ).toBe("코인 · 디파이");
});

test("꼬리표_업종이 없으면_시장과 유형만 남는다", () => {
  expect(
    instrumentTags({ category: "KRX", assetType: "ETF", sector: null })
  ).toBe("국내 · ETF");
});

test("꼬리표_코인인데 업종도 없으면_한 마디만 남는다", () => {
  expect(
    instrumentTags({ category: "CRYPTO", assetType: "CRYPTO", sector: null })
  ).toBe("코인");
});
