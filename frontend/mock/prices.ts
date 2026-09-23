import { isMarketOpen } from "./calendar.ts";
import { gameDateOf, SCHEDULE } from "./clock.ts";
import type { Instrument } from "./instruments.ts";
import { calendarOf, INSTRUMENTS } from "./instruments.ts";

/**
 * 시세 생성 — 시드 고정 난수 워크.
 *
 * **픽스처를 파일로 만들지 않는다.** 시드가 고정이라 기동 시 메모리에서 생성한다(수십 ms).
 * 600KB JSON 을 커밋하면 MR 디프를 파묻고 "픽스처와 생성기 중 어느 쪽이 진실인가" 를
 * 묻게 된다. 반대로 휴장일 데이터는 커밋한다 — 시드에서 유도되지 않는 외부 사실이다.
 *
 * **종가 생성은 `closesFor` 함수 하나로 격리했다.** 실 시세 데이터가 나오면 그것만 교체하고
 * 나머지(스냅샷 조립·휴장 처리·환율 병기)는 그대로 쓴다.
 */

/** 시드. 고정이므로 같은 시드로 다시 띄우면 값이 똑같다. */
const SEED = 20_260_921;

/**
 * 1일차 시장 국면 — 기능명세서가 1일차를 "코로나 폭락, 유동성 랠리, 코인 광풍" 이라 적었다.
 * 값은 월간 기대 수익률이며 종목별 `beta` 가 여기에 곱해진다.
 */
const REGIMES: readonly { untilGameMonth: string; monthlyDrift: number }[] = [
  { untilGameMonth: "2020-02", monthlyDrift: 0.01 }, // 평온
  { untilGameMonth: "2020-03", monthlyDrift: -0.28 }, // 코로나 폭락
  { untilGameMonth: "2021-11", monthlyDrift: 0.05 }, // 유동성 랠리·코인 광풍
  { untilGameMonth: "9999-99", monthlyDrift: -0.05 }, // 긴축 시작
];

/** 거래일당 변동성. 코인이 크다 — 등락 보드가 심심하지 않아야 한다. */
const DAILY_VOLATILITY = {
  STOCK: 0.02,
  ETF: 0.012,
  INVERSE_ETF: 0.02,
  IPO_STOCK: 0.03,
  CRYPTO: 0.05,
};

/** 한 달의 거래일 수 근사. drift 를 일 단위로 쪼개는 데만 쓴다. */
const TRADING_DAYS_PER_MONTH = 21;

/** 가격이 0 으로 붙어 표시가 무의미해지는 것을 막는 하한. 기준가의 1%. */
const FLOOR_RATIO = 0.01;

export type PricePoint = { gameDate: string; close: number };

/**
 * 그 종목의 종가 시계열. **휴장일은 아예 없다** — 그날 새 종가가 존재하지 않으므로
 * 차트에 포인트를 추가하지 않는다 (`mkt.md`).
 *
 * 실 데이터 교체 지점이다. 시그니처만 지키면 나머지 코드는 바뀌지 않는다.
 */
function closesFor(instrument: Instrument): PricePoint[] {
  const market = calendarOf(instrument);
  const points: PricePoint[] = [];
  const floor = instrument.basePrice * FLOOR_RATIO;
  let close = instrument.basePrice;

  // 재생 축(합집합) 위의 날짜만 돈다. 그중 이 종목의 시장이 연 날에만 종가가 생긴다.
  SCHEDULE.forEach((tick, dayIndex) => {
    if (market !== "ALWAYS_OPEN" && !isMarketOpen(market, tick.gameDate)) {
      return;
    }

    const drift =
      (instrument.beta * monthlyDriftOf(tick.gameMonth)) /
      TRADING_DAYS_PER_MONTH;
    const volatility =
      DAILY_VOLATILITY[instrument.assetType] * Math.abs(instrument.beta);
    // 시드를 (종목, 일차)에서 유도해 생성 순서에 의존하지 않게 한다.
    const noise =
      (random(instrument.instrumentId * 1_000_003 + dayIndex) - 0.5) * 2;

    close = Math.max(floor, close * (1 + drift + volatility * noise));
    points.push({ gameDate: tick.gameDate, close });
  });

  return points;
}

