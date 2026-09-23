// ─────────────────────────────────────────────────────────────────────────────
// 이관 규칙 — 이 파일을 고치는 사람이 반드시 읽는다
//
// 1. 구체적인 것이 먼저 온다. 순서가 곧 규칙이고, 첫 매치가 이긴다.
//    `/api/v1/admin/replay`(목)와 `/api/v1/admin/users`(Spring)가 접두사를 공유하므로
//    목 규칙이 맨 뒤의 Spring 캐치올보다 앞에 있어야 한다.
//
// 2. 게임 시계는 마켓 전체가 이관될 때까지 원장에 남긴다.
//    실제 백엔드의 시계는 실제 페이스(배속 1, 실제 개장 앵커)라 목의 배속 시계와
//    `tickSeq`·`gameDate`가 완전히 다르다. 시계만 넘기고 가격을 목에서 받으면
//    게임 시계가 둘이 되어 화면이 앞뒤가 안 맞는다.
//    이관 단위는 엔드포인트 하나가 아니라 시계를 포함한 마켓 덩어리다.
//
//    **시계·현재가·종가에 이어 종목 목록·상세까지 Spring 에 있다(S15P21A605-78 · -99).**
//    그래서 이 여섯을 `MOCK_MARKET=1` 스위치 뒤에 두었다 — **기본은 Spring 이고, 켜면**
//    여섯이 함께 목으로 간다. 스위치 하나가 마켓 덩어리 전체를 옮기므로 어느 쪽이든 게임
//    시계가 하나다.
//
//    **기본이 Spring 인 이유는 그것이 진실이기 때문이다.** 여섯 다 Spring 에 있고
//    (S15P21A605-78 · -99), 배포도 Spring 을 본다. 기본을 목으로 두면 일상 개발이 배포와
//    다른 것을 보고, 빠뜨린 스위치 하나가 배포를 조용히 깨뜨린다 — 실제로 그랬다.
//
//    **목을 켜는 쪽은 e2e 다.** Spring 을 띄우지 않고 돌므로(`playwright.config.ts`) 목
//    시계에서 결정론을 얻는다. 그래서 그 설정이 `MOCK_MARKET=1` 을 **명시한다** — 목을
//    쓰는 쪽이 말하게 두면, 말하지 않은 모든 환경이 자동으로 옳다.
// ─────────────────────────────────────────────────────────────────────────────
//
// 이 파일은 **import 를 하나도 갖지 않는다.** 세 곳이 함께 읽기 때문이다 —
// `next.config.ts`(브라우저 경로), `lib/backend.ts`(서버 컴포넌트 경로),
// 그리고 목 서버(자기 소유 경로 판정). `next.config.ts` 는 `server-only` 를 끌어올 수
// 없으므로 여기에 무엇이든 import 하면 그 셋 중 하나가 원장을 못 읽게 된다.
// 원장이 둘이 되면 브라우저와 SSR 이 서로 다른 백엔드를 본다.

/** 요청을 받을 쪽. `backend` 는 기존 Spring 이다. */
export type LedgerTarget = "mock" | "backend";

export type LedgerEntry = {
  /** Next rewrite 의 `source` 문법. `:param` 은 한 세그먼트, `:param*` 은 나머지 전부. */
  source: string;
  target: LedgerTarget;
};

/**
 * Spring 에 있고 **기본으로 Spring 이 답하는** 경로. `MOCK_MARKET=1` 일 때만 원장에 들어와
 * 목으로 간다 (위 규칙 2).
 *
 * **다섯이 한 덩어리다.** 시계만 넘기면 가격이 목의 배속 시계를 보고, 가격만 넘기면 시계가
 * 어긋난다. 종가 시계열은 절단 기준을 같은 시계에서 받고, 종목 목록의 `listingStatus`·
 * `isNew`·`asOfGameDate` 도 같은 시계에서 파생된다. 하나만 넘기면 게임 시계가 둘이 된다.
 *
 * 종가 시계열이 종목 상세보다 앞이어야 한다 — 첫 매치가 이기므로 더 긴 경로가 먼저다.
 */
