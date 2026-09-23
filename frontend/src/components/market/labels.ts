import type {
  InstrumentInfo,
  InstrumentMeta,
  PatternTag,
} from "@/lib/market-types";

/** 화면 문구. 값은 서버 enum 그대로다 (`unv.md`). */
export const CATEGORY_LABEL: Record<InstrumentMeta["category"], string> = {
  NASDAQ: "미국",
  KRX: "국내",
  CRYPTO: "코인",
};

export const ASSET_TYPE_LABEL: Record<InstrumentMeta["assetType"], string> = {
  STOCK: "주식",
  ETF: "ETF",
  INVERSE_ETF: "인버스",
  IPO_STOCK: "공모주",
  CRYPTO: "코인",
};

/**
 * 종목 한 줄 아래에 붙는 분류 꼬리표. `시장 · 자산 유형 · 업종` 을 가운뎃점으로 잇는다.
 *
 * **같은 말을 두 번 쓰지 않는다.** 코인은 시장도 "코인" 이고 자산 유형도 "코인" 이라 그대로
 * 이으면 `코인 · 코인` 이 된다. 같은 문구는 하나만 남긴다 — 코인만 분기하지 않는 이유는
 * 유형이 늘어날 때 같은 일이 또 생기기 때문이다 (판정 기준은 "겹치는가" 하나다).
 *
 * **업종이 없으면 그 자리를 비운다.** 시즌 1 은 편성에 업종이 없어 `null` 이고, 그때는
 * 지금까지와 같은 `시장 · 자산 유형` 두 마디가 된다.
 */
export function instrumentTags(
  instrument: Pick<InstrumentMeta, "category" | "assetType" | "sector">
): string {
  const parts = [
    CATEGORY_LABEL[instrument.category],
    ASSET_TYPE_LABEL[instrument.assetType],
    instrument.sector,
  ];
  return [...new Set(parts.filter((part): part is string => !!part))].join(
    " · "
  );
}

/**
 * 종목 정보 구획의 머리말. **`cardType` 으로 가른다** — 어느 카드가 그려지는지를 결정론적으로
 * 말해 주는 값이 그것이다 (`info.md`, `instrument-info-card` 의 같은 판단).
 *
 * 신호등은 주식에만 있다. ETF·코인에도 "신호등 요약" 이라 적어 두면 화면에 없는 것을 찾게
 * 되고, 사용자는 그것을 고장으로 읽는다.
 */
export const INFO_CARD_HEADING: Record<InstrumentInfo["cardType"], string> = {
  STOCK: "종목 정보 · 신호등 요약",
  PRODUCT: "종목 정보 · 상품 구조",
  PROJECT: "종목 정보 · 프로젝트 개요",
};

/** 복기 자동 태깅 문구 (`rpt.md`, 기획 13.1.4). 판정은 서버가 하고 화면은 이름만 안다. */
export const PATTERN_TAG_LABEL: Record<PatternTag["kind"], string> = {
  PANIC_BUY_HIGH: "고점 매수",
  PANIC_SELL_LOW: "저점 매도",
};
