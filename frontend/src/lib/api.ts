/** 백엔드가 심는 위조 방지 토큰 쿠키와, 그 값을 돌려보낼 헤더. */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

/**
 * 브라우저에서 백엔드를 호출한다. 위조 방지 토큰은 **이 한 곳에서만** 붙인다 —
 * 호출부마다 붙이면 반드시 어딘가에서 빠뜨린다.
 *
 * @param path `/api` 로 시작하는 상대 경로 (절대 URL 금지)
 */
export function apiFetch(
  path: string,
  init: RequestInit = {}
): Promise<Response> {
  const csrfToken = readCookie(CSRF_COOKIE);

  return fetch(path, {
    ...init,
    headers: {
      ...init.headers,
      ...(csrfToken ? { [CSRF_HEADER]: csrfToken } : {}),
    },
  });
}

/** 백엔드의 실패 응답. 검증 실패는 필드별 이유가 함께 온다. */
type ErrorBody = {
  message?: string;
  errors?: { field: string; reason: string }[];
};

/**
 * 실패 응답에서 사용자에게 보여줄 문구를 고른다. 필드별 이유가 있으면 그것이 가장 구체적이다.
 * 본문이 비어 있거나 JSON 이 아니어도 던지지 않는다.
 *
 * @param fallback 본문에 아무 문구도 없을 때 쓸 기본 안내
 */
export async function apiErrorMessage(
  response: Response,
  fallback: string
): Promise<string> {
  const body = (await response.json().catch(() => ({}))) as ErrorBody;

  return body.errors?.[0]?.reason ?? body.message ?? fallback;
}

/** 브라우저 전용. `HttpOnly` 가 아닌 쿠키만 읽을 수 있다 (세션 쿠키는 읽히지 않는다). */
function readCookie(name: string): string | undefined {
  const entry = document.cookie
    .split("; ")
    .find((candidate) => candidate.startsWith(`${name}=`));

  return entry && decodeURIComponent(entry.slice(name.length + 1));
}