const MOCK_MARKET_ENTRIES: readonly LedgerEntry[] = [
  // 게임 시계 — 시계·phase 의 단일 진실 (`_common.md §7.3`)
  { source: "/api/v1/game/clock", target: "mock" },

  // 현재가 스냅샷·종가 시계열 (`mkt.md`)
  { source: "/api/v1/market/prices", target: "mock" },
  { source: "/api/v1/market/instruments/:instrumentId/closes", target: "mock" },

  // 종목 목록·상세 (`unv.md`)와 정보 카드 (`info.md`, S15P21A605-129). 판정 기준
  // (`/signal-criteria`)·시즌(`/seasons/current`)은 목이 답한 적이 없어 원장에 없다 —
  // 캐치올로 Spring 에 간다.
  //
  // **정보 카드가 덩어리에 들어간 이유는 e2e 다.** 거기서는 Spring 을 띄우지 않으므로
  // (`playwright.config.ts`) 원장에서 빠진 경로가 죽은 :8080 으로 간다 — 마켓 화면이 종목을
  // 고를 때마다 이것을 부르기 시작했으므로 함께 있어야 한다. 시계와 달리 내용이 틱에 묶이지
  // 않아 게임 시계를 둘로 만들 염려는 없다.
  //
  // 정보 카드가 종목 상세보다 앞이어야 한다 — 첫 매치가 이기므로 더 긴 경로가 먼저다.
  { source: "/api/v1/market/instruments/:instrumentId/info", target: "mock" },
  { source: "/api/v1/market/instruments/:instrumentId", target: "mock" },
  { source: "/api/v1/market/instruments", target: "mock" },

  // 개장 브리핑 (`sea.md`). Spring 에 있다(S15P21A605-99). **덩어리에 넣는 이유는 시계다** —
  // 여는 조건이 `game.phase === BRIEFING` 이고 내용이 당일 틱 구간의 청약 일정이라, 시계만
  // 목이고 브리핑만 Spring 이면 열리지 않는 모달을 조회하거나 그 반대가 된다.
  { source: "/api/v1/seasons/current/briefing", target: "mock" },
];

/**
 * Spring 에 **아직 없는** 경로. 여기 있는 한 독립 모드가 아니어도 목이 답한다.
 *
 * Spring 에 엔드포인트가 생기면 **여기서 지우고 `STANDALONE_ENTRIES` 로 옮긴다.** 그냥
 * 지우면 안 된다 — e2e 는 Spring 을 띄우지 않으므로(`playwright.config.ts`) 원장에서 빠진
 * 경로는 캐치올을 타고 죽은 :8080 으로 간다. 목 핸들러를 지울 수 있는 시점도 같다:
 * 스위치가 아니라 **e2e 가 Spring 을 함께 띄우게 되는 때**다 (위 규칙 2 의 마지막 단락).
 */
const MOCK_ENTRIES: readonly LedgerEntry[] = [
  // 재생 제어(`/api/v1/admin/replay`)는 Spring 으로 이관했다 (S15P21A605-57).
  // 규칙 2 의 "시계는 함께 넘긴다"는 **일반 마켓 화면**에 대한 것이다 — 관리자 재생 화면은
  // 목 마켓 스토어를 쓰지 않고 Spring 상태만 읽으므로 게임 시계가 둘이 되지 않는다.
  // 게임 시계는 `MOCK_MARKET_ENTRIES` 에 있다: 기본은 Spring 이고 스위치로 함께 목이 된다.
];

/**
 * 독립 모드에서만 목이 답하는 경로.
 *
 * 보호 라우트가 현재 사용자 조회를 통과해야 렌더되므로, 이것이 없으면 목이 완벽해도
 * 마켓 화면이 로그인으로 튄다. **기본은 꺼짐이고 일상 개발은 Spring 을 함께 띄운다** —
 * OAuth·세션 만료·위조 방지 토큰이 실제로 검증되는 편이 진실에 가깝다.
 */
