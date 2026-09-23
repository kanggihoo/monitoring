import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";
import encoding from "k6/encoding";

// 프로세스 안 스냅샷(172) 전후 비교. 같은 스크립트를 before(develop) / after(172) 두 jar 에 돌린다.
// 인덕스 비교(load.js)와 다른 점은 대상이 "같은 틱이면 전원 동일한 응답" 과 "요청마다 game_ticks 를
// 한 번 더 읽던 인증 경로" 라는 것 — 그래서 시나리오가 공개 시세와 내 계좌 둘이다.
const BASE = __ENV.BASE_URL;
const KEY = __ENV.TEST_SUPPORT_KEY;
const USERS = Number(__ENV.USERS || 200);
const MODE = __ENV.MODE || "load"; // login | load
const SLEEP = Number(__ENV.SLEEP || 2);
const DURATION = __ENV.DURATION || "60s";

export const options = MODE === "login"
  ? { vus: 20, iterations: USERS }
  : {
      scenarios: {
        // 공개 시세. 접속·새로고침마다 부르는 초기 로드다. 172-A 의 대상.
        prices: { executor: "constant-vus", vus: Number(__ENV.PRICES_VUS || 100), duration: DURATION, exec: "prices" },
        // 내 계좌. 시계 → game_ticks → 계좌·포지션. 172-B 의 대상 (틱 조회가 맵 참조가 된다).
        account: { executor: "constant-vus", vus: Number(__ENV.ACCOUNT_VUS || 100), duration: DURATION, exec: "account" },
        // 대조군: 172 가 건드리지 않은 공개 경로. 환경 노이즈를 잰다.
        control: { executor: "constant-vus", vus: 10, duration: DURATION, exec: "control" },
      },
      thresholds: {
        http_req_failed: ["rate<0.01"],
        "http_req_duration{name:GET /market/prices}": ["p(95)<5000"],
        "http_req_duration{name:GET /accounts/me}": ["p(95)<5000"],
        "http_req_duration{name:GET /news/ticker (control)}": ["p(95)<5000"],
      },
    };

function login(name) {
  const res = http.post(`${BASE}/api/v1/test-support/login`, JSON.stringify({ name }), {
    headers: { "Content-Type": "application/json", "X-Test-Support-Key": KEY },
    tags: { name: "login" },
  });
  check(res, { "login 2xx": (r) => r.status >= 200 && r.status < 300 });
  // 세션은 Secure 쿠키로만 온다 — k6 는 http 에서 Secure 쿠키를 보내지 않는다. 쿠키값은
  // 세션 ID 의 base64 라 디코드해 X-Auth-Token 헤더로 보낸다 (CookieOrHeaderHttpSessionIdResolver).
  const c = res.cookies && res.cookies.SESSION && res.cookies.SESSION[0];
  return c ? encoding.b64decode(c.value, "std", "s") : null;
}

// 로그인 패스: 사용자마다 한 번 로그인해 계좌를 만든다 (enroll).
export default function () {
  const n = (exec.scenario.iterationInTest % USERS) + 1;
  login(`k6-${String(n).padStart(3, "0")}`);
}

export function prices() {
  const res = http.get(`${BASE}/api/v1/market/prices`, { tags: { name: "GET /market/prices" } });
  check(res, { "prices 200": (r) => r.status === 200 });
  sleep(SLEEP);
}

let token = null;
let loggedIn = false;

export function account() {
  if (!loggedIn) { token = login(`k6-${String(((__VU - 1) % USERS) + 1).padStart(3, "0")}`); loggedIn = true; }
  const headers = token ? { "X-Auth-Token": token } : {};
  const res = http.get(`${BASE}/api/v1/accounts/me`, { headers, tags: { name: "GET /accounts/me" } });
  check(res, { "account 200": (r) => r.status === 200 });
  sleep(SLEEP);
}

export function control() {
  const res = http.get(`${BASE}/api/v1/news/ticker`, { tags: { name: "GET /news/ticker (control)" } });
  check(res, { "ticker 200": (r) => r.status === 200 });
  sleep(SLEEP);
}
