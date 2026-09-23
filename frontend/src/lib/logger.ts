import pino from "pino";

import "server-only";

/**
 * 서버 전용 구조적 로거.
 *
 * 브라우저 쪽 로깅은 `console.*` 를 쓴다 (`docs/conventions/code.md` 5절).
 * `server-only` import 는 이 파일이 클라이언트 컴포넌트에 딸려 들어가는 순간
 * 빌드를 실패시킨다 — pino 가 브라우저 번들에 섞이는 것을 막는 장치다.
 * (import 정렬 순서와 무관하게 동작한다. 지우지 말 것.)
 *
 * 개발에서는 pino-pretty 로 사람이 읽을 수 있게, 운영에서는 JSON 한 줄로 찍어
 * 로그 수집기가 파싱할 수 있게 한다.
 */
export const logger = pino({
  level:
    process.env.LOG_LEVEL ??
    (process.env.NODE_ENV === "production" ? "info" : "debug"),

  transport:
    process.env.NODE_ENV === "production"
      ? undefined
      : { target: "pino-pretty", options: { colorize: true } },

  // 민감정보가 실수로 실려도 값이 남지 않게 한다. 로깅 금지 규칙의 마지막 방어선일 뿐,
  // 애초에 넣지 않는 것이 원칙이다.
  redact: {
    paths: [
      // pino 의 경로는 깊이를 정확히 맞춰야 한다. "*.password" 는 한 단계 안쪽
      // (user.password)만 잡고 최상위 password 는 놓치므로 둘 다 적는다.
      "password",
      "token",
      "accessToken",
      "refreshToken",
      "authorization",
      "cookie",
      "*.password",
      "*.token",
      "*.accessToken",
      "*.refreshToken",
      "*.authorization",
      "*.cookie",
      "req.headers.authorization",
      "req.headers.cookie",
    ],
    censor: "[redacted]",
  },
});