const STANDALONE_ENTRIES: readonly LedgerEntry[] = [
  { source: "/api/v1/users/me", target: "mock" },
  // 로그아웃도 함께 넘긴다. 세션 종료가 WS 를 끊는 경로(ADR-0005)를 브라우저 테스트로
  // 확인하려면 목이 그 종료를 실제로 수행해야 한다.
  { source: "/api/auth/logout", target: "mock" },

  // 주문 미리보기와 환전 (`ord.md` · `acc.md`). 둘이 한 흐름이라 함께 있어야 한다 —
  // 미리보기가 부족분 환전 요청을 만들고 환전이 그것을 실행한다. 하나만 목에 두면
  // 부족분 버튼이 눌리기만 하고 아무 일도 일어나지 않는다.
  { source: "/api/v1/orders/preview", target: "mock" },
  // 주문 생성과 보유 조회. **미리보기만 목에 두면 매수가 Spring 으로 가** 시세가 없다는
  // 오류로 끝난다 — 목만으로 사고파는 흐름을 볼 수 있어야 한다. 더 긴 경로가 앞이다.
  { source: "/api/v1/accounts/me/positions", target: "mock" },
  // 알림 목록 — 앱 진입의 초기 조회 묶음에 있다. 목에 없으면 독립 모드에서 죽은 Spring 을
  // 부르고, 그 실패가 마켓 화면의 첫 시세까지 함께 데려간다.
  { source: "/api/v1/notifications", target: "mock" },
  { source: "/api/v1/orders", target: "mock" },
  { source: "/api/v1/accounts/me/exchanges", target: "mock" },

  // ── 아래는 Spring 에 이미 있다. 독립 모드에서만 목이 답한다. ──

  // 일차 리포트 (`rpt.md`, S15P21A605-88). `/review` 가 서버 컴포넌트에서 일차 목록을
  // 읽으므로, 이것이 없으면 Spring 없는 독립 모드에서 복기 화면이 렌더 자체를 못 한다.
  { source: "/api/v1/reports/daily/:seasonDay", target: "mock" },
  { source: "/api/v1/reports/daily", target: "mock" },

  // '만약에' 시뮬레이터 (`rpt.md`, S15P21A605-90). **Spring 에 있다** — 그래서 위의
  // `MOCK_ENTRIES` 가 아니라 여기다. 시즌 리포트(`/reports/season`)와 접두사를 공유하지만
  // 그쪽 규칙이 정확 일치라 이 경로를 잡지 못하므로 자기 규칙이 필요하다. 더 긴 경로가
  // 앞이다 — 첫 매치가 이긴다.
  { source: "/api/v1/reports/season/what-if", target: "mock" },

  // 시즌 리포트 (`rpt.md`). **Spring 에 있다** (`ReportController` 의 `GET /season`) —
  // `MOCK_ENTRIES` 에 남아 있는 동안 목이 409 로 가로채 실제 엔드포인트가 개발 중 한 번도
  // 불리지 않았고, 그래서 응답 형태가 갈린 것을 아무도 못 봤다. 규칙대로 이쪽으로 옮긴다.
  { source: "/api/v1/reports/season", target: "mock" },

  // 투자 성향 설문 (`OnboardingController` 의 `GET/PUT /self-profile`). **Spring 에 있다** —
  // 목은 프로세스 메모리에만 들고 있고, 독립 모드에서 저장·조회 왕복을 보려는 용도다.
  { source: "/api/v1/onboarding/self-profile", target: "mock" },

  // 뉴스 (`nws.md`, BE-50). 티커가 피드보다 먼저다 — 첫 매치가 이기므로 더 긴 경로가 앞이다.
  { source: "/api/v1/news/ticker", target: "mock" },
  { source: "/api/v1/news", target: "mock" },

  // 리더보드 (`rank.md`, S15P21A605-62 · -64). 목 쪽은 스텁 고정값이다 (`mock/stubs.ts`).
  { source: "/api/v1/rankings/awards", target: "mock" },
  { source: "/api/v1/rankings/me", target: "mock" },
  { source: "/api/v1/rankings", target: "mock" },

  // 전체 포지션 집계 (`soc.md`). 매 틱 방송되는 값의 초기 조회다 — 방송이 멎는 구간에
  // 들어온 사람에게도 카드가 채워져야 한다 (`ws.md` 초기 로드).
  { source: "/api/v1/social/heatmap", target: "mock" },

  // 부재 중 요약 (`acc.md`). **Spring 에 있다** — 두 엔드포인트와 `lastAckedTickSeq` 저장이
  // `AccountController` · `AbsenceSummaryService` 에 다 있다 (S15P21A605-123).
  // 확인이 더 긴 경로라 앞이다.
  { source: "/api/v1/accounts/me/absence-summary/ack", target: "mock" },
  { source: "/api/v1/accounts/me/absence-summary", target: "mock" },

  // 계좌 요약 (`acc.md`).
  // 평가액은 매 틱 방송되지만 첫 값은 이 조회에서 온다 (`ws.md` 초기 로드).
  // 하위 경로(환전·포지션)는 걸리지 않는다 — 목에 그 상태가 없고, 없는 것을
  // 있는 척하면 Spring 이관 때 한꺼번에 터진다.
  { source: "/api/v1/accounts/me", target: "mock" },
];

