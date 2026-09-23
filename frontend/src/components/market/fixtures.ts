import type {
  Awards,
  ClockSnapshot,
  Heatmap,
  InstrumentMeta,
  Leaderboard,
  PositionItem,
  PriceEntry,
  RankingMe,
  Valuation,
} from "@/lib/market-types";
import type { Series } from "@/stores/tick-store";

/** 스토리·테스트 공용 픽스처. 값은 계약 형태만 지키는 예시다. */

export const INSTRUMENTS: InstrumentMeta[] = [
  {
    instrumentId: 1,
    name: "Invesco QQQ",
    category: "NASDAQ",
    assetType: "ETF",
    currency: "USD",
    priceScale: 2,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "지수",
  },
  {
    instrumentId: 2,
    name: "ProShares SQQQ",
    category: "NASDAQ",
    assetType: "INVERSE_ETF",
    currency: "USD",
    priceScale: 2,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "지수",
  },
  {
    instrumentId: 3,
    name: "엔비디아",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "정보기술",
  },
  {
    instrumentId: 6,
    name: "줌 비디오",
    category: "NASDAQ",
    assetType: "STOCK",
    currency: "USD",
    priceScale: 2,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "정보기술",
  },
  {
    instrumentId: 9,
    name: "코인베이스",
    category: "NASDAQ",
    assetType: "IPO_STOCK",
    currency: "USD",
    priceScale: 2,
    watched: false,
    // 상장 전 한 건을 남겨 둔다 — 가격이 없는 줄이 어떻게 보이는지가 이 픽스처로만 확인된다.
    listingStatus: "UPCOMING",
    listedGameDate: "2021-04-14",
    isNew: false,
    sector: "산업재",
  },
  {
    instrumentId: 13,
    name: "KODEX 200",
    category: "KRX",
    assetType: "ETF",
    currency: "KRW",
    priceScale: 0,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "지수",
  },
  {
    instrumentId: 15,
    name: "삼성전자",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "정보기술",
  },
  {
    instrumentId: 19,
    name: "카카오",
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "정보기술",
  },
  {
    instrumentId: 25,
    name: "이더리움",
    category: "CRYPTO",
    assetType: "CRYPTO",
    currency: "USD",
    priceScale: 2,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "디파이",
  },
  {
    instrumentId: 27,
    name: "폴리곤",
    category: "CRYPTO",
    assetType: "CRYPTO",
    currency: "USD",
    priceScale: 4,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "디파이",
  },
];

const entry = (
  instrumentId: number,
  close: string,
  closeKrw: string,
  changeRate: number | null,
  tradedAmountKrw: string
): PriceEntry => ({
  instrumentId,
  close,
  closeKrw,
  prevClose: close,
  changeRate,
  marketClosed: changeRate === null,
  tradedAmountKrw,
});

/**
 * **거래대금은 등락률과 순서가 다르게 깔려 있다.** 두 정렬이 같은 줄 순서를 내면 시안에서
 * 거래대금 정렬이 도는지 확인할 수 없다. 거래가 없는 종목(15)도 하나 둔다 — 그 값은 서버가
 * 비우지 않고 `"0"` 으로 채워 보낸다.
 *
 * **자릿수를 일부러 흩어 놓는다.** 금액이 문자열로 오므로 수로 바꾸지 않고 비교하면
 * `"9..."` 가 `"48..."` 보다 크게 서는데, 자릿수가 다 같으면 그 버그가 드러나지 않는다.
 */
export const PRICES: Record<number, PriceEntry> = Object.fromEntries(
  [
    entry(1, "233.46", "290000", 2.0, "3700000000"),
    entry(2, "39.52", "49100", -2.9, "1200000000"),
    entry(3, "5.33", "6620", 2.5, "400000000"),
    entry(6, "58.48", "72600", -8.9, "5800000000"),
    entry(9, "279.57", "347200", -1.73, "2100000000"),
    entry(13, "29467", "29467", 0.4, "900000000"),
    entry(15, "53084", "53084", null, "0"),
    entry(19, "38150", "38150", 1.77, "14300000000"),
    entry(25, "1750.12", "2173000", 6.05, "7600000000"),
    entry(27, "0.0294", "37", 7.3, "200000000"),
  ].map((price) => [price.instrumentId, price])
);

