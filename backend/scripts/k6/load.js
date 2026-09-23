import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";
import encoding from "k6/encoding";

// 인덱스 유무 비교용 부하. 같은 스크립트를 PHASE 만 바꿔 두 번 돌린다.
const BASE = __ENV.BASE_URL;
const KEY = __ENV.TEST_SUPPORT_KEY;
const USERS = Number(__ENV.USERS || 200);
const MODE = __ENV.MODE || "load"; // login | load
// 요청 사이 쉼(초). 0.2 는 "소수가 쉴 새 없이", 2 는 "많은 사람이 드물게"에 가깝다.
const SLEEP = Number(__ENV.SLEEP || 0.2);

export const options = MODE === "login"
  ? { vus: 20, iterations: USERS }
  : {
      scenarios: {
        notifications: { executor: "constant-vus", vus: Number(__ENV.VUS || 40), duration: __ENV.DURATION || "60s", exec: "notifications" },
        control: { executor: "constant-vus", vus: 10, duration: __ENV.DURATION || "60s", exec: "control" },
      },
      // 태그별 임계값을 두면 요약 JSON 에 엔드포인트별 지표가 따로 남는다.
      thresholds: {
        http_req_failed: ["rate<0.01"],
        "http_req_duration{name:GET /notifications}": ["p(95)<5000"],
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
  // 테스트 전체에서 한 번씩만 나오는 번호. VU 별 __ITER 로 만들면 VU 마다 같은 구간을 돈다.
  const n = (exec.scenario.iterationInTest % USERS) + 1;
  login(`k6-${String(n).padStart(3, "0")}`);
}

// 모듈 스코프는 VU 마다 따로다 — VU 하나가 사용자 하나로 한 번 로그인해 세션을 들고 돈다.
let token = null;
let loggedIn = false;

export function notifications() {
  if (!loggedIn) { token = login(`k6-${String(((__VU - 1) % USERS) + 1).padStart(3, "0")}`); loggedIn = true; }
  const headers = token ? { "X-Auth-Token": token } : {};
  const res = http.get(`${BASE}/api/v1/notifications?page=0&size=20`, { headers, tags: { name: "GET /notifications" } });
  check(res, { "notifications 200": (r) => r.status === 200 });
  sleep(SLEEP);
}

// 대조군: 인덱스와 무관한 공개 경로. 환경 노이즈를 잰다.
export function control() {
  const res = http.get(`${BASE}/api/v1/news/ticker`, { tags: { name: "GET /news/ticker (control)" } });
  check(res, { "ticker 200": (r) => r.status === 200 });
  sleep(SLEEP);
}
