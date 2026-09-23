/**
 * 수량·금액 입력 칸의 정규화.
 *
 * **상태에는 늘 `Number()` 가 그대로 읽는 문자열만 둔다.** 칸에 보이는 모양(자릿점 등)은
 * 그리는 쪽이 얹고, 여기서 나온 값은 미리보기 요청·잔고 비교·주문 제출이 같은 숫자로
 * 읽어야 한다. 중간에 `NaN` 이 한 번이라도 끼면 화면은 "0" 을 말하면서 버튼만 잠기고,
 * 왜 잠겼는지는 아무 데도 적히지 않는다.
 */

/**
 * 주문 수량의 상한. **서버가 받을 수 있는 최대치와 같은 값이다.**
 *
 * `OrderRequest.quantity` 는 Java `Integer` 라 2,147,483,647 을 넘으면 역직렬화 단계에서
 * 400 이 된다 — 화면이 더 넓게 열어 둘 이유가 없고, 더 좁게 막을 이유는 더 없다. 여기서
 * 끊으면 낼 수 없는 수량이 칸에 서 있다가 서버에서 거절되는 일이 없다.
 */
export const QUANTITY_MAX = 2_147_483_647;

/**
 * 금액 정수부의 자릿수 상한. **`Number` 가 끝자리를 잃기 전에서 끊는다.**
 *
 * 금액은 서버에서 `BigDecimal` 이라 타입이 주는 상한이 없다. 대신 화면이 `Number` 로
 * 읽으므로 배정도 실수의 한계(`MAX_SAFE_INTEGER`, 16자리)가 실질적인 경계다. 그 너머는
 * 친 값과 칸에 보이는 값이 달라지고(`Number("1234567890123456789")`), `String()` 이
 * 지수 표기를 돌려줘 서버로 `1e+21` 같은 글자가 나간다.
 */
export const AMOUNT_MAX_DIGITS = 15;

/**
 * 수량 입력. **1주 단위 정수**이므로 (기획 6.5.2) 숫자가 아닌 글자와 앞자리 0 을 지운다.
 *
 * 앞자리 0 을 남기면 `000` 이나 `007` 같은 값이 칸에 서 있게 된다 — 읽는 사람에게는
 * 수량이 아니고, `Number` 는 같은 값으로 읽어 화면과 계산이 어긋나 보인다. `0` 하나만
 * 치면 빈 칸이 된다: 0 주 주문은 없으므로 그것이 곧 비운 것이다.
 */
export function normalizeQuantity(raw: string): string {
  const digits = raw.replace(/\D/g, "").replace(/^0+/, "");
  if (digits === "") return "";
  // 자릿수가 아니라 **값**으로 끊는다. 자릿수로 자르면 앞자리만 남아 (`30억` → `300,000,000`)
  // 친 것과 전혀 다른 수량이 되지만, 값으로 끊으면 "여기까지" 가 그대로 보인다.
  // `Number` 가 정확히 셀 수 있는 범위 안이라 이 비교 자체는 안전하다.
  return digits.length > 10 || Number(digits) > QUANTITY_MAX
    ? String(QUANTITY_MAX)
    : digits;
}

/**
 * 금액·가격 입력. 숫자와 **소수점 하나**만 남기고 소수 자릿수를 `scale` 로 끊는다.
 *
 * 소수점 개수를 세지 않으면 `1.2.3` 이 칸에 남는다. 그 값은 `Number` 가 `NaN` 으로 읽고,
 * 받는 쪽은 대개 `|| 0` 으로 덮어 0 이 된다 — 칸에는 숫자가 서 있는데 계산은 0 을 보는
 * 상태다. 비교식(`send > available`)에 `NaN` 이 들어가면 경고까지 조용히 새어 나간다.
 *
 * **치는 도중의 `1000.` 은 남긴다.** 지우면 다음 글자를 이어 적을 수 없다. `scale` 이 0 인
 * 통화(원화)에는 소수 자리가 없으므로 소수점 자체를 받지 않는다.
 */
export function normalizeDecimal(raw: string, scale: number): string {
  const digitsAndDots = raw.replace(/[^\d.]/g, "");
  const [rawWhole = "", ...rest] = digitsAndDots.split(".");

  // 앞자리 0 은 지우되 소수점 앞의 `0` 하나는 남긴다 — `0.5` 를 `.5` 로 만들지 않는다.
  const hasDot = scale > 0 && rest.length > 0;
  const stripped = rawWhole.replace(/^0+/, "");
  const whole = (stripped === "" && hasDot ? "0" : stripped).slice(
    0,
    AMOUNT_MAX_DIGITS
  );

  if (!hasDot) return whole;
  return `${whole}.${rest.join("").slice(0, scale)}`;
}