/** 종목별 시계열. 기동 시 한 번 만든다. */
const SERIES = new Map<number, PricePoint[]>(
  INSTRUMENTS.map((instrument) => [
    instrument.instrumentId,
    closesFor(instrument),
  ])
);

/** 일별 매매기준율. 하루 1개 값이고 시세 틱과 함께 갱신된다 (`mkt.md`). */
const USD_KRW = new Map<string, number>(
  SCHEDULE.map((tick, dayIndex) => [
    tick.gameDate,
    // 1,100~1,300원 사이를 완만하게 오간다. 자릿수는 2다.
    round(
      1200 + 100 * Math.sin(dayIndex / 40) + (random(dayIndex) - 0.5) * 12,
      2
    ),
  ])
);

export type PriceSnapshotEntry = {
  instrumentId: number;
  close: string;
  closeKrw: string;
  prevClose: string;
  changeRate: number | null;
  marketClosed: boolean;
  /**
   * 그 일차에 참가자들이 이 종목으로 주고받은 금액, 원화 환산 (`S15P21A605-271`).
   * 실제 시장 거래량이 아니고, 금액이라 문자열이다.
   */
  tradedAmountKrw: string;
};

export type PriceSnapshot = {
  asOfGameDate: string;
  asOfTickSeq: number;
  usdKrwRate: string;
  prices: PriceSnapshotEntry[];
};

/**
 * 그 틱 기준 28종 현재가 + 환율. **`market.prices` WS 페이로드와 같은 형태다** — 프론트가
 * 초기 로드와 틱 수신에 같은 파서·같은 상태 갱신 경로를 쓴다 (`mkt.md`).
 *
 * IPO 종목의 상장 전 노출 차단(`listingStatus`)은 IPO 슬라이스에서 붙는다. 이 슬라이스는
 * 28종 전부를 내려 등락 보드·히트맵이 개발 중에 의미 있게 보이도록 한다.
 */
export function priceSnapshot(tickSeq: number): PriceSnapshot {
  const gameDate = gameDateOf(tickSeq);
  const usdKrwRate = USD_KRW.get(gameDate) ?? 1200;

  return {
    asOfGameDate: gameDate,
    asOfTickSeq: tickSeq,
    usdKrwRate: usdKrwRate.toFixed(2),
    prices: INSTRUMENTS.map((instrument) =>
      entryOf(instrument, gameDate, usdKrwRate, tickSeq)
    ),
  };
}

/** 그 종목의 종가 시계열. 게임 시각 기준으로 잘린다 (`_common.md §9`). */
export function closesUpTo(
  instrument: Instrument,
  gameDate: string
): PricePoint[] {
  const points = SERIES.get(instrument.instrumentId) ?? [];
  return points.filter((point) => point.gameDate <= gameDate);
}

/**
 * 참가자 거래대금(원화). 1일차 편성이라 그 일차의 첫 틱이 곧 1 번이고, 누적은 `tickSeq` 에
 * 비례한다.
 *
 * **틱과 함께 단조 증가시킨다** — 서버 값이 그 일차 첫 틱부터의 누적이라 틱마다 흔들리지
 * 않는다. 난수로 매 틱 다시 뽑으면 정렬이 틱마다 뒤집혀, 정렬이 도는 것인지 값이 튀는
 * 것인지 구분할 수 없다. 종목별로 갈리는 것은 활발한 정도뿐이고 그것도 시드다.
 *
 * **자릿수가 실제와 비슷해야 한다.** 화면이 이 값을 원화 금액으로 접어 내므로
 * (`compactKrw`), 한 자릿수짜리를 주면 축약이 도는지 목에서 확인할 수 없다.
 */
