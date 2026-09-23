/**
 * 브라우저 분석 도구(GA4 gtag · Microsoft Clarity)로 사용자 행동을 보낸다.
 *
 * 두 전역이 **없는 것도 정상 경로다** — 측정 ID 를 주입하지 않은 환경(로컬 개발 · E2E),
 * 광고 차단기가 스크립트를 막은 경우, `afterInteractive` 로드가 끝나기 전에 사용자가 먼저
 * 누른 순간이 그렇다. 그래서 전부 옵셔널 호출이고 실패를 삼킨다 — 분석이 화면을 깨뜨리는
 * 일은 없어야 한다. 반대로 말하면 **여기서 보낸 것이 반드시 도착하지는 않는다**. 절대
 * 수치가 아니라 기간별 상대 비교로 읽어야 하는 이유다.
 */

declare global {
  interface Window {
    dataLayer?: unknown[];
    gtag?: (...args: unknown[]) => void;
    clarity?: (...args: unknown[]) => void;
  }
}

/**
 * 추적하는 사건의 이름. 호출부에서 문자열을 직접 적으면 오타가 조용히 **새 이벤트**로 잡혀
 * 보고서가 둘로 갈린다 (GA4 는 미리 정의된 이름 목록을 갖지 않는다).
 *
 * GA4 제약: 소문자 snake_case, 40자 이내. `page_view` 같은 예약어는 쓰지 않는다 —
 * 그쪽은 `components/analytics.tsx` 가 라우트 변경에서 직접 보낸다.
 */
export type EventName =
  | "login_click"
  | "instrument_view"
  | "order_submit"
  | "order_result"
  | "briefing_open"
  | "briefing_complete"
  | "news_click"
  | "ranking_view";

/**
 * 사건에 딸리는 값. GA4 는 문자열 100자 · 이벤트당 25개까지 받고 객체·배열은 버린다.
 *
 * **매개변수는 GA4 관리 → 맞춤 정의에 같은 이름으로 등록해야 보고서에 나타난다.** 등록 전에
 * 쌓인 데이터는 소급되지 않으므로, 새 매개변수를 추가할 때는 배포보다 등록이 먼저다.
 * 지금 등록된 것은 여덟이다 (prod · dev 속성 양쪽). 괄호는 값이 오는 자리다:
 *
 *   provider      (`components/login-providers.tsx` 의 `PROVIDERS[].id`)
 *   side          (`lib/order-api.ts` `OrderRequest.side`)
 *   order_type    (`lib/order-api.ts` `OrderRequest.orderType`)
 *   status        주문 전송의 성패. `placeOrder` 가 값을 돌려주면 SUCCESS, 던지면 FAILED
 *   tab           (`ranking/ranking-screen.tsx` 의 `Track`)
 *   season_day    (`hooks/use-game-clock.ts` `GameClock.seasonDay`)
 *   instrument_name (`lib/market-types.ts` `InstrumentMeta.name`)
 *   asset_type    (`lib/market-types.ts` `InstrumentMeta.assetType`) — STOCK · ETF ·
 *                 INVERSE_ETF · IPO_STOCK · CRYPTO 다섯이다. 종목 **유형별로** 조회·주문·
 *                 실패율을 쪼개려고 둔다 (개별 종목은 instrument_name 이 답한다).
 *
 * **값은 도메인 표기를 그대로 보낸다** — `BUY` · `SELL` · `MARKET` · `LIMIT` 처럼 대문자다.
 * 보기 좋으라고 소문자로 바꾸지 않는다: GA4 의 차원 값은 대소문자를 구분해서 `BUY` 와 `buy`
 * 가 보고서에서 **다른 행**이 되고, 변환을 한 곳이라도 빠뜨리면 같은 사실이 둘로 쪼개진다.
 * 변환기를 두지 않는 것이 어긋날 여지를 없애는 길이다.
 *
 * **종목은 `instrumentId` 가 아니라 이름으로 보낸다.** 숫자 id 는 보고서에서 읽을 수 없고,
 * `CONTEXT.md` 가 `티커`·`Symbol` 을 금지어로 두었으므로 그 이름도 쓰지 않는다.
 */
type EventParams = Record<string, string | number | boolean>;

/**
 * 사건 하나를 두 도구에 함께 보낸다. Clarity 의 커스텀 이벤트는 값을 받지 않아 이름만 간다 —
 * Clarity 쪽에서 필터로 쓰려면 값은 `setTag()` 로 세션에 건다.
 */
export function trackEvent(name: EventName, params?: EventParams): void {
  window.gtag?.("event", name, params);
  window.clarity?.("event", name);
}

/**
 * 로그인 사용자를 식별해 기기가 바뀌어도 한 사람으로 묶는다.
 *
 * **백엔드 내부 숫자 ID 만 넘긴다.** 이메일·닉네임을 넣으면 GA4 약관(PII 금지) 위반이고
 * 적발 시 속성의 데이터가 삭제될 수 있다.
 */
export function identifyUser(userId: number): void {
  const id = String(userId);
  window.gtag?.("set", { user_id: id });
  window.clarity?.("identify", id);
}

/**
 * Clarity 세션에 필터용 태그를 건다 (예: `setTag("season", "2014-H1")`).
 * 세션 단위 속성이라 사건마다 바뀌는 값이 아니라 "이 사람이 지금 어떤 상태인가"를 넣는다.
 */
export function setTag(key: string, value: string): void {
  window.clarity?.("set", key, value);
}
