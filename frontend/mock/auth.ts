/**
 * 목의 인증 검사.
 *
 * **세션 쿠키의 존재만 본다.** 세션 소유권은 Spring(Redis)의 것이고 목은 그 값을 검증할
 * 수단이 없다. 그래도 검사하는 이유는 세션 만료 화면을 개발 중에 확인할 수 있어야 하고,
 * 무엇보다 **검사하지 않으면 토큰·쿠키를 빠뜨린 호출부가 목 단계에서 통과하다가 Spring
 * 이관 시점에 한꺼번에 터지기** 때문이다 — BFF 를 기각한 이유와 같은 함정을 목 자신이
 * 만드는 셈이 된다.
 */

/** 백엔드가 심는 세션 쿠키 이름. `src/proxy.ts` 와 같은 값이다. */
const SESSION_COOKIE = "SESSION";

export function readCookie(
  cookieHeader: string | undefined,
  name: string
): string | undefined {
  const entry = (cookieHeader ?? "")
    .split(";")
    .map((candidate) => candidate.trim())
    .find((candidate) => candidate.startsWith(`${name}=`));

  return entry && decodeURIComponent(entry.slice(name.length + 1));
}

/** 핸드셰이크·요청에 실려 온 세션 쿠키 값. 목에는 이것이 곧 세션의 신원이다. */
export function sessionIdOf(
  cookieHeader: string | undefined
): string | undefined {
  return readCookie(cookieHeader, SESSION_COOKIE);
}

/** 세션 쿠키를 즉시 만료시키는 헤더. 실서버는 Spring Session 이 같은 일을 한다. */
export function expiredSessionCookieHeader(): string {
  return `${SESSION_COOKIE}=; Path=/; Max-Age=0; SameSite=Lax`;
}

/** 위조 방지 토큰 쿠키와, 그 값을 돌려받을 헤더. `src/lib/api.ts` 와 같은 이름이다. */
const CSRF_COOKIE = "XSRF-TOKEN";
export const CSRF_HEADER = "x-xsrf-token";

/** 세션 쿠키 값 → 위조 방지 토큰. 재시작하면 사라진다. */
const csrfTokens = new Map<string, string>();

/**
 * 미인증에 열린 조회. **백엔드 `SecurityConfig` 와 정확히 같은 목록이어야 한다** — 안 맞으면
 * 목에서는 통과하고 실서버에서 깨진다.
 *
 * 미래 차단은 여기서 지키는 것이 아니다. 절단은 조회 계층의 책임이고 REST 와 WS 가 같은
 * 기준을 쓰므로, 미인증이 불러도 발행된 틱까지만 나간다 (ADR-0005).
 *
 * **`GET` 만 여는 항목이 있다.** 채팅은 조회가 공개이고 전송은 `사용자` 다 — 메서드를 구분하지
 * 않으면 목에서 미인증 전송이 통과한다.
 */
const PUBLIC_GET_PATHS = [
  /^\/api\/v1\/seasons\/current$/,
  /^\/api\/v1\/chat\/messages$/,
  /^\/api\/v1\/game\/clock$/,
  /^\/api\/v1\/market\/prices$/,
  /^\/api\/v1\/market\/instruments$/,
  /^\/api\/v1\/market\/instruments\/\d+\/closes$/,
  /^\/api\/v1\/news$/,
  /^\/api\/v1\/news\/ticker$/,
  /^\/api\/v1\/rankings$/,
  /^\/api\/v1\/rankings\/awards$/,
];

/**
 * 메서드를 가리지 않고 열린 경로. 재생 제어와 목 전용 경로이며 **명령줄에서 부르기 때문이다** —
 * 기존 테스트 지원 명세가 같은 판단을 이미 했다.
 */
const SESSION_EXEMPT = [/^\/api\/v1\/admin\/replay/, /^\/__mock\//];

/** 위조 방지 토큰 예외. 세션 예외와 같은 이유(명령줄 호출)이므로 같은 목록을 쓴다. */
const CSRF_EXEMPT = [/^\/api\/v1\/admin\/replay/, /^\/__mock\//];

/**
 * @param path 판정할 경로
 * @param method HTTP 메서드. 공개 조회는 `GET` 에만 열린다
 */
export function isSessionExempt(path: string, method: string): boolean {
  if (SESSION_EXEMPT.some((pattern) => pattern.test(path))) {
    return true;
  }
  return (
    (method === "GET" || method === "HEAD") &&
    PUBLIC_GET_PATHS.some((pattern) => pattern.test(path))
  );
}

export function isCsrfExempt(path: string): boolean {
  return CSRF_EXEMPT.some((pattern) => pattern.test(path));
}

/**
 * 이 세션이 쓸 위조 방지 토큰. 없으면 만든다.
 *
 * **스크립트가 읽어야 하므로 `HttpOnly` 를 붙이지 않는다** — `lib/api.ts` 의 `apiFetch` 가
 * `document.cookie` 에서 읽어 헤더에 실어 돌려보낸다.
 */
export function csrfTokenFor(cookieHeader: string | undefined): string {
  const session = readCookie(cookieHeader, SESSION_COOKIE) ?? "anonymous";
  const existing = csrfTokens.get(session);
  if (existing) {
    return existing;
  }

  const token = `mock-csrf-${Math.random().toString(36).slice(2, 10)}`;
  csrfTokens.set(session, token);
  return token;
}

/** 클라이언트가 위조 방지 토큰 쿠키를 아직 갖고 있지 않은지. 첫 조회 응답에 심는다. */
export function needsCsrfCookie(cookieHeader: string | undefined): boolean {
  return readCookie(cookieHeader, CSRF_COOKIE) === undefined;
}

export function csrfCookieHeader(token: string): string {
  return `${CSRF_COOKIE}=${token}; Path=/; SameSite=Lax`;
}

/**
 * 상태 변경 요청의 위조 방지 토큰이 맞는지. **헤더 값이 쿠키와 달라도 거부한다** —
 * 검사하지 않으면 토큰을 빠뜨린 호출부가 슬라이스 내내 통과하다가 Spring 이관 시점에
 * 한꺼번에 터진다.
 */
export function csrfMatches(
  cookieHeader: string | undefined,
  headerValue: string | string[] | undefined
): boolean {
  const cookieToken = readCookie(cookieHeader, CSRF_COOKIE);
  const sent = Array.isArray(headerValue) ? headerValue[0] : headerValue;

  return cookieToken !== undefined && sent === cookieToken;
}

/**
 * 독립 모드가 돌려주는 고정 사용자.
 *
 * **권한을 인자로 받고 기본은 일반 사용자다.** 기본을 관리자로 두면 개발 중 항상 관리자
 * 시점이 되어 일반 사용자 화면을 못 본다.
 *
 * Provider 를 하나 둔다 — 비어 있으면 `lib/sandbox.ts` 가 샌드박스로 판정해 상단 바에
 * 안내 띠가 뜬다(Test User 는 OAuth Account 를 갖지 않는다는 근거). 독립 모드의 고정
 * 사용자는 샌드박스가 아니다.
 */
export function standaloneUser() {
  const role = process.env.MOCK_STANDALONE_ROLE === "ADMIN" ? "ADMIN" : "USER";

  return {
    id: 1,
    email: "mock@example.com",
    nickname: "목플레이어목플레이어목목",
    role,
    providers: ["google"],
  };
}
