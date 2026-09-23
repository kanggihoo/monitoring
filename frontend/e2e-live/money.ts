/**
 * 금액 계약 (`docs/qa/user-checklist.md` §금액 계약).
 *
 * **부동소수로 계산하지 않는다.** `0.05 / 100` 을 `number` 로 곱하면 서버의 `BigDecimal`
 * 과 마지막 자리에서 갈리고, 그 한 자리가 곧 "환전했는데 주문이 또 막힌다" 다. 값을
 * 자릿수만큼 올린 **정수(`bigint`)** 로 다루고 반올림 방향을 명시한다.
 */

/** 원화 금액·원화 현금. */
export const KRW_SCALE = 0;
/** 달러 금액·**달러 현금**. 종목 가격의 `priceScale` 과 다른 값이다. */
export const USD_SCALE = 2;
/** 환율. */
export const RATE_SCALE = 2;

export type Currency = "KRW" | "USD";
export type ExchangeDirection = "KRW_TO_USD" | "USD_TO_KRW";

/** 현금·체결 금액의 자릿수. **종목 가격은 여기 해당하지 않는다** (`priceScale` 을 쓴다). */
export const cashScale = (currency: Currency) =>
  currency === "KRW" ? KRW_SCALE : USD_SCALE;

/** 환전에서 **보내는** 통화의 자릿수. 수수료가 이 자릿수로 반올림된다. */
export const sendScale = (direction: ExchangeDirection) =>
  direction === "KRW_TO_USD" ? KRW_SCALE : USD_SCALE;

/** 환전에서 **받는** 통화의 자릿수. 수령액이 이 자릿수로 내림된다. */
export const receiveScale = (direction: ExchangeDirection) =>
  direction === "KRW_TO_USD" ? USD_SCALE : KRW_SCALE;

/** `"839.06"`, 2 → 정수 `83906`. 자릿수가 넘치면 던진다 — 조용히 버리면 판정이 거짓이 된다. */
export function scaled(value: string | number, scale: number): bigint {
  const text = String(value).trim();
  const match = /^(-?)(\d+)(?:\.(\d*))?$/.exec(text);
  if (!match)
    throw new Error(`금액으로 읽을 수 없습니다: ${JSON.stringify(value)}`);

  const [, sign, whole, fraction = ""] = match;
  if (fraction.replace(/0+$/, "").length > scale) {
    throw new Error(`자릿수 ${scale} 를 넘는 금액입니다: ${text}`);
  }

  const digits = `${whole}${fraction.padEnd(scale, "0").slice(0, scale)}`;
  return BigInt(`${sign}${digits}`);
}

/** 정수 `83906`, 2 → `"839.06"`. 서버 응답과 문자열로 맞대려고 자릿수를 채워 둔다. */
export function unscaled(value: bigint, scale: number): string {
  const negative = value < BigInt(0);
  const digits = (negative ? -value : value)
    .toString()
    .padStart(scale + 1, "0");
  const whole = digits.slice(0, digits.length - scale);
  const fraction = scale === 0 ? "" : `.${digits.slice(digits.length - scale)}`;
  return `${negative ? "-" : ""}${whole}${fraction}`;
}

/** 양수 나눗셈의 **반올림**(HALF_UP). 수수료가 이 방향이다. */
const divHalfUp = (numerator: bigint, denominator: bigint) =>
  (BigInt(2) * numerator + denominator) / (BigInt(2) * denominator);

/**
 * 매도 수수료 `금액 × 0.15%`. **보내는 통화 자릿수로 반올림**한다.
 *
 * 무료 타임(`clock.feeFree`)에는 서버가 `0` 을 매기므로 호출하는 쪽이 구간을 먼저 본다.
 */
export const sellFee = (amount: string, currency: Currency) =>
  unscaled(
    divHalfUp(scaled(amount, cashScale(currency)) * BigInt(15), BigInt(10000)),
    cashScale(currency)
  );

/** 환전 수수료 `보낼 금액 × 0.05%`. **보내는 통화 자릿수로 반올림**. */
export const exchangeFee = (sendAmount: string, direction: ExchangeDirection) =>
  unscaled(
    divHalfUp(
      scaled(sendAmount, sendScale(direction)) * BigInt(5),
      BigInt(10000)
    ),
    sendScale(direction)
  );

