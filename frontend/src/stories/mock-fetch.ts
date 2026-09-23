/**
 * 스토리 전용 `fetch` 가로채기. **앱 코드가 아니다** — 스토리에서만 import 한다.
 *
 * 화면 컴포넌트 상당수가 자기 데이터를 스스로 조회한다. 스토리마다 `fetch` 를 손으로
 * 갈아 끼우면 같은 열 줄이 열 군데로 복사되므로 한 곳에 둔다.
 *
 * 서버 응답은 `{ data: ... }` 봉투를 쓴다 (`lib/market-api.ts` 의 `readJson`).
 * 여기 넘기는 것은 봉투 **안**의 값이고, 씌우는 일은 이 함수가 한다.
 *
 * @param routes 경로 조각 → 응답 본문. 상태 코드를 내려면 값 자리에 숫자를 준다
 * @returns 되돌리는 함수. 스토리의 `beforeEach` 가 반환하면 스토리북이 알아서 부른다
 */
export function mockFetch(routes: Record<string, unknown>) {
  const original = globalThis.fetch;

  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url =
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.href
          : input.url;

    const hit = Object.entries(routes).find(([path]) => url.includes(path));
    // 심지 않은 경로는 404 다. 조용히 빈 값을 주면 어느 조회가 빠졌는지 모른다.
    if (!hit) return new Response("", { status: 404 });

    const [, body] = hit;
    if (typeof body === "number") return new Response("", { status: body });
    return Response.json({ data: body });
  }) as typeof fetch;

  return () => {
    globalThis.fetch = original;
  };
}
