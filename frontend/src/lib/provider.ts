/**
 * 화면 문구는 "로그인 방법" 으로 통일한다. 값 자체는 백엔드의 Provider 문자열이다
 * (`../../../backend/docs/CONTEXT.md`).
 */
const LABELS: Record<string, string> = {
  google: "Google",
  ssafy: "SSAFY",
};

/**
 * 마지막으로 **성공한** 로그인 방법을 담는 브라우저 로컬 저장소 키.
 * 쓰는 쪽은 `components/remember-login-provider.tsx`, 읽는 쪽은 로그인 화면이다.
 */
export const LAST_PROVIDER_KEY = "lastLoginProvider";

/**
 * Provider 를 화면에 보여줄 이름으로 바꾼다.
 * @param provider 백엔드가 준 Provider 값. 모르는 값이면 그대로 돌려준다.
 */
export function providerLabel(provider: string): string {
  return LABELS[provider] ?? provider;
}