/**
 * 환전 수령액. **방향에 따라 나누기와 곱하기가 갈린다** — `rate` 가 원/달러 매매기준율
 * (1달러가 몇 원인가)이라 대칭이 아니다. 받는 통화 자릿수로 **내림**한다.
 */
export function exchangeReceive(
  sendAmount: string,
  fee: string,
  rate: string,
  direction: ExchangeDirection
): string {
  const scale = sendScale(direction);
  const net = scaled(sendAmount, scale) - scaled(fee, scale);
  const rateScaled = scaled(rate, RATE_SCALE);

  if (direction === "KRW_TO_USD") {
    // net(원) ÷ rate → 달러. 달러 2자리로 맞추려고 10^(2+RATE_SCALE) 을 곱해 둔다.
    return unscaled((net * BigInt(10000)) / rateScaled, USD_SCALE);
  }
  // net(달러, 2자리) × rate → 원. 두 자릿수를 합친 10^(2+RATE_SCALE) 로 되돌린다.
  return unscaled((net * rateScaled) / BigInt(10000), KRW_SCALE);
}

/** `기준가 × 수량`. 가격은 `priceScale`, 결과는 통화 자릿수다. */
export const orderAmount = (
  referencePrice: string,
  quantity: number,
  priceScale: number,
  currency: Currency
) =>
  unscaled(
    rescale(
      scaled(referencePrice, priceScale) * BigInt(quantity),
      priceScale,
      cashScale(currency)
    ),
    cashScale(currency)
  );

/**
 * 자릿수를 옮긴다. **줄일 때만 반올림이 생긴다** — 서버가 `setScale(HALF_UP)` 을 쓰는
 * 자리라 여기서도 같은 방향을 쓴다.
 */
function rescale(value: bigint, from: number, to: number): bigint {
  if (to >= from) return value * BigInt(10) ** BigInt(to - from);
  return divHalfUp(value, BigInt(10) ** BigInt(from - to));
}

/** 두 금액의 차. 문자열로 돌려 서버 응답과 그대로 비교할 수 있게 둔다. */
export const diff = (after: string, before: string, scale: number) =>
  unscaled(scaled(after, scale) - scaled(before, scale), scale);

/** 문자열 금액의 대소 비교. `Number` 로 바꾸면 큰 원화에서 정밀도를 잃는다. */
export const gte = (left: string, right: string, scale: number) =>
  scaled(left, scale) >= scaled(right, scale);

/**
 * 화면에 찍힌 `1,234,567원` · `$1,234.56` 에서 금액을 읽는다.
 *
 * **첫 숫자만 읽는다.** 헤더의 자산 칸은 금액 뒤에 등락률이 붙어 있어 (`100,000,000원
 * -0.02%`) 숫자가 아닌 글자를 전부 지우면 `100000000-0.02` 라는 없는 수가 된다.
 */
export function parseMoney(text: string): number {
  const match = /-?\d[\d,]*(?:\.\d+)?/.exec(text);
  if (!match)
    throw new Error(`숫자를 찾지 못했습니다: ${JSON.stringify(text)}`);
  return Number(match[0].replaceAll(",", ""));
}

/**
 * 헤더의 **축약 금액**(`1.0억원` · `9,500만원`)을 수로 되돌린다.
 *
 * 헤더는 칸이 좁아 접어서 적는다 (`lib/format.ts` 의 `compactKrw`). 그래서 계좌 요약의
 * 정확한 값과 **같은 수가 될 수 없다** — 비교는 표시 단위의 반만큼을 허용해야 한다.
 * `parseMoney` 로 읽으면 `1.0억원` 이 `1` 이 되어 어떤 비교도 무의미해진다.
 *
 * @param text 화면에 적힌 축약 금액
 * @returns 되돌린 값과 그 표기가 허용하는 오차
 */
export function parseCompactKrw(text: string): {
  value: number;
  tolerance: number;
} {
  const number = parseMoney(text);
  if (text.includes("억원")) return { value: number * 1e8, tolerance: 0.05e8 };
  if (text.includes("만원")) return { value: number * 1e4, tolerance: 0.5e4 };
  return { value: number, tolerance: 0.5 };
}
