/**
 * WebSocket 주소.
 *
 * **기본값은 현재 origin 에서 유도한다.** `NEXT_PUBLIC_*` 은 빌드 시점에 번들에 구워지므로
 * 배포에서 주입할 수 없다 — `next.config.ts` 가 `BACKEND_ORIGIN` 에 대해 이미 같은 것을
 * 경고하고 있다. 그래서 배포는 환경변수 없이 풀리고, 로컬만 목의 포트를 가리킨다.
 *
 * 브라우저와 목이 직결하는 이유는 rewrite 가 업그레이드 요청을 넘기지 못하기 때문이다.
 * 쿠키는 포트를 구분하지 않으므로 세션 쿠키가 그대로 실린다.
 */
export function websocketUrl(): string {
  // 로컬 override 를 **개발 빌드에서만** 읽는다. 이 조건이 없으면 `.env.local` 을 둔 채
  // 빌드한 번들에 `ws://localhost:8081/ws` 가 구워지고, 배포된 화면의 WS 가 조용히 안 붙는다
  // (연결 실패는 화면에 아무 표시도 남기지 않으므로 발견이 늦다).
  //
  // `NODE_ENV` 는 빌드 시점에 문자열로 치환되므로 프로덕션에서는 이 분기가 죽은 코드가 되어
  // 환경변수 값 자체가 번들에 들어가지 않는다.
  if (process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_WS_URL) {
    return process.env.NEXT_PUBLIC_WS_URL;
  }

  const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${window.location.host}/ws`;
}

/** 미인증도 구독하는 공개 토픽 셋 (`ws.md`, ADR-0005). */
export const PUBLIC_DESTINATIONS = [
  "/topic/tick",
  "/topic/event",
  "/topic/chat",
] as const;

/** 개인 destination. **미인증은 애초에 구독하지 않는다** — 서버 거부는 방어선이지 정상 경로가 아니다. */
export const PERSONAL_DESTINATION = "/user/queue/me";

/** 계약이 정한 destination 넷. 타입마다 쪼개지 않는다 (`ws.md`). */
export const DESTINATIONS = [
  ...PUBLIC_DESTINATIONS,
  PERSONAL_DESTINATION,
] as const;

/**
 * 세션 종료로 서버가 연결을 끊을 때 `ERROR` 프레임의 `message` 헤더에 실리는 사유
 * (`ws.md` §오류). 백엔드의 `SessionEndedDisconnector.SESSION_ENDED` 와 같은 문자열이다.
 *
 * **네트워크 단절과 구분하는 유일한 근거다.** 단절은 프레임을 남기지 않으므로, 이 값이 없는
 * 종료는 재연결로만 처리하고 사용자에게 아무 안내도 하지 않는다.
 */
export const SESSION_ENDED = "SESSION_ENDED";

/**
 * 계약 위반 구독 거부에 붙는 접두사 (`ContractedDestinationInterceptor.CONTRACT_VIOLATION`).
 *
 * **재시도로 고쳐지지 않는 오류라는 것을 이 값으로만 가른다.** 문구 자체를 매칭하면 백엔드가
 * 문장을 다듬는 순간 프론트가 조용히 분류를 놓치므로, 양쪽이 같은 코드를 상수로 들고 있다.
 *
 * 이 접두사가 없는 오류는 **전부 일시 장애로 본다** — 서버가 읽기 타임아웃으로 세션을 끊을 때
 * Spring 이 보내는 `"Session closed."` 처럼, 정상 운영 중에 나오는 오류가 훨씬 많다.
 */
export const CONTRACT_VIOLATION = "CONTRACT_VIOLATION";

/**
 * 실시간 메시지 봉투 (`_common.md §10`).
 *
 * `tickSeq` 가 비어 있는 메시지가 있다 — 폐장 자동 취소처럼 Tick 에 속하지 않는 사건이다.
 * 라우팅 기준은 destination 이 아니라 **이 `type`** 이다: `/topic/tick` 하나에 네 종류가
 * 흐르므로, 봉투에 `type` 이 없으면 payload 모양을 보고 종류를 추측해야 한다.
 */
export type Envelope<T = unknown> = {
  type: string;
  tickSeq: number | null;
  gameDate: string | null;
  /**
   * 재생 실행 세대. **재생 메시지에만 실린다** (`market.prices` · `game.phase` ·
   * `game.clock`) — 나머지 19종은 재생과 무관해 이 필드가 없다
   * (`RealtimePublisher.ReplayEnvelope`).
   *
   * **이 값이 바뀌면 `tickSeq` 가 되감긴다.** 관리자가 시즌을 초기화하거나 뒤로 이동하면
   * 서버가 1 올리고 틱을 1 번부터 다시 내보낸다. 그것을 모르면 새 틱이 전부 "이미 지난
   * 번호" 로 버려져 화면이 되감기 전 가격을 그대로 들고 있게 된다 — 봉투에 이 필드가
   * 있는 이유가 그것이다.
   */
  replayEpoch?: number;
  payload: T;
};
