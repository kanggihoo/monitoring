// ─────────────────────────────────────────────────────────────────────────────
// 목 서버 — 계약을 흉내내는 껍데기가 아니라 작은 게임 서버다.
//
// 월 그리드로 Tick 을 발행하고, 재생 배속·되감기를 지원하고, 세션 쿠키와 위조 방지
// 토큰을 실제로 검사한다. 그래야 목에서 통과한 코드가 Spring 에서도 통과한다.
//
// ── 반드시 지켜야 하는 제약 두 개 ───────────────────────────────────────────
//
// 1. **시계는 프로세스 전역이다.** 되감기와 배속이 모든 접속에 적용되고 되감기는 계좌까지
//    초기화하므로, 시계를 건드리는 두 E2E 가 동시에 돌면 둘 다 틀린다.
//    → Playwright 에서 시계를 조작하는 스펙은 **워커 하나로 묶은 별도 프로젝트**다.
//      (`playwright.config.ts` 의 `mock-clock` 프로젝트)
//    → 워커마다 목을 하나씩 띄우는 방법은 **불가능하다** — 포트가 워커마다 달라야 하는데
//      rewrite 의 destination 은 dev 서버 기동 시점에 고정된다.
//    이것이 코드에 안 적혀 있으면 반년 뒤 누군가 병렬로 돌린다.
//
// 2. **STOMP 구독 등록은 프레임 수신 시점에 동기적으로 끝낸다.** 클라이언트가 구독 완료를
//    판정하는 근거가 프레임 순서이므로(수신 확인 프레임을 쓰지 않는다), 목이 그 순서를
//    실제로 지켜야 한다. 구독 처리를 비동기로 미루면 `SUBSCRIBE` 와 첫 브로드캐스트
//    사이에 구멍이 열려 `ws.md` 의 초기 로드 절차가 막으려던 버그가 되살아난다.
//    → `stomp.ts` 의 `SUBSCRIBE` 처리를 참고.
// ─────────────────────────────────────────────────────────────────────────────

import { createServer, type IncomingMessage } from "node:http";

import { targetFor } from "../src/lib/mock-ledger.ts";
import { absenceSummary, acknowledgeAbsence } from "./absence.ts";
import {
  type Direction,
  exchange,
  EXCHANGE_FEE_RATE,
  type ExchangeQuote,
  sendScale,
} from "./account.ts";
import {
  CSRF_HEADER,
  csrfCookieHeader,
  csrfMatches,
  csrfTokenFor,
  expiredSessionCookieHeader,
  isCsrfExempt,
  isSessionExempt,
  needsCsrfCookie,
  sessionIdOf,
  standaloneUser,
} from "./auth.ts";
import {
  briefing,
  gameDateOf,
  SCHEDULE,
  SEASON_DAY,
  snapshot,
} from "./clock.ts";
import { sendError, sendNoContent, sendOk } from "./envelope.ts";
import {
  infoOf,
  instrumentById,
  INSTRUMENTS,
  publicMetaOf,
} from "./instruments.ts";
import { type Channel, newsAt, newsFeed, newsTicker } from "./news.ts";
import { saveSelfProfile, selfProfile } from "./onboarding.ts";
import {
  executeOrder,
  parseOrderBody,
  parsePreviewQuery,
  previewStub,
} from "./orders.ts";
import { positionsStub } from "./positions.ts";
import { closesUpTo, priceSnapshot } from "./prices.ts";
import { applyAdvance, applyPatch, replayState } from "./replay.ts";
import {
  dailyReport,
  dailyReportList,
  reportAvailable,
  seasonReport,
  whatIf,
} from "./reports.ts";
import { selfCheck } from "./self-check.ts";
import {
  clockState,
  currentTickSeq,
  dropTick,
  isDropped,
  markPublished,
  publishedTickSeq,
} from "./state.ts";
import { attachStomp, broadcast, endSession } from "./stomp.ts";
import {
  awardsStub,
  heatmapStub,
  leaderboardStub,
  rankingStub,
  valuationStub,
} from "./stubs.ts";

const PORT = Number(process.env.MOCK_PORT ?? 8081);