export const SERIES: Series = {
  instrumentId: 6,
  currency: "USD",
  priceScale: 2,
  points: Array.from({ length: 120 }, (_, i) => {
    const date = new Date(Date.UTC(2020, 0, 2 + Math.floor(i * 1.4)));
    return {
      gameDate: date.toISOString().slice(0, 10),
      close: (
        60 +
        25 * Math.sin(i / 14) +
        8 * Math.sin(i / 3.3) +
        i * 0.1
      ).toFixed(2),
    };
  }),
};

export const VALUATION: Valuation = {
  asOfGameDate: "2020-08-14",
  asOfTickSeq: 137,
  usdKrwRate: "1187.20",
  totalValuationKrw: "91411674",
  krwCash: "38000000",
  usdCash: "8940.00",
  lockedKrw: "0",
  lockedUsd: "0.00",
  holdingsValuationKrw: "42800000",
  startingBalanceKrw: "100000000",
  cumulativeReturn: -8.59,
  dailyReturn: 0.13,
};

export const RANKING_ME: RankingMe = {
  asOfTickSeq: 137,
  seasonRank: 328,
  seasonRankDelta: -4,
  seasonReturnRate: -8.59,
  dailyRank: 88,
  dailyRankDelta: 21,
  dailyReturnRate: 0.13,
  valuationRank: 331,
  valuationRankDelta: -3,
  totalValuationKrw: "91411674",
  totalParticipants: 542,
  blackout: false,
};

/** 스토리가 시계를 세울 때 쓴다. `serverTime` 을 지금으로 두면 오프셋이 0 이다. */
export const clockSnapshot = (
  overrides: Partial<ClockSnapshot> = {}
): ClockSnapshot => {
  const now = Date.now();
  return {
    phase: "TRADING",
    phaseEndsAt: new Date(now + 2 * 3600_000 + 8 * 60_000).toISOString(),
    serverTime: new Date(now).toISOString(),
    gameDate: "2020-08-14",
    gameMonth: "2020-08",
    tickSeq: 137,
    nextTickAt: new Date(now + 31_000).toISOString(),
    feeFree: false,
    feeFreeEndsAt: null,
    leaderboardBlackout: false,
    seasonDay: 1,
    ...overrides,
  };
};

/**
 * 보유 포지션. 원화·달러, 평가익·평가손, 잠긴 수량을 한 벌에 섞어 둔다 — 보유 종목 표와
 * 자산 배분 카드가 갈리는 지점이 전부 이 네 줄에 있다.
 *
 * 비중 합이 40.1 이라 나머지 59.9 가 현금으로 잡힌다.
 */
export const POSITIONS: PositionItem[] = [
  {
    instrumentId: 15, // 삼성전자 · KRX
    currency: "KRW",
    quantity: 300,
    lockedQuantity: 0,
    totalCost: "21300000",
    avgPrice: "71000",
    currentPrice: "68500",
    valuation: "20550000",
    valuationKrw: "20550000",
    unrealizedPnl: "-750000",
    unrealizedPnlKrw: "-750000",
    returnRate: -3.52,
    weight: 22.5,
  },
  {
    instrumentId: 3, // 엔비디아 · NASDAQ — 예약 매도로 10주가 잠겨 있다
    currency: "USD",
    quantity: 40,
    lockedQuantity: 10,
    totalCost: "4736.00",
    avgPrice: "118.40",
    currentPrice: "134.20",
    valuation: "5368.00",
    valuationKrw: "6372889",
    unrealizedPnl: "632.00",
    unrealizedPnlKrw: "750310",
    returnRate: 13.34,
    weight: 7.0,
  },
  {
    instrumentId: 25, // 이더리움 · CRYPTO
    currency: "USD",
    quantity: 12,
    lockedQuantity: 0,
    totalCost: "4825.20",
    avgPrice: "402.10",
    currentPrice: "371.55",
    valuation: "4458.60",
    valuationKrw: "5293249",
    unrealizedPnl: "-366.60",
    unrealizedPnlKrw: "-435188",
    returnRate: -7.6,
    weight: 5.8,
  },
  {
    instrumentId: 13, // KODEX 200 · KRX
    currency: "KRW",
    quantity: 150,
    lockedQuantity: 0,
    totalCost: "4215000",
    avgPrice: "28100",
    currentPrice: "29450",
    valuation: "4417500",
    valuationKrw: "4417500",
    unrealizedPnl: "202500",
    unrealizedPnlKrw: "202500",
    returnRate: 4.8,
    weight: 4.8,
  },
];

