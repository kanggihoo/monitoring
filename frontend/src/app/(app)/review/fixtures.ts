/**
 * 복기 탭 스토리가 나눠 쓰는 리포트 표본. **스토리 전용이다** — 화면 코드가 import 하지
 * 않는다. 한 벌만 두는 이유는 일일·시즌·조각 스토리가 같은 숫자를 보아야 서로 견줄 수
 * 있어서다 (`components/market/fixtures.ts` 와 같은 결).
 */
import type {
  ClosingHolding,
  DailyReport,
  DailyReportEntry,
  ReportInstrument,
  SeasonReport,
  WhatIf,
} from "@/lib/market-types";

/** 3일 시즌. 2일차까지 발표됐고 3일차는 아직 열리지 않았다. */
export const DAYS: DailyReportEntry[] = [
  {
    seasonDay: 1,
    fromGameMonth: "2020-01",
    toGameMonth: "2020-04",
    dailyReturn: 12.4,
    available: true,
  },
  {
    seasonDay: 2,
    fromGameMonth: "2020-05",
    toGameMonth: "2020-08",
    dailyReturn: -3.1,
    available: true,
  },
  {
    seasonDay: 3,
    fromGameMonth: null,
    toGameMonth: null,
    dailyReturn: null,
    available: false,
  },
];

const closes = (from: number, to: number, count = 12) =>
  Array.from({ length: count }, (_, index) => ({
    gameDate: `2020-0${1 + Math.floor(index / 5)}-${String(1 + (index % 5) * 6).padStart(2, "0")}`,
    close: String(from + ((to - from) * index) / (count - 1)),
  }));

export const REPORT_INSTRUMENTS: ReportInstrument[] = [
  {
    instrumentId: 15,
    currency: "KRW",
    priceScale: 0,
    points: closes(52000, 68500),
    markers: [
      {
        executionId: 301,
        gameDate: "2020-01-13",
        side: "BUY",
        quantity: 300,
        price: "54200",
      },
      {
        executionId: 318,
        gameDate: "2020-03-19",
        side: "SELL",
        quantity: 100,
        price: "48900",
      },
    ],
  },
  {
    instrumentId: 3,
    currency: "USD",
    priceScale: 2,
    points: closes(118.4, 134.2),
    markers: [
      {
        executionId: 322,
        gameDate: "2020-02-06",
        side: "BUY",
        quantity: 40,
        price: "118.40",
      },
    ],
  },
];

