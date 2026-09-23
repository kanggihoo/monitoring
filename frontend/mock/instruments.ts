/**
 * 유니버스 28종.
 *
 * **`instrumentId` 의 정본은 `docs/projects/종목.md` 의 1~28 이다** — 번호가 매겨진 완전한
 * 목록이 거기밖에 없고, 시장 → 구분 순이라 픽스처가 읽기도 좋다.
 *
 * `basePrice` 는 **2020-01 대략값이며 픽스처 전용이다.** 실제 종가가 아니다 — 필요한 것은
 * 정확성이 아니라 자릿수와 규모의 그럴듯함이다(프론트는 금액을 계산하지 않는다,
 * `_common.md §6.1`). 실 시세 데이터가 오면 `prices.ts` 의 `closesFor` 만 교체한다.
 *
 * `beta` 는 시장 국면 drift 에 곱하는 계수다. **인버스 둘만 음수**이고, 그것 하나로
 * 코로나 폭락 구간에서만 오르는 종목이 공짜로 나와 등락 보드·히트맵이 그럴듯해진다.
 *
 * `priceScale` 0·2·4 가 여기서 공존한다 — 국내 12종이 0, 미국·코인 대부분이 2, 소액 코인
 * 둘이 4 다 (`_common.md §6.2`). 전부 2 로 통일하면 MATIC 이 `"0.50"` 이 되어 등락이 안
 * 보이고, 전부 4 로 통일하면 엔비디아가 `"875.4300"` 이 된다.
 */

export type Category = "NASDAQ" | "KRX" | "CRYPTO";

export type AssetType =
  "STOCK" | "ETF" | "INVERSE_ETF" | "IPO_STOCK" | "CRYPTO";

export type Instrument = {
  instrumentId: number;
  name: string;
  category: Category;
  assetType: AssetType;
  currency: "KRW" | "USD";
  priceScale: number;
  /** 픽스처 전용 2020-01 대략값. 실 데이터가 아니다. */
  basePrice: number;
  /** 시장 drift 계수. 음수면 하락 구간에 오른다. */
  beta: number;
  /** IPO 종목의 상장 게임월. 일반 종목은 없다. */
  listingGameMonth?: string;
};

export const INSTRUMENTS: readonly Instrument[] = [
  // 나스닥 12 (기축 통화 USD, 2자리)
  {
    instrumentId: 1,
    name: "Invesco QQQ",
    category: "NASDAQ",
    assetType: "ETF",
    currency: "USD",
    priceScale: 2,
    basePrice: 212.6,
    beta: 1,
  },
  {
    instrumentId: 2,
    name: "ProShares SQQQ",
    category: "NASDAQ",
    assetType: "INVERSE_ETF",
    currency: "USD",
    priceScale: 2,
    basePrice: 148.3,
    beta: -3,
  },
  {
    instrumentId: 3,
    name: "엔비디아",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 59.02,
    beta: 2,
  },
  {
    instrumentId: 4,
    name: "테슬라",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 28.68,
    beta: 1.8,
  },
  {
    instrumentId: 5,
    name: "슈퍼마이크로",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 24.4,
    beta: 2.2,
  },
  {
    instrumentId: 6,
    name: "줌 비디오",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 68.72,
    beta: 1.5,
  },
  {
    instrumentId: 7,
    name: "마이크로스트래티지",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 14.2,
    beta: 2.5,
  },
  {
    instrumentId: 8,
    name: "모더나",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 19.56,
    beta: 1.6,
  },
  {
    instrumentId: 9,
    name: "코인베이스",
    category: "NASDAQ",
    assetType: "IPO_STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 328.28,
    beta: 2.5,
    listingGameMonth: "2021-04",
  },
  {
    instrumentId: 10,
    name: "리비안",
    category: "NASDAQ",
    assetType: "IPO_STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 100.73,
    beta: 2.3,
    listingGameMonth: "2021-11",
  },
  {
    instrumentId: 11,
    name: "ARM",
    category: "NASDAQ",
    assetType: "IPO_STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 63.59,
    beta: 1.8,
    listingGameMonth: "2023-09",
  },
  {
    instrumentId: 12,
    name: "코어위브",
    category: "NASDAQ",
    assetType: "IPO_STOCK",
    currency: "USD",
    priceScale: 2,
    basePrice: 40,
    beta: 1.5,
    listingGameMonth: "2025-03",
  },

  // 국내 12 (원화, 0자리 — 1주 단위 정수 거래라 소수 원이 발생하지 않는다)
  {
    instrumentId: 13,
    name: "KODEX 200",
    category: "KRX",
    assetType: "ETF",
    currency: "KRW",
    priceScale: 0,
    basePrice: 28600,
    beta: 0.8,
  },
  {
    instrumentId: 14,
    name: "KODEX 200선물인버스2X",
    category: "KRX",
    assetType: "INVERSE_ETF",
    currency: "KRW",
    priceScale: 0,
    basePrice: 5400,
    beta: -1.6,
  },
  {
    instrumentId: 15,
    name: "삼성전자",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 55800,
    beta: 0.9,
  },
  {
    instrumentId: 16,
    name: "SK하이닉스",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 94700,
    beta: 1.2,
  },
  {
    instrumentId: 17,
    name: "에코프로",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 12800,
    beta: 2,
  },
  {
    instrumentId: 18,
    name: "한화에어로스페이스",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 34500,
    beta: 1.1,
  },
  {
    instrumentId: 19,
    name: "카카오",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 30700,
    beta: 1.7,
  },
  {
    instrumentId: 20,
    name: "HMM",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 3900,
    beta: 1.9,
  },
  {
    instrumentId: 21,
    name: "카카오게임즈",
    category: "KRX",
    assetType: "IPO_STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 48000,
    beta: 1.8,
    listingGameMonth: "2020-09",
  },
  {
    instrumentId: 22,
    name: "크래프톤",
    category: "KRX",
    assetType: "IPO_STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 498000,
    beta: 1.4,
    listingGameMonth: "2021-08",
  },
  {
    instrumentId: 23,
    name: "두산로보틱스",
    category: "KRX",
    assetType: "IPO_STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 26000,
    beta: 1.6,
    listingGameMonth: "2023-10",
  },
  {
    instrumentId: 24,
    name: "LG CNS",
    category: "KRX",
    assetType: "IPO_STOCK",
    currency: "KRW",
    priceScale: 0,
    basePrice: 61900,
    beta: 1.3,
    listingGameMonth: "2025-02",
  },

  // 코인 4 (달러 결제, 연중무휴 — marketClosed 가 참이 되지 않는다)
  {
    instrumentId: 25,
    name: "이더리움",
    category: "CRYPTO",
    assetType: "CRYPTO",
    currency: "USD",
    priceScale: 2,
    basePrice: 130.8,
    beta: 2.2,
  },
  {
    instrumentId: 26,
    name: "체인링크",
    category: "CRYPTO",
    assetType: "CRYPTO",
    currency: "USD",
    priceScale: 2,
    basePrice: 1.82,
    beta: 2.5,
  },
  {
    instrumentId: 27,
    name: "폴리곤",
    category: "CRYPTO",
    assetType: "CRYPTO",
    currency: "USD",
    priceScale: 4,
    basePrice: 0.0154,
    beta: 2.8,
  },
  {
    instrumentId: 28,
    name: "디센트럴랜드",
    category: "CRYPTO",
    assetType: "CRYPTO",
    currency: "USD",
    priceScale: 4,
    basePrice: 0.0197,
    beta: 2.8,
  },
];