function tradedAmountKrwOf(instrument: Instrument, tickSeq: number): string {
  const activity = ((instrument.instrumentId * 7919) % 23) / 23;
  return String(Math.floor(tickSeq * activity * 0.4) * 12_500_000);
}

function entryOf(
  instrument: Instrument,
  gameDate: string,
  usdKrwRate: number,
  tickSeq: number
): PriceSnapshotEntry {
  const market = calendarOf(instrument);
  const marketClosed =
    market !== "ALWAYS_OPEN" && !isMarketOpen(market, gameDate);
  const points = closesUpTo(instrument, gameDate);
  const latest = points.at(-1)?.close ?? instrument.basePrice;

  // 휴장이면 새 종가가 없다. 직전 종가를 그대로 유지하고 등락률을 비운다 — 보합(0.00)과
  // 구분되어야 한다. "오늘 안 움직였다" 와 "오늘 값이 없다" 는 다른 사실이다.
  const prev = marketClosed
    ? latest
    : (points.at(-2)?.close ?? instrument.basePrice);

  const close = round(latest, instrument.priceScale);
  const prevClose = round(prev, instrument.priceScale);

  return {
    instrumentId: instrument.instrumentId,
    close: close.toFixed(instrument.priceScale),
    // 원화 종목은 close 와 동일하다. 원화 금액의 자릿수는 0 이다.
    closeKrw:
      instrument.currency === "KRW"
        ? close.toFixed(0)
        : Math.round(close * usdKrwRate).toFixed(0),
    prevClose: prevClose.toFixed(instrument.priceScale),
    changeRate:
      marketClosed || prevClose === 0
        ? null
        : round(((close - prevClose) / prevClose) * 100, 2),
    marketClosed,
    tradedAmountKrw: tradedAmountKrwOf(instrument, tickSeq),
  };
}

function monthlyDriftOf(gameMonth: string): number {
  return (
    REGIMES.find((regime) => gameMonth <= regime.untilGameMonth)
      ?.monthlyDrift ?? 0
  );
}

/**
 * 시드 하나에서 0~1 값 하나를 뽑는다. **상태를 들지 않아 생성 순서에 의존하지 않는다** —
 * 종목을 어떤 순서로 만들어도 같은 (종목, 일차)가 같은 값을 받는다.
 *
 * 그 성질을 지키려면 PRNG 를 전진시키는 대신 **시드를 해시해야** 한다. mulberry32 를 한 라운드
 * 돌리는 방식으로는 안 된다 — 그것은 상태에서 전진하도록 설계된 것이라 연속한 시드에 쓰면
 * 출력이 상관되고 평균이 0.5 에서 벗어난다. 실제로 종목 28 에서 편차가 +0.03 이었고, 그 편향이
 * `변동성 × |beta|` 로 증폭되어 584 Tick 복리로 **95배**가 됐다. 국면 drift 가 만든 것이 아니라
 * 난수의 결함이 만든 상승이었다.
 *
 * 그래서 lowbias32 아발란치 파이널라이저를 쓴다. 황금비 상수로 입력을 먼저 흩고(연속 입력의
 * 상관을 끊는다) 두 번의 곱셈·xorshift 로 비트를 섞는다.
 */
function random(offset: number): number {
  // 0x9e3779b1 = 2^32 / 황금비. 홀수라 곱셈이 가역이고 연속 입력을 고르게 벌린다.
  let value = (SEED + Math.imul(offset, 0x9e3779b1)) >>> 0;

  value ^= value >>> 16;
  value = Math.imul(value, 0x21f0aaad) >>> 0;
  value ^= value >>> 15;
  value = Math.imul(value, 0x735a2d97) >>> 0;
  value ^= value >>> 15;

  return (value >>> 0) / 4_294_967_296;
}

function round(value: number, scale: number): number {
  const factor = 10 ** scale;
  return Math.round(value * factor) / factor;
}