export const DAILY_REPORT: DailyReport = {
  seasonDay: 2,
  fromGameMonth: "2020-05",
  toGameMonth: "2020-08",
  finalizedAt: "2026-09-15T08:00:00Z",
  summary: {
    dailyReturn: -3.1,
    executionCount: 14,
    totalFeeKrw: "18420",
    realizedPnlKrw: "-412000",
  },
  bestTrade: {
    executionId: 318,
    instrumentId: 3,
    realizedPnlKrw: "750310",
    returnRate: 13.34,
  },
  worstTrade: {
    executionId: 301,
    instrumentId: 15,
    realizedPnlKrw: "-750000",
    returnRate: -3.52,
  },
  instruments: REPORT_INSTRUMENTS,
  patternTags: [
    {
      kind: "PANIC_SELL_LOW",
      executionId: 318,
      instrumentId: 15,
      changeRate: -8.4,
    },
  ],
  // 구성의 합이 곧 합계다 — 84,544,348 + 4,939,652 + 7,416,000 = 96,900,000.
  // 어긋나게 두면 비중이 100 % 를 넘어 화면이 못 쓰게 된다.
  closing: {
    totalValuationKrw: "96900000",
    krwCash: "84544348",
    usdCash: "4079.66",
    usdKrw: "1210.80",
    holdingsValuationKrw: "7416000",
    asOfGameDate: "2020-08-31",
    dailyRank: 12,
    participantCount: 47,
    maxDrawdown: -18.4,
    resetsNextDay: true,
  },
  closingHoldings: [
    // 통화는 유니버스의 실제 값과 맞춘다 — 삼성전자가 달러로 보이면 검수가 멈춘다.
    {
      instrumentId: 3,
      currency: "USD",
      priceScale: 2,
      quantity: 40,
      totalCost: "4736.00",
      avgPrice: "118.40",
      valuationKrw: "5904000",
    },
    {
      instrumentId: 15,
      currency: "KRW",
      priceScale: 0,
      quantity: 120,
      totalCost: "1420000",
      avgPrice: "11833",
      valuationKrw: "1512000",
    },
  ],
  commentary: {
    headline: "폭락에 놀라 던진 자리가 바닥이었습니다.",
    sceneAliases: ["3월의 급락", "반등 첫 주"],
  },
  // 복기 결과지. 선언(온보딩)을 한 사람이고, 다섯 항목 중 하나는 표본이 얇다 —
  // 스토리가 흐린 막대와 또렷한 막대를 한 화면에서 보여 줘야 한다.
  review: {
    investorType: "LEVEL_2",
    typeEmoji: "🔍",
    typeName: "돌다리 두드리는 분석가",
    typeDescription:
      "살펴본 자리만 담고 위험을 나눠 집니다. 확인이 끝나기 전에는 움직이지 않습니다.",
    verdict:
      "시장이 내리막을 걸은 구간이라 기다리는 동안 장이 먼저 지나갔습니다. +9.3%로 상위 62%에 머물렀습니다.",
    profileLevel: 2,
    profileTotal: 10,
    profileName: "안정추구형",
    profilePersona: "돌다리 두드리는 분석가",
    profileItems: [
      {
        item: "INVESTED",
        label: "투입 비중",
        point: 1,
        provisional: false,
        selfPoint: 3,
      },
      {
        item: "DRAWDOWN",
        label: "손실 감내",
        point: 3,
        provisional: false,
        selfPoint: 3,
      },
      {
        item: "ENTRY",
        label: "진입 타이밍",
        point: 2,
        provisional: false,
        selfPoint: 2,
      },
      {
        item: "DIP",
        label: "급락 대응",
        point: 1,
        provisional: false,
        selfPoint: 1,
      },
      {
        item: "STOP",
        label: "손실 정리",
        point: 3,
        provisional: true,
        selfPoint: 2,
      },
    ],
    selfLevel: 3,
    selfTotal: 11,
    selfPersona: "유연한 자산 조율사",
    profileMatch: "NEAR",
    narrative: {
      oneLine: "확인하고 들어가고, 아니다 싶으면 오래 붙들지 않습니다",
      selfView:
        "스스로는 현금을 조금 남기고, 흐름이 살아 있을 때 사는 쪽이라 보셨습니다.",
      actual:
        "지금까지의 거래를 보면 현금을 넉넉히 남긴 채 움직이셨습니다. 떨어진 자리에서는 담기보다 정리하는 쪽이었습니다.",
      traits: [
        {
          axis: "INVESTED",
          title: "현금을 많이 두신 쪽이었습니다",
          body: "현금은 54.2%를 남기셨습니다.",
        },
        {
          axis: "DIP",
          title: "떨어질 때 덜어낸 편이었습니다",
          body: "급락 자리에서 담은 것이 3건, 정리한 것이 9건이었습니다.",
        },
        {
          axis: "DRAWDOWN",
          title: "흔들려도 자리를 지킨 쪽이었습니다",
          body: "최대 낙폭이 18.4%였습니다.",
        },
      ],
      nextStep:
        "지금까지는 현금을 넉넉히 두는 쪽이었습니다. 앞으로는 한 자리만 더 실어 보세요.",
      profileGap:
        "흐름이 살아 있을 때 산다고 하셨는데, 실제로는 충분히 떨어진 자리를 기다리셨습니다.",
      lossTolerance: null,
    },
  },
  regret: [
    {
      kind: "SOLD",
      instrumentId: 15,
      detail: "2020.03에 100주 매도 · 이후 재진입 없음",
      sinceRate: 41.2,
    },
    {
      kind: "HELD",
      instrumentId: 3,
      detail: "2020.02 매수 후 계속 보유",
      sinceRate: 13.3,
    },
  ],
};

/**
 * 1일차 리포트. **`DAILY_REPORT`(2일차)와 다른 값이어야 한다** — 일차 버튼이 정말 다른
 * 리포트를 부르는지 스토리에서 눈으로 보려면, 목이 일차마다 다른 것을 돌려줘야 한다.
 *
 * 리포트는 누적이라 1일차 결과지가 더 흐리다 (`provisional` 이 둘, 판정은 아직 3단계).
 */
export const DAILY_REPORT_DAY1: DailyReport = {
  ...DAILY_REPORT,
  seasonDay: 1,
  fromGameMonth: "2020-01",
  toGameMonth: "2020-04",
  summary: {
    dailyReturn: 12.4,
    executionCount: 6,
    totalFeeKrw: "7300",
    realizedPnlKrw: "1840000",
  },
  commentary: {
    headline: "첫날부터 반등을 정면으로 받았습니다.",
    sceneAliases: ["1월의 시작", "첫 매수"],
  },
  review: {
    ...DAILY_REPORT.review!,
    investorType: "LEVEL_3",
    typeName: "유연한 자산 조율사",
    typeDescription: "지킬 자리와 걸 자리를 나눠 둡니다. 과열되면 덜어냅니다.",
    verdict:
      "지수가 꾸준히 오른 구간이라 들어간 자리가 그대로 회복됐습니다. +12.4%로 상위 24%에 올랐습니다.",
    profileLevel: 3,
    profileTotal: 12,
    profileName: "위험중립형",
    profilePersona: "유연한 자산 조율사",
    profileItems: [
      {
        item: "INVESTED",
        label: "투입 비중",
        point: 2,
        provisional: false,
        selfPoint: 3,
      },
      {
        item: "DRAWDOWN",
        label: "손실 감내",
        point: 3,
        provisional: true,
        selfPoint: 3,
      },
      {
        item: "ENTRY",
        label: "진입 타이밍",
        point: 3,
        provisional: false,
        selfPoint: 2,
      },
      {
        item: "DIP",
        label: "급락 대응",
        point: 2,
        provisional: true,
        selfPoint: 1,
      },
      {
        item: "STOP",
        label: "손실 정리",
        point: null,
        provisional: true,
        selfPoint: 2,
      },
    ],
    profileMatch: "SAME",
    narrative: {
      ...DAILY_REPORT.review!.narrative,
      oneLine: "한쪽으로 치우치지 않고 상황에 맞춰 옮겨 담습니다",
      actual:
        "지금까지의 거래를 보면 오르는 흐름을 따라가셨습니다. 아직 판 자리가 적어 정리 습관은 드러나지 않았습니다.",
      profileGap: null,
      nextStep:
        "지금까지는 흐름을 따라가는 쪽이었습니다. 앞으로는 빠진 자리도 한 번 담아 보세요.",
    },
  },
};

