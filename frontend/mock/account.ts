/**
 * 계좌 현금과 환전. **목의 가변 상태다** (`state.ts` 와 같은 성격이고 되감기가 초기화한다).
 *
 * 통조림 응답으로는 "환전하면 부족분이 사라진다" 를 볼 수 없다. 부족분 환전은 두 화면이
 * 이어져야 성립하는 흐름이라 — 미리보기가 부족분을 알려주고, 환전이 잔고를 바꾸고, 다시
 * 부른 미리보기가 부족분을 0 으로 답한다 — 잔고가 움직이지 않으면 아무것도 검증되지 않는다.
 *
 * **보유 종목은 여기 없다.** 매수·매도가 아직 목에 없어 수량이 변하지 않으므로, 평가액의
 * 종목 몫은 스텁의 고정값 그대로다 (`stubs.ts`).
 */

/** 환전 수수료. **항상 0.05%** 이고 무료 타임 대상이 아니다 (`acc.md`). */
export const EXCHANGE_FEE_RATE = 0.05;

export type Direction = "KRW_TO_USD" | "USD_TO_KRW";

/**
 * 시드 현금. `stubs.ts` 의 고정값과 같은 값이고, 되감기가 여기로 되돌린다.
 * 잠긴 몫은 예약·청약이 잡고 있는 것으로 친다 — 목에 예약이 없어 값이 고정이다.
 */
const SEED = {
  krwCash: 38_000_000,
  lockedKrw: 3_000_000,
  usdCash: 8_940,
  lockedUsd: 0,
};

let cash = { ...SEED };

export function accountCash() {
  return cash;
}

/** 되감기가 부른다 (`state.ts` 의 `resetGameState`). */
export function resetAccount(): void {
  cash = { ...SEED };
}

/** 보내는 통화의 소수 자릿수. 원화는 정수, 달러는 2 자리다. */
export function sendScale(direction: Direction): number {
  return direction === "KRW_TO_USD" ? 0 : 2;
}

export type ExchangeQuote = {
  rate: number;
  sendAmount: number;
  fee: number;
  receiveAmount: number;
};

/**
 * 실제 환전 계산. **반올림이 두 번 들어간다** — 수수료를 보내는 통화의 자릿수로 반올림하고,
 * 받는 금액을 받는 통화의 자릿수로 **내림**한다 (`acc.md`).
 *
 * 이 두 번이 부족분 `sendAmount` 를 역산으로 구할 수 없게 만드는 이유이고, 아래
 * `shortfallExchange` 가 공식이 아니라 검산으로 답을 정하는 이유다.
 */
export function quoteExchange(
  direction: Direction,
  sendAmount: number,
  rate: number
): ExchangeQuote {
  const fee = round(
    sendAmount * (EXCHANGE_FEE_RATE / 100),
    sendScale(direction)
  );
  const net = sendAmount - fee;
  const receiveAmount =
    direction === "KRW_TO_USD" ? floor(net / rate, 2) : Math.floor(net * rate);

  return { rate, sendAmount, fee, receiveAmount };
}

/**
 * 부족분을 채우는 환전 요청. **역산 공식이 아니라 실제 환전 계산을 만족하는 최소 금액**이다
 * (`ord.md`).
 *
 * 공식으로 근사한 뒤 한 칸 아래부터 훑는다 — 수수료 반올림의 오차가 반 칸을 넘지 못하므로
 * 답은 근사값에서 위아래 한 칸 안에 있고, 한 칸 위는 언제나 충분하다. 내림한 값을 그대로
 * 쓰면 정확히 부족분만큼 모자라 **환전했는데 주문이 또 막힌다.**
 *
 * @param shortfall 받는 통화 기준 부족분
 * @returns 보내는 통화 기준 금액. 답이 없으면 null (근사가 크게 빗나간 경우로, 일어나지 않는다)
 */
export function shortfallSendAmount(
  direction: Direction,
  shortfall: number,
  rate: number
): number | null {
  const scale = sendScale(direction);
  const step = 10 ** -scale;
  const gross = shortfall * (1 / (1 - EXCHANGE_FEE_RATE / 100));
  const approx = ceil(
    direction === "KRW_TO_USD" ? gross * rate : gross / rate,
    scale
  );

  for (let offset = -1; offset <= 1; offset++) {
    const candidate = round(approx + offset * step, scale);
    if (
      candidate > 0 &&
      quoteExchange(direction, candidate, rate).receiveAmount >= shortfall
    ) {
      return candidate;
    }
  }
  return null;
}

/**
 * 환전 실행. 잠긴 몫은 보낼 수 없다 (`acc.md` 의 `availableCash`).
 *
 * @returns 성공한 견적, 또는 거절 사유. 잔고 부족은 계약상 409 다
 */
export function exchange(
  direction: Direction,
  sendAmount: number,
  rate: number
): { quote: ExchangeQuote } | { error: string } {
  if (!(sendAmount > 0)) {
    return { error: "환전 금액은 0보다 커야 합니다." };
  }
  if (round(sendAmount, sendScale(direction)) !== sendAmount) {
    return { error: "환전 금액의 자릿수가 통화와 맞지 않습니다." };
  }

  const available = availableCash(direction === "KRW_TO_USD" ? "KRW" : "USD");
  if (sendAmount > available) {
    return { error: "보낼 수 있는 금액을 넘었습니다." };
  }

  const quote = quoteExchange(direction, sendAmount, rate);
  if (direction === "KRW_TO_USD") {
    cash.krwCash -= sendAmount;
    cash.usdCash = round(cash.usdCash + quote.receiveAmount, 2);
  } else {
    cash.usdCash = round(cash.usdCash - sendAmount, 2);
    cash.krwCash += quote.receiveAmount;
  }
  return { quote };
}

/** 잠기지 않은 현금. **내림한다** — 표시된 값으로는 낼 수 없는 주문이 가능해 보이면 안 된다. */
export function availableCash(currency: "KRW" | "USD"): number {
  return currency === "KRW"
    ? Math.max(0, cash.krwCash - cash.lockedKrw)
    : Math.max(0, floor(cash.usdCash - cash.lockedUsd, 2));
}

function round(value: number, scale: number): number {
  const factor = 10 ** scale;
  return Math.round(value * factor) / factor;
}

function floor(value: number, scale: number): number {
  const factor = 10 ** scale;
  return Math.floor(value * factor) / factor;
}

function ceil(value: number, scale: number): number {
  const factor = 10 ** scale;
  return Math.ceil(value * factor) / factor;
}