/**
 * 발행 폴러의 주기.
 *
 * **고정 주기 스케줄러로는 이 그리드를 만들 수 없다** — 틱 간격이 월마다 31~36초로
 * 다르기 때문이다. 그래서 매 초 "지금까지 발행됐어야 할 Tick 이 있나" 를 묻고 안 보낸
 * 것을 보낸다. 멱등하고, 멈췄다 깨어나도 따라잡는다. **이 구조가 배속·되감기를 부작용으로
 * 준다** (`adm.md` 구현 메모).
 */
const POLL_INTERVAL_MS = 1000;

/** 매 Tick 발행 시 불릴 훅. STOMP 브로드캐스트가 여기 붙는다. */
const publishHandlers: ((tickSeq: number) => void)[] = [];

export function onPublish(handler: (tickSeq: number) => void): void {
  publishHandlers.push(handler);
}

/** @returns 파싱한 본문. 비어 있으면 `{}`, JSON 이 아니면 `undefined` */
async function readJsonBody(
  request: IncomingMessage
): Promise<Record<string, unknown> | undefined> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(chunk as Buffer);
  }

  const raw = Buffer.concat(chunks).toString("utf8").trim();
  if (raw === "") {
    return {};
  }

  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === "object" && parsed !== null ? parsed : undefined;
  } catch {
    return undefined;
  }
}

/** 환전 한 건의 응답. 실행 응답과 이력 항목이 같은 형태다 (`acc.md`). */
function exchangeResponse(
  direction: Direction,
  quote: ExchangeQuote,
  prices: { asOfGameDate: string; asOfTickSeq: number }
) {
  const scale = sendScale(direction);
  return {
    // 이력을 저장하지 않으므로 식별자는 틱에서 만든다. 이력 조회는 목에 없다.
    exchangeId: prices.asOfTickSeq,
    direction,
    rate: quote.rate.toFixed(2),
    sendAmount: quote.sendAmount.toFixed(scale),
    feeRate: EXCHANGE_FEE_RATE,
    fee: quote.fee.toFixed(scale),
    receiveAmount: quote.receiveAmount.toFixed(
      direction === "KRW_TO_USD" ? 2 : 0
    ),
    gameDate: prices.asOfGameDate,
    tickSeq: prices.asOfTickSeq,
    exchangedAt: new Date().toISOString(),
  };
}

/** `channel` 은 필터 유무로 전체를 표현한다 — enum 안에 `ALL` 을 섞지 않는다 (`nws.md`). */
function isChannel(value: string | null): value is Channel {
  return value === "US" || value === "KR" || value === "CRYPTO";
}

function poll(): void {
  const target = currentTickSeq();

  // 밀린 것을 하나씩 따라잡는다. 배속을 올리면 한 폴에 여러 개가 나갈 수 있다.
  for (let tickSeq = publishedTickSeq() + 1; tickSeq <= target; tickSeq++) {
    markPublished(tickSeq);
    for (const handler of publishHandlers) {
      handler(tickSeq);
    }
  }
}

