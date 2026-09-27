// 스모크 — 파이프라인이 끝까지 이어지는지 확인하는 가벼운 부하. 성능 측정이 아니다.
// 실행: load/README.md
//
// 흐름 (VU 하나 = 사용자 하나):
//   처음 한 번   test-support 로그인 → 시장가 매수 1주
//   반복        게임 시계 · 종목 목록 · 뉴스 티커 · 알림 · 내 랭킹 조회
//
// 결과는 두 곳에 남는다:
//   - 터미널 요약 (thresholds 통과 여부)
//   - Prometheus (k6_* 지표, testid 라벨) → Grafana community/k6 Prometheus 대시보드
//   요청은 backend 트레이스·로그에도 그대로 찍힌다 → 앱 개요 대시보드의 HTTP 행.
import http from "k6/http";
import { check, sleep } from "k6";
import encoding from "k6/encoding";

const BASE = __ENV.BASE_URL || "http://backend:8080";
// backend/.env 의 값을 compose 가 env_file 로 넘긴다.
const KEY = __ENV.TEST_SUPPORT_API_KEY;

export const options = {
  scenarios: {
    smoke: { executor: "constant-vus", vus: Number(__ENV.VUS || 5), duration: __ENV.DURATION || "1m" },
  },
  // 넘으면 k6 가 실패 코드로 끝난다. 스모크라 느슨하게 — "돌아가는가"만 본다.
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
    checks: ["rate>0.99"],
  },
  // 모든 지표에 붙는 태그 → Prometheus 라벨. testid 로 실행 하나를 골라 본다.
  tags: { env: "local", testid: __ENV.TESTID || "smoke" },
};

// test user 는 backend 시드가 만든 k6-0001 ~ k6-1000 이다 (네 자리). k6-0001 은
// scripts/replay-speed.sh 가 ADMIN 으로 쓰므로 11 번부터 쓴다.
function userName() {
  return `k6-${String(__VU + 10).padStart(4, "0")}`;
}

function login() {
  const res = http.post(`${BASE}/api/v1/test-support/login`, JSON.stringify({ name: userName() }), {
    headers: { "Content-Type": "application/json", "X-Test-Support-Key": KEY },
    tags: { name: "POST /test-support/login" },
  });
  check(res, { "login 204": (r) => r.status === 204 });
  // 세션은 Secure 쿠키로만 온다 — k6 는 http 에서 Secure 쿠키를 보내지 않는다. 쿠키값은 세션 ID 의
  // base64 라 디코드해 X-Auth-Token 헤더로 보낸다. 이 헤더가 있으면 CSRF 검사도 빠진다 (SecurityConfig).
  const c = res.cookies.SESSION && res.cookies.SESSION[0];
  return c ? encoding.b64decode(c.value, "std", "s") : null;
}

// 모듈 스코프는 VU 마다 따로다.
let headers = null;

export default function () {
  if (headers === null) {
    headers = { "X-Auth-Token": login(), "Content-Type": "application/json" };
    const order = http.post(
      `${BASE}/api/v1/orders`,
      JSON.stringify({ orderType: "MARKET", side: "BUY", instrumentId: 101, quantity: 1 }),
      { headers, tags: { name: "POST /orders" } },
    );
    // 장이 닫힌 시간(폐장·야간)이면 거절될 수 있다 — 거절도 응답이 오면 파이프라인 확인으로는 충분하다.
    check(order, { "order answered": (r) => r.status === 201 || r.status === 400 || r.status === 409 });
  }

  const get = (path, name) => http.get(`${BASE}${path}`, { headers, tags: { name } });
  check(get("/api/v1/game/clock", "GET /game/clock"), { "clock 200": (r) => r.status === 200 });
  check(get("/api/v1/market/instruments", "GET /market/instruments"), { "instruments 200": (r) => r.status === 200 });
  check(get("/api/v1/news/ticker", "GET /news/ticker"), { "ticker 200": (r) => r.status === 200 });
  check(get("/api/v1/notifications?page=0&size=20", "GET /notifications"), { "notifications 200": (r) => r.status === 200 });
  check(get("/api/v1/rankings/me", "GET /rankings/me"), { "ranking 200": (r) => r.status === 200 });
  sleep(1);
}
