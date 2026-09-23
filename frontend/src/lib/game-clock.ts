/**
 * 게임 시계 오프셋과 남은 시간. **남은 초를 세어 내려가지 않는다** — 백그라운드 탭에서
 * 타이머가 스로틀되면 거짓이 되므로, 절대 시각과 오프셋에서 매 렌더링마다 다시 계산한다
 * (`_common.md §7.3`, `docs/CONTEXT.md` "게임 시계 오프셋").
 */

/**
 * 서버 시각 − 로컬 시계. 한 번 구해 두고 이후 모든 카운트다운에 더한다.
 *
 * @param serverTimeIso 응답의 `serverTime` (ISO-8601 UTC)
 * @param localNowMs 응답을 받은 순간의 로컬 `Date.now()`
 */
export function clockOffsetMs(
  serverTimeIso: string,
  localNowMs: number
): number {
  return Date.parse(serverTimeIso) - localNowMs;
}

/**
 * 절대 시각까지 남은 초. 0 미만은 0 으로 자른다.
 *
 * @param targetIso 서버가 준 절대 시각. null(정지 중)이면 null 을 돌려준다
 * @param offsetMs `clockOffsetMs` 의 값
 * @param localNowMs 지금의 로컬 `Date.now()`
 */
export function remainingSeconds(
  targetIso: string | null | undefined,
  offsetMs: number,
  localNowMs: number
): number | null {
  if (!targetIso) {
    return null;
  }
  const remainingMs = Date.parse(targetIso) - (localNowMs + offsetMs);
  return Math.max(0, Math.ceil(remainingMs / 1000));
}

/** mm:ss. 한 시간이 넘으면 h:mm:ss. */
export function formatCountdown(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const secs = safe % 60;
  const mmss = `${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
  return hours > 0 ? `${hours}:${mmss}` : mmss;
}