const server = createServer((request, response) => {
  const path = (request.url ?? "/").split("?")[0] ?? "/";

  // 목은 프록시 기능이 없다. 자기 경로만 답한다 — fallthrough 프록시를 두면 OAuth2
  // 리다이렉트와 다중 Set-Cookie 가 손으로 쓴 프록시를 통과해 세션이 깨진다.
  //
  // 재생 제어만 원장 판정에서 뺀다. 원장이 답하는 질문은 "**클라이언트**가 이 경로를 어디로
  // 불러야 하나" 이고, 그 답은 `S15P21A605-57` 에서 Spring 으로 바뀌었다 — 관리자 화면은
  // 실제 재생 시계를 조작해야 한다. 그런데 목 마켓은 여전히 **자기 시계**를 갖고 있고
  // (`/api/v1/game/clock` 은 원장에 남아 있다) 그것을 움직이는 수단이 이 경로뿐이다.
  // 부르는 것은 `pnpm mock:control` 과 시계 E2E 이며 둘 다 :8081 에 직결한다. 브라우저는
  // rewrite 가 Spring 으로 보내므로 여기 도달하지 않는다 — 그게 의도다.
  //
  // 원장이 지키는 불변("브라우저와 SSR 이 같은 백엔드를 본다")은 그대로다. 여기서 갈라지는
  // 것은 목 내부의 소유 판정뿐이다.
  if (
    path.startsWith("/api/") &&
    targetFor(path) !== "mock" &&
    !path.startsWith("/api/v1/admin/replay")
  ) {
    sendError(
      response,
      404,
      "목이 소유하지 않은 경로입니다. 원장(src/lib/mock-ledger.ts)을 확인하세요.",
      path
    );
    return;
  }

  const cookieHeader = request.headers.cookie;

  // ── 1층: 세션 ────────────────────────────────────────────────────────────────
  // 세션 쿠키의 **존재**를 검사한다. 검사하지 않으면 쿠키를 빠뜨린 호출부가 목에서는
  // 통과하고 Spring 이관 시점에 401 로 한꺼번에 터진다.
  if (
    !isSessionExempt(path, request.method ?? "GET") &&
    sessionIdOf(cookieHeader) === undefined
  ) {
    sendError(response, 401, "로그인이 필요합니다.", path);
    return;
  }

  // ── 2층: 위조 방지 토큰 ──────────────────────────────────────────────────────
  // 상태 변경 메서드에서만 검사한다. GET 은 절대 상태를 바꾸지 않으므로(`_common.md §1`)
  // 검사 대상이 아니다.
  const changesState = request.method !== "GET" && request.method !== "HEAD";
  if (changesState && !isCsrfExempt(path)) {
    if (!csrfMatches(cookieHeader, request.headers[CSRF_HEADER])) {
      sendError(response, 403, "위조 방지 토큰이 유효하지 않습니다.", path);
      return;
    }
  }

  // 첫 조회 응답에 위조 방지 토큰 쿠키를 심는다. 스크립트가 읽어야 하므로 HttpOnly 가
  // 아니다 — `lib/api.ts` 가 읽어 헤더로 돌려보낸다.
  if (!changesState && needsCsrfCookie(cookieHeader)) {
    response.setHeader(
      "set-cookie",
      csrfCookieHeader(csrfTokenFor(cookieHeader))
    );
  }

  // ── 로그아웃 (독립 모드) ────────────────────────────────────────────────────
  // 실서버는 Spring Security 가 소유하는 경로다. 독립 모드에서만 원장이 목 쪽에 포함하며,
  // **세션 종료가 WS 를 끊는 경로를 여기서 재현한다** — 두 탭 로그아웃 전파를 브라우저
  // 테스트로 확인할 수 있어야 하고, 그것은 서버가 끊어야 성립한다 (ADR-0005).
  if (path === "/api/auth/logout" && targetFor(path) === "mock") {
    if (request.method !== "POST") {
      sendError(response, 405, "허용하지 않는 메서드입니다.", path);
      return;
    }

    const sessionId = sessionIdOf(cookieHeader);
    response.setHeader("set-cookie", expiredSessionCookieHeader());
    if (sessionId) {
      endSession(sessionId);
    }
    response.writeHead(204).end();
    return;
  }

  if (
    path === "/api/v1/users/me" &&
    request.method === "GET" &&
    targetFor(path) === "mock"
  ) {
    // 독립 모드에서만 원장이 이 경로를 목 쪽에 포함한다. 꺼져 있으면 위쪽 원장 검사가
    // 이미 404 로 걸렀으므로 여기 도달하지 않는다.
    sendOk(response, standaloneUser());
    return;
  }

  // ── 재생 제어 (승격된 관리자 계약) ──────────────────────────────────────────
  if (path === "/api/v1/admin/replay") {
    if (request.method === "GET") {
      sendOk(response, replayState(false));
      return;
    }
    if (request.method === "PATCH") {
      void readJsonBody(request).then((body) => {
        if (body === undefined) {
          sendError(response, 400, "JSON 본문을 읽을 수 없습니다.", path);
          return;
        }

        const rejection = applyPatch(body);
        if (rejection) {
          sendError(response, rejection.status, rejection.message, path);
          return;
        }

        // 앵커를 바꿨으면 초기화가 일어났다. speed 만 바꿨으면 아니다.
        const movedAnchor =
          body.anchorTickSeq !== undefined ||
          body.anchorGameMonth !== undefined;
        sendOk(response, replayState(movedAnchor));
      });
      return;
    }

    sendError(response, 405, "허용하지 않는 메서드입니다.", path);
    return;
  }

  if (path === "/api/v1/admin/replay/advance") {
    if (request.method !== "POST") {
      sendError(response, 405, "허용하지 않는 메서드입니다.", path);
      return;
    }

    const rejection = applyAdvance();
    if (rejection) {
      sendError(response, rejection.status, rejection.message, path);
      return;
    }

    // 폴러를 기다리지 않고 즉시 발행한다 — 수동 전진의 요점이 "한 칸이 지금 나가는 것"이다.
    poll();
    sendOk(response, replayState(false));
    return;
  }

  // 환전 — 상태를 바꾸는 유일한 계약 경로다. 세션과 위조 방지 토큰을 이미 위에서 검사했다.
  if (path === "/api/v1/accounts/me/exchanges") {
    if (request.method !== "POST") {
      sendError(response, 405, "허용하지 않는 메서드입니다.", path);
      return;
    }
    if (snapshot(clockState(), Date.now()).phase !== "TRADING") {
      sendError(
        response,
        409,
        "환전은 장이 열려 있을 때만 할 수 있습니다.",
        path
      );
      return;
    }

    void readJsonBody(request).then((body) => {
      if (body === undefined) {
        sendError(response, 400, "JSON 본문을 읽을 수 없습니다.", path);
        return;
      }

      const direction = body.direction;
      if (direction !== "KRW_TO_USD" && direction !== "USD_TO_KRW") {
        sendError(response, 400, "환전 방향이 올바르지 않습니다.", path);
        return;
      }

      const tickSeq = currentTickSeq();
      const prices = priceSnapshot(tickSeq);
      const result = exchange(
        direction,
        Number(body.amount),
        Number(prices.usdKrwRate)
      );
      if ("error" in result) {
        // 잔고 부족은 계약이 409 로 정한다 (`acc.md`).
        sendError(response, 409, result.error, path);
        return;
      }

      sendOk(response, exchangeResponse(direction, result.quote, prices), 201);
    });
    return;
  }

  // 부재 요약 확인 (`acc.md`). **클라이언트가 본 지점을 그대로 받는다** — 수신 시점의 틱으로
  // 갱신하면 모달을 띄워 둔 채 비운 시간까지 "본 것" 이 된다. 204 다.
  if (path === "/api/v1/accounts/me/absence-summary/ack") {
    if (request.method !== "POST") {
      sendError(response, 405, "허용하지 않는 메서드입니다.", path);
      return;
    }

    void readJsonBody(request).then((body) => {
      if (body === undefined) {
        sendError(response, 400, "JSON 본문을 읽을 수 없습니다.", path);
        return;
      }

      const ackedTickSeq = Number(body.ackedTickSeq);
      if (!Number.isFinite(ackedTickSeq) || ackedTickSeq < 0) {
        sendError(response, 400, "확인 지점이 올바르지 않습니다.", path);
        return;
      }

      acknowledgeAbsence(ackedTickSeq);
      sendNoContent(response);
    });
    return;
  }

  // ── 목 전용 결함 주입 ────────────────────────────────────────────────────────
  // `/api` 밖에 둔다. 원장을 거치지 않는 게 요점이고, rewrite 규칙에 걸릴 여지가 원천적으로
  // 없어야 앱 코드가 목을 아는 일이 없다. 브라우저에서는 도달할 수 없으며(rewrite 가
  // 없으므로) 그게 의도다 — 부르는 것은 curl 과 E2E 뿐이고 둘 다 :8081 에 직결한다.
  //
  // 이것만 목 전용이다. 배속·seek·advance 는 전부 계약이다.
  if (path === "/__mock/drop-next-tick") {
    if (request.method !== "POST") {
      sendError(response, 405, "허용하지 않는 메서드입니다.", path);
      return;
    }

    const dropped = publishedTickSeq() + 1;
    dropTick(dropped);
    sendOk(response, { droppedTickSeq: dropped });
    return;
  }

  // 주문 — 시장가만 그 자리에서 체결한다 (`orders.ts` 상단 주석).
  if (path === "/api/v1/orders") {
    if (request.method !== "POST") {
      sendError(response, 405, "허용하지 않는 메서드입니다.", path);
      return;
    }
    const clock = snapshot(clockState(), Date.now());
    if (clock.phase !== "TRADING") {
      sendError(response, 409, "장이 열려 있을 때만 주문할 수 있습니다.", path);
      return;
    }

    void readJsonBody(request).then((body) => {
      if (body === undefined) {
        sendError(response, 400, "JSON 본문을 읽을 수 없습니다.", path);
        return;
      }
      const order = parseOrderBody(body);
      if ("error" in order) {
        sendError(response, 400, order.error, path);
        return;
      }
      if (order.orderType === "LIMIT") {
        sendError(
          response,
          409,
          "목은 예약 주문을 체결하지 않습니다. 시장가로 시험하세요.",
          path
        );
        return;
      }

      const result = executeOrder(
        order,
        priceSnapshot(currentTickSeq()),
        clock.feeFree
      );
      if ("error" in result) {
        sendError(response, result.status, result.error, path);
        return;
      }
      sendOk(response, result.created, 201);
    });
    return;
  }

  // 투자 성향 설문. 조회와 저장 둘뿐이고 저장은 덮어쓰기다 — 언제든 다시 고칠 수 있다.
  if (path === "/api/v1/onboarding/self-profile") {
    if (request.method === "GET") {
      sendOk(response, selfProfile());
      return;
    }
    if (request.method === "PUT") {
      void readJsonBody(request).then((body) => {
        if (body === undefined) {
          sendError(response, 400, "JSON 본문을 읽을 수 없습니다.", path);
          return;
        }
        const saved = saveSelfProfile(body.answers);
        if (!saved) {
          // 형식 검증은 서버 몫이다 (채점은 화면이 한다). 실서버의 400 과 같은 자리다.
          sendError(
            response,
            400,
            "답은 다섯 글자여야 하고 각 자리가 1~4 여야 합니다.",
            path
          );
          return;
        }
        sendOk(response, saved);
      });
      return;
    }
  }

  if (request.method !== "GET") {
    sendError(response, 405, "허용하지 않는 메서드입니다.", path);
    return;
  }

  if (path === "/api/v1/game/clock") {
    sendOk(response, snapshot(clockState(), Date.now()));
    return;
  }

  if (path === "/api/v1/rankings") {
    const tickSeq = currentTickSeq();
    const clock = snapshot(clockState(), Date.now());
    const query = new URL(request.url ?? "/", "http://mock").searchParams;
    // 모르는 값은 기본 트랙으로 떨어뜨린다. Spring 은 400 이지만(계약) 목의 책임은 형태를
    // 내는 것이고, 여기서 400 을 흉내 내면 오타 하나에 화면이 통째로 빈다.
    const requested = query.get("track");
    const track =
      requested === "DAILY" ||
      requested === "VALUATION" ||
      requested === "POINT"
        ? requested
        : "SEASON";
    const size = Number(query.get("size"));
    const page = Number(query.get("page"));
    sendOk(
      response,
      leaderboardStub(
        tickSeq,
        gameDateOf(tickSeq),
        track,
        clock.leaderboardBlackout,
        Number.isFinite(size) && size > 0 ? Math.min(size, 200) : undefined,
        Number.isFinite(page) && page > 0 ? page : 0
      )
    );
    return;
  }

  // 계좌 요약·전체 포지션 집계 — **방송되는 값의 초기 조회다** (`ws.md` 초기 로드).
  // 같은 스텁을 쓰므로 REST 로 받은 첫 값과 뒤이어 오는 방송이 어긋나지 않는다.
  if (path === "/api/v1/accounts/me") {
    sendOk(response, valuationStub(priceSnapshot(currentTickSeq())));
    return;
  }

  // 알림 (`noti.md`). **빈 목록이지만 있어야 한다** — 앱 진입의 초기 조회 묶음에 들어
  // 있어서, 이 경로가 죽은 Spring 으로 가면 마켓 화면이 시세를 세우지 못한 채 멎는다.
  // 목은 알림을 만들지 않으므로 내용은 비어 있다.
  if (path === "/api/v1/notifications") {
    sendOk(response, {
      unreadCount: 0,
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      hasNext: false,
    });
    return;
  }

  // 보유 종목 (`acc.md`). 주문이 바꾼 보유를 그대로 낸다 — 시드 상수가 아니다.
  if (path === "/api/v1/accounts/me/positions") {
    sendOk(response, positionsStub(priceSnapshot(currentTickSeq())));
    return;
  }

  // 부재 중 요약 (`acc.md`). 판정은 여기서 한다 — 프론트가 같은 계산을 흉내내면 기준이 둘이
  // 된다. 상태를 바꾸지 않으므로 몇 번을 불러도 같은 답이다.
  if (path === "/api/v1/accounts/me/absence-summary") {
    sendOk(response, absenceSummary(currentTickSeq(), gameDateOf));
    return;
  }

  if (path === "/api/v1/social/heatmap") {
    const tickSeq = currentTickSeq();
    sendOk(response, heatmapStub(tickSeq, gameDateOf(tickSeq)));
    return;
  }

  // 주문 미리보기 (`ord.md`). 부족분과 그것을 채우는 환전 요청까지 여기서 만든다 —
  // 프론트가 역산하면 반올림이 갈려 환전한 뒤에도 주문이 막힌다.
  if (path === "/api/v1/orders/preview") {
    const clock = snapshot(clockState(), Date.now());
    if (clock.phase !== "TRADING") {
      sendError(
        response,
        409,
        "장이 열려 있을 때만 미리 볼 수 있습니다.",
        path
      );
      return;
    }

    const query = parsePreviewQuery(
      new URL(request.url ?? "/", "http://mock").searchParams
    );
    if ("error" in query) {
      sendError(response, 400, query.error, path);
      return;
    }

    const preview = previewStub(
      query,
      priceSnapshot(currentTickSeq()),
      clock.feeFree
    );
    if (!preview) {
      sendError(response, 404, "종목을 찾을 수 없습니다.", path);
      return;
    }

    sendOk(response, preview);
    return;
  }

  // 개장 브리핑 (`sea.md`). 브리핑 구간에만 쓰이는 값이 아니라 하루 종일 다시 열 수 있다 —
  // 그래서 허용 phase 가 `ALWAYS` 다.
  if (path === "/api/v1/seasons/current/briefing") {
    sendOk(response, briefing(clockState()));
    return;
  }

  if (path === "/api/v1/rankings/me") {
    const clock = snapshot(clockState(), Date.now());
    sendOk(response, rankingStub(currentTickSeq(), clock.leaderboardBlackout));
    return;
  }

  // 폐장에 순위가 확정되지만 공개는 정산 발표(17:00)다 — `REVIEW_ONLY` 가 그 이후다.
  if (path === "/api/v1/rankings/awards") {
    const clock = snapshot(clockState(), Date.now());
    sendOk(
      response,
      awardsStub(
        currentTickSeq(),
        clock.seasonDay,
        clock.phase === "REVIEW_ONLY"
      )
    );
    return;
  }

  // 뉴스는 시세와 같은 시계를 쓴다 — 현재 Tick 이 곧 미래 차단선이다 (`nws.md`).
  if (path === "/api/v1/news") {
    const query = new URL(request.url ?? "/", "http://mock").searchParams;
    const channel = query.get("channel");
    const instrumentId = Number(query.get("instrumentId"));
    const size = Number(query.get("size"));
    sendOk(
      response,
      newsFeed({
        asOfTickSeq: currentTickSeq(),
        channel: isChannel(channel) ? channel : undefined,
        instrumentId: Number.isFinite(instrumentId) ? instrumentId : undefined,
        personalOnly: query.get("relevance") === "PERSONAL",
        cursor: query.get("cursor"),
        size: Number.isFinite(size) && size > 0 ? size : undefined,
      })
    );
    return;
  }

  if (path === "/api/v1/news/ticker") {
    sendOk(response, newsTicker(currentTickSeq()));
    return;
  }

  if (path === "/api/v1/reports/daily") {
    sendOk(response, dailyReportList(snapshot(clockState(), Date.now()).phase));
    return;
  }

  const daily = /^\/api\/v1\/reports\/daily\/(\d+)$/.exec(path);
  if (daily?.[1]) {
    const seasonDay = Number(daily[1]);
    const clock = snapshot(clockState(), Date.now());
    // 폐장에 만들어지지만 정산 발표까지 열지 않는다 — 실패가 아니라 아직 이르다는 뜻이다.
    if (!reportAvailable(seasonDay, clock.phase)) {
      sendError(response, 409, "정산 발표 전에는 복기를 열 수 없습니다.", path);
      return;
    }

    sendOk(response, dailyReport(seasonDay, clock.gameDate));
    return;
  }

  // '만약에' 시뮬레이터 (`rpt.md`, S15P21A605-90). 시즌 리포트와 같은 409 리듬이다 —
  // 발표된 일차가 하나도 없으면 조회 자체가 열리지 않는다.
  if (path === "/api/v1/reports/season/what-if") {
    const clock = snapshot(clockState(), Date.now());
    if (!reportAvailable(SEASON_DAY.day, clock.phase)) {
      sendError(response, 409, "정산이 발표된 일차가 아직 없습니다.", path);
      return;
    }

    const query = new URL(request.url ?? "/", "http://mock").searchParams;
    const instrumentId = Number(query.get("instrumentId"));
    const result = whatIf(instrumentId, clock.gameDate);
    if (!result) {
      sendError(response, 404, "존재하지 않는 종목입니다.", path);
      return;
    }

    sendOk(response, result);
    return;
  }

  // 계약은 3일차 정산 발표를 기다리지만 목에는 3일차가 없다 — 목이 재생하는 일차의 정산
  // 발표를 시즌 종료로 본다 (`reports.ts` 머리말). 그 전에는 계약과 같은 409 다.
  if (path === "/api/v1/reports/season") {
    const clock = snapshot(clockState(), Date.now());
    if (!reportAvailable(SEASON_DAY.day, clock.phase)) {
      sendError(
        response,
        409,
        "시즌 리포트는 마지막 일차 정산 발표 이후에 열립니다.",
        path
      );
      return;
    }

    sendOk(response, seasonReport(clock.gameDate));
    return;
  }

  if (path === "/api/v1/market/prices") {
    sendOk(response, priceSnapshot(currentTickSeq()));
    return;
  }

  if (path === "/api/v1/market/instruments") {
    sendOk(response, {
      asOfGameDate: gameDateOf(currentTickSeq()),
      // 메타와 시세를 분리한다 — 메타는 IPO 상장 시에만 바뀌고 시세는 매 틱 바뀐다.
      // 프론트가 instrumentId 로 조합한다 (`unv.md`).
      instruments: INSTRUMENTS.map(publicMetaOf),
    });
    return;
  }

  const closes = /^\/api\/v1\/market\/instruments\/(\d+)\/closes$/.exec(path);
  if (closes?.[1]) {
    const instrument = instrumentById(Number(closes[1]));
    if (!instrument) {
      sendError(response, 404, "존재하지 않는 종목입니다.", path);
      return;
    }

    const tickSeq = currentTickSeq();
    const gameDate = gameDateOf(tickSeq);

    sendOk(response, {
      instrumentId: instrument.instrumentId,
      currency: instrument.currency,
      priceScale: instrument.priceScale,
      asOfGameDate: gameDate,
      asOfTickSeq: tickSeq,
      points: closesUpTo(instrument, gameDate).map((point) => ({
        gameDate: point.gameDate,
        close: point.close.toFixed(instrument.priceScale),
      })),
    });
    return;
  }

  // 정보 카드 (`info.md`). 종목 상세보다 먼저 온다 — 더 긴 경로가 앞이어야 한다.
  const info = /^\/api\/v1\/market\/instruments\/(\d+)\/info$/.exec(path);
  if (info?.[1]) {
    const instrument = instrumentById(Number(info[1]));
    if (!instrument) {
      sendError(response, 404, "존재하지 않는 종목입니다.", path);
      return;
    }

    sendOk(response, infoOf(instrument));
    return;
  }

  const detail = /^\/api\/v1\/market\/instruments\/(\d+)$/.exec(path);
  if (detail?.[1]) {
    const instrument = instrumentById(Number(detail[1]));
    if (!instrument) {
      sendError(response, 404, "존재하지 않는 종목입니다.", path);
      return;
    }

    sendOk(response, publicMetaOf(instrument));
    return;
  }

  sendError(response, 404, "존재하지 않는 경로입니다.", path);
});

