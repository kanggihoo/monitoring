/** 마켓 리플레이 공용 숫자 포맷. 계좌 기준 통화는 원화다 (기획 2장, 환율 도입). */

export const won = (value: number) =>
  `${new Intl.NumberFormat("ko-KR").format(Math.round(value))}원`;

/**
 * 달러 금액. 계약의 문자열(`"12000.00"`)도 그대로 받는다 — `krw` 와 같은 자리다.
 *
 * **`style: "currency"` 가 소수 두 자리를 위아래로 함께 고정한다.** 직접 짤 때 흔히
 * `minimumFractionDigits` 만 적는데, 그러면 최대 자릿수가 Intl 기본값(3)이 되어 계약이
 * 두 자리를 보장하는 동안에만(`_common.md §6.2`) 우연히 맞는 표기가 된다.
 */
export const usd = (value: string | number) =>
  new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
  }).format(Number(value));

/**
 * 부호를 붙인 고정 소수. 음수는 하이픈(-)이 아니라 진짜 빼기 기호(−, U+2212)를 쓴다.
 * 하이픈은 폭이 좁아 tabular-nums 열에서 자릿수가 어긋나 보인다.
 */
export const signed = (value: number, digits = 2) => {
  const sign = value > 0 ? "+" : value < 0 ? "−" : "";
  return `${sign}${Math.abs(value).toFixed(digits)}`;
};

/** mm:ss. 카운트다운 표기. */
export const mmss = (seconds: number) => {
  const safe = Math.max(0, Math.floor(seconds));
  return `${String(Math.floor(safe / 60)).padStart(2, "0")}:${String(safe % 60).padStart(2, "0")}`;
};

/** 게임 내 시각은 일(日)까지 의미가 없다. 화면에는 "2020년 3월"까지만 낸다. */
export const gameMonth = (isoMonth: string) => {
  const [year, month] = isoMonth.split("-");
  return `${year}년 ${Number(month)}월`;
};

/**
 * 주식 수량. **천 단위로 끊는다** — 잔주가 만 단위를 넘으면(저가 코인·주식) `2449073주` 가
 * 한 덩어리로 보여 자릿수를 세어야 읽힌다.
 *
 * 1 주 단위 정수다 (기획 6.5.2). 소수를 넘기면 반올림해 정수로 만든다.
 */
export const count = (value: string | number) =>
  new Intl.NumberFormat("ko-KR").format(Math.round(Number(value)));

/** 위와 같되 단위를 붙인다. 한 줄에 수량이 둘이면 뒤엣것에만 붙인다. */
export const shares = (value: string | number) => `${count(value)}주`;

/**
 * 계약의 원화 금액 문자열(`"112434000"`)을 화면 문구로. 숫자도 받는다.
 * 프론트는 금액을 계산하지 않으므로 여기서 하는 일은 자릿수 구분과 단위 부착뿐이다.
 */
export const krw = (amount: string | number) =>
  `${new Intl.NumberFormat("ko-KR").format(Math.round(Number(amount)))}원`;

/**
 * 현지 통화 가격. 자릿수는 종목 메타의 `priceScale` 을 따른다 (`_common.md §6.2`) —
 * 0·2·4 가 공존하므로 통일하면 폴리곤이 `$0.50` 이 되거나 엔비디아가 `$875.4300` 이 된다.
 */
export const localPrice = (
  value: string | number,
  currency: "KRW" | "USD",
  priceScale: number
) => {
  const number = Number(value);
  if (currency === "KRW") {
    return `${new Intl.NumberFormat("ko-KR").format(number)}원`;
  }
  return `$${new Intl.NumberFormat("en-US", {
    minimumFractionDigits: priceScale,
    maximumFractionDigits: priceScale,
  }).format(number)}`;
};

/**
 * 헤더 요약용 축약 원화. `48,250,780,000원` 을 `482.5억원` 으로 줄인다.
 *
 * **훑어보는 자리에만 쓴다.** 랭킹과 내 계좌는 쉼표가 든 정확한 금액을 그대로 보여준다 —
 * 두 화면의 역할이 다르다. 여기서 반올림한 값으로 등수를 가늠하게 두면 안 되고, 실제로
 * 등수는 서버가 원래 값으로 매긴다.
 *
 * 헤더 말고 하나 더 있다 — 종목 목록의 `보유` 필터가 내는 평가금
 * (`instrument-list.v2.tsx`). 좁은 기둥에서 금액 칸이 길어지면 그만큼을 종목명에서
 * 가져가므로, 접어서 칸 폭을 금액이 정하지 못하게 한다. 그쪽도 계좌로 건너가기 전에
 * 내 자리를 훑는 자리다.
 */
