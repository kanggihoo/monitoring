/**
 * 서버와 브라우저가 같은 문자열을 만들도록 지역과 시간대를 고정한다. 고정하지 않으면 서버에서
 * 만든 HTML 과 브라우저가 다시 그린 결과가 달라져 하이드레이션이 어긋난다.
 */
const FORMATTER = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Seoul",
});

/**
 * 백엔드가 준 시각을 화면 문구로 바꾼다.
 *
 * @param isoString 백엔드의 시각 문자열 (UTC 기준 ISO-8601)
 */
export function formatDateTime(isoString: string): string {
  return FORMATTER.format(new Date(isoString));
}

/** 같은 이유로 시간대를 고정한다. 내역 표는 자릿수가 고르게 서야 해 24시간제를 쓴다. */
const DAY_TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "Asia/Seoul",
});

/**
 * 실제 날짜와 시·분. **게임 시각이 아니라 사람이 실제로 누른 시각이다** (기획 7.4.1).
 *
 * `2026. 09. 21. 14:31` 처럼 오는 것을 `2026.09.21 14:31` 로 다듬는다 — 표에서 자릿수가
 * 흔들리지 않아야 한다.
 *
 * @param isoString 백엔드의 시각 문자열 (UTC 기준 ISO-8601)
 */
export function formatDayTime(isoString: string): string {
  return DAY_TIME_FORMATTER.format(new Date(isoString))
    .replace(/\.\s*/g, ".")
    .replace(/\.(\d{2}:)/, " $1")
    .replace(/\.$/, "");
}