/** 원장에 없는 모든 `/api` 는 기존 백엔드로 간다. **반드시 맨 뒤다.** */
const BACKEND_CATCH_ALL: LedgerEntry = {
  source: "/api/:path*",
  target: "backend",
};

/**
 * 순서 있는 원장. 첫 매치가 이긴다.
 *
 * **프로덕션에서는 캐치올 하나만 돌려준다** — 그 환경에 목이 없으므로 원장에 목 규칙이
 * 남아 있으면 서버 컴포넌트가 닿지 않는 주소를 부른다. 옵션으로도 켤 수 없다.
 *
 * @param options `standalone` 이 참이면 현재 사용자 조회를 목 쪽에 **추가로** 포함한다.
 *                기본값은 `MOCK_STANDALONE` 환경변수이며 미설정은 꺼짐이다.
 *                `mockMarket` 이 참이면 마켓 덩어리 여섯을 원장에 **넣어** 목으로 보낸다.
 *                기본값은 `MOCK_MARKET` 환경변수이며 미설정은 꺼짐이다 — 즉 기본이 Spring 이다.
 */
export function ledger(options?: {
  standalone?: boolean;
  mockMarket?: boolean;
}): LedgerEntry[] {
  // **프로덕션에는 목이 없다.** 이 줄이 없으면 서버 컴포넌트가 `MOCK_ORIGIN`(기본
  // `localhost:8081`)을 찔러 종목 목록이 통째로 빈다 — 브라우저 호출은 nginx 가 가져가고
  // 헬스체크(`/login`)는 종목을 읽지 않으므로, 컨테이너는 healthy 인 채로 화면만 빈다.
  //
  // 배포 설정에 스위치를 넣어 막지 않는 이유는 그것이 **빠뜨릴 수 있는 한 줄**이기
  // 때문이다. 목을 볼지 말지는 환경의 선택이 아니라 목이 존재하느냐의 사실이므로,
  // 원장이 그 사실을 알고 있는 편이 맞다.
  if (process.env.NODE_ENV === "production") return [BACKEND_CATCH_ALL];

  const standalone = options?.standalone ?? process.env.MOCK_STANDALONE === "1";
  const mockMarket = options?.mockMarket ?? process.env.MOCK_MARKET === "1";

  return [
    ...(mockMarket ? MOCK_MARKET_ENTRIES : []),
    ...MOCK_ENTRIES,
    ...(standalone ? STANDALONE_ENTRIES : []),
    BACKEND_CATCH_ALL,
  ];
}

/**
 * 이 경로를 어느 쪽이 답하는지 판정한다. 원장을 위에서부터 훑어 **첫 매치**를 쓴다.
 *
 * @param path `/api` 로 시작하는 경로. 쿼리스트링은 붙어 있어도 무시한다
 * @param entries 평가할 원장. 기본값은 `ledger()` — 테스트가 순서를 바꿔 넣는다
 */
export function targetFor(
  path: string,
  entries: readonly LedgerEntry[] = ledger()
): LedgerTarget {
  const pathname = path.split("?")[0] ?? path;
  const matched = entries.find((entry) =>
    toRegExp(entry.source).test(pathname)
  );

  return matched?.target ?? "backend";
}

/**
 * Next 의 `source` 문법을 정규식으로 바꾼다. 목 서버와 `originFor` 가 rewrite 와 **같은
 * 문자열**을 평가해야 하므로, 패턴을 두 문법으로 나눠 적지 않고 이 변환을 둔다.
 *
 * `:param*` 은 세그먼트 0개 이상이라 앞의 `/` 까지 함께 선택적으로 만든다 —
 * 그래야 `/api/:path*` 가 `/api` 자체도 잡는다 (Next 와 같은 동작).
 */
function toRegExp(source: string): RegExp {
  const pattern = source
    .replace(/[.+?^${}()|[\]\\]/g, "\\$&")
    .replace(/\/:\w+\*/g, "(?:/.*)?")
    .replace(/:\w+/g, "[^/]+");

  return new RegExp(`^${pattern}$`);
}