export const compactKrw = (amount: string | number) => {
  const value = Number(amount);
  const magnitude = Math.abs(value);
  if (magnitude >= 1e8) return `${(value / 1e8).toFixed(1)}억원`;
  // 만 단위는 소수를 붙이지 않는다 — `9,500만원` 이 `0.1억원` 보다 읽기 쉽다.
  if (magnitude >= 1e4) {
    const tenThousands = Math.round(value / 1e4);
    // **반올림한 뒤에 단위를 고른다.** 접기 전 값으로만 갈래를 타면 `99,999,999원` 이 1억에
    // 못 미쳐 `만` 으로 왔다가 반올림에서 `10,000만원` 이 된다 — 다섯 자리가 붙은 그 표기는
    // 바로 위 줄의 `1.0억원` 과 같은 값인데도 다른 크기로 읽힌다 (`compactUsd` 도 같다).
    if (Math.abs(tenThousands) >= 1e4)
      return `${(tenThousands / 1e4).toFixed(1)}억원`;
    return `${new Intl.NumberFormat("ko-KR").format(tenThousands)}만원`;
  }
  return krw(value);
};

/**
 * 헤더 요약용 축약 수익률. `+48250.78%` 를 `+4.8만%` 로 줄인다.
 *
 * 1만% 아래는 손대지 않는다. 대부분의 값이 거기 있고, 축약이 필요한 것은 칸을 넘치는
 * 네 자리 위쪽뿐이다.
 */
export const compactPercent = (value: number) =>
  Math.abs(value) >= 1e4
    ? `${signed(value / 1e4, 1)}만%`
    : `${signed(value, 2)}%`;

/**
 * 헤더 요약용 축약 달러. `$36,000,000.00` 을 `$36.0M` 으로 줄인다.
 *
 * **`compactKrw` 와 같은 자리에만 쓴다** — 헤더의 `Cash` 칸은 원화와 달러를 한 줄에
 * 나란히 놓으므로, 원화만 접으면 줄 길이를 정하는 쪽이 달러로 넘어가 접은 뜻이 없어진다.
 * 정확한 값은 내 계좌가 그대로 보여준다.
 *
 * **단위는 `억`·`만` 이 아니라 `M`·`K` 다.** 달러는 세 자리로 끊어 읽는 통화라 만 단위로
 * 접으면 `$3,600만` 처럼 자릿점과 단위가 서로 다른 묶음을 가리킨다.
 *
 * 십만 달러가 경계인 이유는 길이다. `$99,999.99` 까지가 열 자로 `482.5억원` 과 같은 급이고,
 * 그 위부터 숫자가 칸 폭을 정하기 시작한다.
 */
export const compactUsd = (amount: string | number) => {
  const value = Number(amount);
  const magnitude = Math.abs(value);
  if (magnitude >= 1e6) return `$${(value / 1e6).toFixed(1)}M`;
  // 천 단위는 소수를 붙이지 않는다 — `$125K` 가 `$0.1M` 보다 읽기 쉽다 (`compactKrw` 와 같다).
  if (magnitude >= 1e5) {
    const thousands = Math.round(value / 1e3);
    // **반올림한 뒤에 단위를 고른다.** 접기 전 값으로만 갈래를 타면 `$999,999` 가 백만에
    // 못 미쳐 `K` 로 왔다가 반올림에서 `$1000K` 가 된다 — 네 자리가 붙은 그 표기는 바로
    // 아래 줄의 `$1.0M` 과 같은 값인데도 다른 크기로 읽힌다.
    if (Math.abs(thousands) >= 1e3) return `$${(thousands / 1e3).toFixed(1)}M`;
    return `$${thousands}K`;
  }
  return usd(value);
};

/** 게임 거래일 `2020-03-16` → `2020.03.16`. */
export const gameDay = (isoDate: string) => isoDate.replaceAll("-", ".");