/** 리더보드 한 판. 내 줄이 섞여 있다 — 고정 줄과 목록의 값이 같아야 한다 (`rank.md`). */
export const LEADERBOARD: Leaderboard = {
  track: "DAILY",
  asOfTickSeq: 137,
  asOfGameDate: "2020-08-14",
  blackout: false,
  finalized: false,
  totalParticipants: 542,
  content: [
    ["느린손", 42.18, 5, 37],
    ["리플레이어", 31.4, 3, 12],
    ["2011생존자", 28.05, 8, 64],
    ["PanicBuyer", 12.7, 2, 91],
    ["Satoshi_Fan", 4.02, 1, 6],
    ["홍길동", -8.59, 4, 23],
  ].map(([nickname, returnRate, holdingCount, tradeCount], index) => ({
    rank: index + 1,
    rankDelta: [0, 1, -1, 3, 0, -2][index] ?? 0,
    nickname: nickname as string,
    avatarId: index + 1,
    returnRate: returnRate as number,
    totalValuationKrw: String(
      Math.round(100_000_000 * (1 + (returnRate as number) / 100))
    ),
    holdingCount: holdingCount as number,
    tradeCount: tradeCount as number,
    // 이 판은 `DAILY` 다 — 점수는 `POINT` 트랙에서만 채워진다 (`rank.md`).
    points: null,
    wins: null,
    isMe: nickname === "홍길동",
  })),
  page: 0,
  size: 20,
  totalElements: 542,
  totalPages: 28,
  hasNext: true,
};

export const HEATMAP: Heatmap = {
  asOfTickSeq: 137,
  asOfGameDate: "2020-08-14",
  totalValuationKrw: "412000000000",
  buckets: [
    {
      category: "NASDAQ",
      valuationKrw: "148320000000",
      weight: 36,
      weightDelta: -1.82,
    },
    {
      category: "KRX",
      valuationKrw: "102000000000",
      weight: 24.76,
      weightDelta: 0.41,
    },
    {
      category: "CRYPTO",
      valuationKrw: "41200000000",
      weight: 10,
      weightDelta: 2.11,
    },
    {
      category: "CASH",
      valuationKrw: "120480000000",
      weight: 29.24,
      weightDelta: -0.7,
    },
  ],
};

/**
 * 1·2일차만 발표된 상태. 시즌 우승자는 마지막 일차 발표 전이라 **빈 배열**이다.
 *
 * **일차마다 1~5위가 온다** (`S15P21A605-265`) — 1위만 담아 두면 화면이 등수를 보는지
 * 픽스처가 말하지 못한다. 여기서는 2일차 2위가 동점이라 그 줄이 접힌다.
 */
export const AWARDS: Awards = {
  daily: [
    {
      seasonDay: 1,
      rank: 1,
      nickname: "느린손",
      avatarId: 1,
      returnRate: 42.18,
      title: "일간 수익왕",
      announcedAt: "2026-09-14T08:00:00Z",
    },
    {
      seasonDay: 1,
      rank: 2,
      nickname: "돌다리",
      avatarId: 3,
      returnRate: 28.9,
      title: "일간 수익왕",
      announcedAt: "2026-09-14T08:00:00Z",
    },
    {
      seasonDay: 2,
      rank: 1,
      nickname: "리플레이어",
      avatarId: 2,
      returnRate: 31.4,
      title: "일간 수익왕",
      announcedAt: "2026-09-15T08:00:00Z",
    },
    {
      seasonDay: 2,
      rank: 2,
      nickname: "장기말",
      avatarId: 4,
      returnRate: 12.06,
      title: "일간 수익왕",
      announcedAt: "2026-09-15T08:00:00Z",
    },
    {
      seasonDay: 2,
      rank: 2,
      nickname: "한강뷰",
      avatarId: 5,
      returnRate: 12.06,
      title: "일간 수익왕",
      announcedAt: "2026-09-15T08:00:00Z",
    },
  ],
  final: [],
};

/**
 * **아무도 거래하지 않은 일차.** 전원이 0.00% 라 동점 판정이 표시 자릿수에서 나면서
 * 수백 명이 함께 1위가 된다 (`rank.md` §동점 처리) — 관측된 화면에서는 이 줄이 전부
 * 펴진 채로 그려져 우측 열이 바깥으로 밀렸다. 접기가 풀리면 여기서 먼저 드러난다.
 */
export const AWARDS_TIED: Awards = {
  daily: Array.from({ length: 120 }, (_, index) => ({
    seasonDay: 2 as const,
    rank: 1,
    nickname: `투자자${4000 + index}`,
    avatarId: 1,
    returnRate: 0,
    title: "일간 수익왕",
    announcedAt: "2026-09-15T08:00:00Z",
  })),
  final: [],
};

