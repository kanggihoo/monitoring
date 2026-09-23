/**
 * 계약이 정한 실제 시각 표현: **ISO-8601 UTC, 밀리초 없음** (`_common.md §6.1`).
 *
 * 한 곳에만 둔다. 봉투의 `timestamp` 와 시계의 `serverTime`·`nextTickAt`·`phaseEndsAt` 이
 * 같은 형식이어야 하고, 구현이 둘이면 한쪽만 바뀐다.
 *
 * @param ms epoch 밀리초. 기본값은 지금
 * @throws 유한하지 않은 값이면 — 배속 0 으로 나눈 결과다. 정지 중에는 nullable 필드를
 *         `null` 로 두어야 하며, `Date` 의 RangeError 는 어디서 0 으로 나눴는지 말해 주지 않는다
 */
export function isoUtc(ms: number = Date.now()): string {
  if (!Number.isFinite(ms)) {
    throw new Error(
      `절대 시각을 계산할 수 없다 (${ms}). 정지 중에는 nullable 필드를 null 로 둔다.`
    );
  }

  return new Date(Math.round(ms)).toISOString().replace(/\.\d{3}Z$/, "Z");
}