/** 그 종목이 어느 시장 달력을 따르는지. 코인은 달력이 없다. */
export function calendarOf(
  instrument: Instrument
): "KRX" | "NYSE" | "ALWAYS_OPEN" {
  if (instrument.category === "CRYPTO") return "ALWAYS_OPEN";
  return instrument.category === "KRX" ? "KRX" : "NYSE";
}

/** 종목 메타 응답 (`unv.md`). */
export type InstrumentMeta = {
  instrumentId: number;
  name: string;
  category: Category;
  assetType: AssetType;
  currency: "KRW" | "USD";
  priceScale: number;
  watched: boolean;
  listingStatus: "UPCOMING" | "LISTED";
  listedGameDate: string | null;
  isNew: boolean;
};

/**
 * 계약이 정한 필드만 남긴다. `basePrice`·`beta` 는 픽스처 생성용 내부 값이라 응답에 새어
 * 나가면 안 된다 — 실 데이터로 교체하면 사라질 값이 계약처럼 보이게 된다.
 *
 * **`watched` 는 언제나 거짓이다.** 워치리스트는 원장이 목에 주지 않은 경로라
 * (`lib/mock-ledger.test.ts` 의 "워치리스트는 원장에 없어 Spring 으로 간다") 목은 누가
 * 무엇을 담았는지 알 방법이 없다. 아는 척해서 참을 섞으면 목만 보는 개발에서 별이 서버에
 * 저장된 것처럼 보이고, 그 착각은 Spring 을 붙이는 순간 깨진다.
 */
export function publicMetaOf(instrument: Instrument): InstrumentMeta {
  return {
    instrumentId: instrument.instrumentId,
    name: instrument.name,
    category: instrument.category,
    assetType: instrument.assetType,
    currency: instrument.currency,
    priceScale: instrument.priceScale,
    watched: false,
    // **상장 노출은 목이 모델링하지 않는다** (`prices.ts` 의 같은 판단). IPO 종목의 공시·상장
    // 시점을 목이 재현하려면 편성표를 한 벌 더 두어야 하고, 그러면 게임 시계가 둘이 되는
    // 것과 같은 종류의 진실 이중화가 생긴다. 전부 상장된 것으로 답하고 그 사실을 적어 둔다.
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
  };
}

export function instrumentById(instrumentId: number): Instrument | undefined {
  return INSTRUMENTS.find(
    (instrument) => instrument.instrumentId === instrumentId
  );
}