/**
 * **자릿수가 터지는 값.** 관측된 것에서 출발한다 — 1일차에 시드 1억으로 `+48,250.78%` 를
 * 찍은 사람이 실제로 있었고 그때 평가액이 483억이다.
 *
 * 시즌은 3일이고 매일 같은 판이 다시 열리므로, 2·3일차는 그 위에 배율이 한 번씩 더 걸린다고
 * 본다 (여기서는 자릿수가 어떻게 넘치는지만 보면 되므로 10배씩 잡았다 — 실제 상한은 편성이
 * 정한다). **스토리 전용이다** — 열 폭과 줄바꿈이 몇 자리에서 무너지는지 미리 보려고 둔다.
 */
export const EXTREME_DAYS = [
  { label: "1일차 (관측)", returnRate: 48_250.78, valuationKrw: "48250780000" },
  {
    label: "2일차 (추정)",
    returnRate: 482_507.8,
    valuationKrw: "482507800000",
  },
  {
    label: "3일차 (추정)",
    returnRate: 4_825_078.0,
    valuationKrw: "4825078000000",
  },
] as const;

const EXTREME = EXTREME_DAYS[2];

/** 3일차 추정치를 앉힌 계좌. 총자산 4.8조 · 누적 +482만%. */
export const EXTREME_VALUATION: Valuation = {
  ...VALUATION,
  totalValuationKrw: EXTREME.valuationKrw,
  krwCash: "2412539000000",
  usdCash: "2031925252.53",
  holdingsValuationKrw: "2412539000000",
  cumulativeReturn: EXTREME.returnRate,
  dailyReturn: 128_430.55,
};

export const EXTREME_RANKING_ME: RankingMe = {
  ...RANKING_ME,
  seasonRank: 1,
  seasonRankDelta: 327,
  seasonReturnRate: EXTREME.returnRate,
  dailyRank: 1,
  dailyRankDelta: 87,
  dailyReturnRate: 128_430.55,
  valuationRank: 1,
  valuationRankDelta: 330,
  totalValuationKrw: EXTREME.valuationKrw,
};

/** 상위권이 전부 터진 판. 아래로 내려갈수록 자릿수가 줄어 열이 언제부터 버티는지 보인다. */
export const EXTREME_LEADERBOARD: Leaderboard = {
  ...LEADERBOARD,
  track: "VALUATION",
  content: LEADERBOARD.content.map((entry, index) => {
    const scale = 10 ** (EXTREME_DAYS.length - Math.min(index, 3));
    return {
      ...entry,
      returnRate: (EXTREME_DAYS[0]!.returnRate * scale) / 10,
      totalValuationKrw: String(
        Math.round((Number(EXTREME_DAYS[0]!.valuationKrw) * scale) / 10)
      ),
    };
  }),
};

/**
 * 포인트 합산 판 (`S15P21A605-250`). **다른 셋과 같은 형태 안에서 네 가지가 다르다** —
 * 점수·1등 횟수가 채워지고, 등락이 전원 0 이며, 기준 틱과 거래일이 없다.
 *
 * 값은 3일치를 가정한다. 1위와 2위가 **같은 점수(180)에서 1등 횟수로 갈리는 자리**를
 * 일부러 넣었다 — 동점이 실제로 나는 곳이 여기라고 250 이 적었고, 그 줄이 어떻게 보이는지가
 * 이 픽스처로만 확인된다. 수익률은 일간 수익률의 단순 합계다 (누적이 아니다).
 */
export const POINT_LEADERBOARD: Leaderboard = {
  ...LEADERBOARD,
  track: "POINT",
  asOfTickSeq: null,
  asOfGameDate: null,
  finalized: true,
  content: [
    ["느린손", 231.4, 180, 2],
    ["리플레이어", 402.8, 180, 1],
    ["2011생존자", 96.2, 145, 0],
    ["PanicBuyer", 41.7, 110, 0],
    ["Satoshi_Fan", 12.05, 30, 0],
    ["홍길동", -8.59, 20, 0],
  ].map(([nickname, returnRate, points, wins], index) => ({
    ...LEADERBOARD.content[index]!,
    rank: index + 1,
    // 하루 한 번만 바뀌는 값이라 비교할 직전 틱이 없다 — 서버가 언제나 0 을 준다.
    rankDelta: 0,
    nickname: nickname as string,
    returnRate: returnRate as number,
    points: points as number,
    wins: wins as number,
    isMe: nickname === "홍길동",
  })),
};