// 웹은 핸드셰이크의 세션 쿠키로 인증한다 (`ws.md`). 브라우저와 목이 직결하는 이유는
// rewrite 가 업그레이드 요청을 넘기지 못하기 때문이고, 쿠키는 포트를 구분하지 않으므로
// 세션 쿠키가 :8081 로도 그대로 실린다.
attachStomp(server, sessionIdOf);

/**
 * 매 Tick 브로드캐스트. `market.prices` 페이로드는 `GET /api/v1/market/prices` 의 `data` 와
 * **같은 형태**다 — 프론트가 초기 로드와 틱 수신에 같은 파서를 쓴다 (`mkt.md`).
 */
onPublish((tickSeq) => {
  // 유실 주입된 틱은 브로드캐스트만 건너뛴다. 내부 상태와 조회 응답에는 이미 반영되어
  // 있으므로 재조회하면 나온다 — 번호를 건너뛰면 무한 재조회가 된다.
  if (isDropped(tickSeq)) {
    return;
  }

  const tick = SCHEDULE[tickSeq - 1];
  const gameDate = tick?.gameDate ?? null;
  const prices = priceSnapshot(tickSeq);

  broadcast("/topic/tick", {
    type: "market.prices",
    tickSeq,
    gameDate,
    payload: prices,
  });

  // 히트맵 스텁을 **같은 Tick 에 함께** 흘린다. 한 Tick 에 한 종류만 흐르면 프론트의 조립
  // 코드가 운동을 하지 않아 화면에서 아무것도 검증되지 않는다 (`-40`).
  broadcast("/topic/tick", {
    type: "social.heatmap",
    tickSeq,
    gameDate,
    payload: heatmapStub(tickSeq, gameDate ?? ""),
  });

  // 기사가 있는 틱에만 보낸다. 매 틱 빈 메시지를 보내면 하루에 500번 헛돈다 (`nws.md`).
  const published = newsAt(tickSeq);
  if (published.length > 0) {
    broadcast("/topic/tick", {
      type: "news.published",
      tickSeq,
      gameDate,
      payload: published,
    });
  }

  // 개인 큐의 매 Tick 조각. 계좌·랭킹 상태는 각 슬라이스 이슈가 목에 붙이므로 지금은
  // 시드 그대로의 전액 현금 계좌와 결정론적 순위다 — 값은 스텁이지만 리듬은 계약대로다.
  broadcast("/user/queue/me", {
    type: "account.valuation",
    tickSeq,
    gameDate,
    payload: valuationStub(prices),
  });
  // 암전은 개인 큐에도 적용된다 — 조회는 막고 푸시로 흘리면 가린 값이 그대로 새어 나간다.
  broadcast("/user/queue/me", {
    type: "ranking.me",
    tickSeq,
    gameDate,
    payload: rankingStub(
      tickSeq,
      snapshot(clockState(), Date.now()).leaderboardBlackout
    ),
  });
});

const notes = selfCheck();
setInterval(poll, POLL_INTERVAL_MS).unref();

server.listen(PORT, () => {
  console.log(`목 서버 :${PORT}`);
  for (const note of notes) {
    console.log(`  검산 통과 — ${note}`);
  }
});