/** 정보 카드 (`info.md`). `cardType` 은 `assetType` 에서 파생된다 — 저장값이 아니다. */
export type InstrumentInfo = {
  instrumentId: number;
  cardType: "STOCK" | "PRODUCT" | "PROJECT";
  summary: string;
  stock: {
    asOfFiscalQuarter: string | null;
    disclosedGameDate: string | null;
    metrics: never[];
    signals: never[];
  } | null;
  product: {
    trackingIndex: string;
    direction: "LONG" | "INVERSE";
    leverage: number;
    expenseRatio: number;
    riskNotice: string | null;
  } | null;
  project: {
    purpose: string;
    supplyModel: string;
    ecosystemRole: string;
  } | null;
};

const RISK_NOTICE =
  "장기 보유 시 변동성에 의해 가치가 잠식될 수 있습니다. 지수가 제자리로 돌아와도 손실이 남을 수 있습니다.";

/** ETF·인버스 4종. 값은 V26 시드와 같은 모양이다. */
const PRODUCT_CARDS: Record<number, InstrumentInfo["product"]> = {
  1: {
    trackingIndex: "나스닥 100",
    direction: "LONG",
    leverage: 1,
    expenseRatio: 0.2,
    riskNotice: null,
  },
  2: {
    trackingIndex: "나스닥 100",
    direction: "INVERSE",
    leverage: 3,
    expenseRatio: 0.95,
    riskNotice: RISK_NOTICE,
  },
  13: {
    trackingIndex: "코스피 200",
    direction: "LONG",
    leverage: 1,
    expenseRatio: 0.15,
    riskNotice: null,
  },
  14: {
    trackingIndex: "코스피 200 선물",
    direction: "INVERSE",
    leverage: 2,
    expenseRatio: 0.64,
    riskNotice: RISK_NOTICE,
  },
};

/** 코인 4종. */
const PROJECT_CARDS: Record<number, InstrumentInfo["project"]> = {
  25: {
    purpose: "네트워크 수수료 지불과 스테이킹에 쓰인다.",
    supplyModel:
      "발행 상한 없음. 스테이킹 보상으로 신규 발행되고 수수료 일부가 소각된다.",
    ecosystemRole: "다수의 토큰과 애플리케이션이 이 네트워크 위에서 동작한다.",
  },
  26: {
    purpose:
      "외부 데이터를 블록체인에 전달하는 노드 운영자에게 보수로 지불된다.",
    supplyModel:
      "총 발행량이 고정되어 있고 일부가 노드 보상으로 순차 배분된다.",
    ecosystemRole:
      "여러 블록체인의 계약이 외부 값을 받을 때 이 네트워크를 거친다.",
  },
  27: {
    purpose: "확장 네트워크의 수수료 지불과 검증자 스테이킹에 쓰인다.",
    supplyModel: "총 발행량 상한이 있고 수수료 일부가 소각된다.",
    ecosystemRole:
      "본체 네트워크의 혼잡을 우회하려는 애플리케이션이 이 위에서 동작한다.",
  },
  28: {
    purpose: "가상 토지와 아이템 거래의 결제 수단이다.",
    supplyModel: "총 발행량 상한이 있고 토지 거래 수수료가 소각된다.",
    ecosystemRole:
      "플랫폼 안의 토지 소유·경매·아이템 제작이 이 코인으로 정산된다.",
  },
};

/**
 * 정보 카드 하나.
 *
 * **주식은 지표가 빈 배열이다.** Spring 도 지금 그렇다 — `instrument_metrics` 에 적재된
 * 행이 없어 "아직 공시된 실적이 없습니다" 경로를 탄다 (V26 은 판정 기준과 ETF·코인 카드만
 * 시드한다). 목이 지표를 꾸며 내면 목만 보는 개발에서 신호등이 채워진 것처럼 보이고, 그
 * 착각은 Spring 을 붙이는 순간 깨진다 — `publicMetaOf` 의 `watched` 와 같은 판단이다.
 */
export function infoOf(instrument: Instrument): InstrumentInfo {
  const id = instrument.instrumentId;
  if (instrument.assetType === "CRYPTO") {
    return {
      instrumentId: id,
      cardType: "PROJECT",
      summary: `${instrument.name} 네트워크의 기축 자산.`,
      stock: null,
      product: null,
      project: PROJECT_CARDS[id] ?? null,
    };
  }
  if (
    instrument.assetType === "ETF" ||
    instrument.assetType === "INVERSE_ETF"
  ) {
    return {
      instrumentId: id,
      cardType: "PRODUCT",
      summary: `${instrument.name}. 지수를 추종하는 상장지수상품이다.`,
      stock: null,
      product: PRODUCT_CARDS[id] ?? null,
      project: null,
    };
  }
  return {
    instrumentId: id,
    cardType: "STOCK",
    summary: `${instrument.name}. 사업 개요는 실 데이터 적재와 함께 들어온다.`,
    stock: {
      asOfFiscalQuarter: null,
      disclosedGameDate: null,
      metrics: [],
      signals: [],
    },
    product: null,
    project: null,
  };
}
