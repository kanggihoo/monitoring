/**
 * 미인증으로 진입할 수 있는 화면 (ADR-0005). `proxy.ts` 와 화면들이 같은 목록을 본다 —
 * 둘로 나누면 한쪽만 열려 프록시는 통과시키는데 화면이 로그인을 요구하는 상태가 된다.
 *
 * **여기 없는 경로는 보호 경로다.** 새 화면의 기본값이 보호인 쪽이 안전하다 — 빠뜨렸을 때
 * 나는 일이 "로그인 화면으로 보낸다" 이지 "개인 데이터가 열린다" 가 아니다.
 */
const PUBLIC_ROUTES = ["/", "/dashboard", "/news", "/ranking"];

/**
 * @param pathname 판정할 경로 (쿼리 없는 pathname)
 * @returns 미인증으로 진입할 수 있으면 true
 */
export function isPublicRoute(pathname: string): boolean {
  return PUBLIC_ROUTES.includes(pathname);
}