export const SEASON_REPORT: SeasonReport = {
  finalizedAt: "2026-09-16T08:00:00Z",
  metrics: {
    finalReturn: 84.2,
    maxDrawdown: -22.7,
    winRate: 58.3,
    turnover: 3.4,
    totalFeeKrw: "218400",
  },
  benchmarks: [
    { label: "QQQ 단순 보유", returnRate: 62.1 },
    { label: "KODEX 200 단순 보유", returnRate: 18.4 },
    { label: "나", returnRate: 84.2 },
  ],
  finalRank: 12,
  totalParticipants: 328,
  investorType: {
    name: "추격 매수형",
    description: "오른 뒤에 따라 사고, 떨어지면 서둘러 정리하는 쪽입니다.",
    verdict: "3월의 급락에서 던진 자리가 이번 시즌의 갈림길이었습니다.",
  },
  shareCard: {
    headline: "2020년을 84.2%로 지나왔습니다",
    caption: "328명 중 12위",
  },
};

export const WHAT_IF: WhatIf = {
  instrumentId: 3,
  returnRate: 212.4,
  myReturnRate: 84.2,
  days: [
    { seasonDay: 1, returnRate: 38.1, myReturnRate: 12.4 },
    { seasonDay: 2, returnRate: 44.7, myReturnRate: -3.1 },
  ],
};

/**
 * 보유가 많은 날. 종목명 길이와 통화를 섞어 둔다 — 짧은 이름만 넣으면 줄이 접히는
 * 경우를 못 본다. 이름은 화면이 유니버스에서 찾으므로 여기서는 식별자만 의미가 있다.
 */
/** 유니버스에 실제로 있는 식별자. 없는 값을 넣으면 화면이 폴백 이름을 그려 정작
 *  보려던 "종목명이 길 때" 를 못 본다. */
const UNIVERSE_IDS = [1, 2, 3, 6, 9, 13, 15, 19, 25, 27];

export const MANY_HOLDINGS: ClosingHolding[] = [
  {
    currency: "USD",
    priceScale: 2,
    quantity: 2,
    totalCost: "16394.04",
    avgPrice: "8197.02",
    valuationKrw: "110020801",
  },
  {
    currency: "USD",
    priceScale: 2,
    quantity: 153,
    totalCost: "7452.36",
    avgPrice: "48.71",
    valuationKrw: "24021000",
  },
  {
    currency: "USD",
    priceScale: 2,
    quantity: 198,
    totalCost: "13286.79",
    avgPrice: "67.10",
    valuationKrw: "18453600",
  },
  {
    currency: "USD",
    priceScale: 2,
    quantity: 1256,
    totalCost: "3504.74",
    avgPrice: "2.79",
    valuationKrw: "5526400",
  },
  {
    currency: "USD",
    priceScale: 2,
    quantity: 10,
    totalCost: "2148.30",
    avgPrice: "214.83",
    valuationKrw: "3405000",
  },
  {
    currency: "KRW",
    priceScale: 0,
    quantity: 77,
    totalCost: "2310000",
    avgPrice: "30000",
    valuationKrw: "2464000",
  },
  {
    currency: "KRW",
    priceScale: 0,
    quantity: 33,
    totalCost: "1650000",
    avgPrice: "50000",
    valuationKrw: "1617000",
  },
  {
    currency: "KRW",
    priceScale: 0,
    quantity: 20,
    totalCost: "900000",
    avgPrice: "45000",
    valuationKrw: "940000",
  },
  {
    currency: "USD",
    priceScale: 2,
    quantity: 15,
    totalCost: "104.85",
    avgPrice: "6.99",
    valuationKrw: "138000",
  },
  {
    currency: "USD",
    priceScale: 2,
    quantity: 8,
    totalCost: "57.52",
    avgPrice: "7.19",
    valuationKrw: "79000",
  },
].map((holding, index): ClosingHolding => ({
  instrumentId: UNIVERSE_IDS[index] ?? index,
  ...holding,
  currency: holding.currency as "KRW" | "USD",
}));
