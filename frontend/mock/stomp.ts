import type { Server } from "node:http";
import { type WebSocket, WebSocketServer } from "ws";

import {
  DESTINATIONS,
  type Envelope,
  PERSONAL_DESTINATION,
  SESSION_ENDED,
} from "../src/lib/ws.ts";

/**
 * 손으로 쓴 STOMP 1.2 서버.
 *
 * **SockJS 를 쓰지 않는다.** SockJS 는 STOMP 위가 아니라 아래에 있는 별도 전송 계층이라,
 * 채택하면 `/ws/info` 전송 협상·세션 URL·XHR 폴백 프레이밍이 계약에 들어오고 목 비용이
 * 몇 배가 된다. 얻는 것은 WebSocket 미지원 브라우저 대응인데 대상 환경에 그런 브라우저가
 * 없다 (`ws.md`).
 *
 * **서버가 보내는 프레임은 `CONNECTED`·`MESSAGE`·`ERROR` 셋뿐이다.** 그래서 손으로 쓸 수
 * 있다. 클라이언트에서 받는 것은 `CONNECT`·`SUBSCRIBE`·`UNSUBSCRIBE`·`DISCONNECT`·
 * 하트비트뿐이고 **`SEND` 는 받지 않는다** — 애플리케이션 메시지가 전부 서버→클라이언트라
 * correlation id 도, 애플리케이션 오류 프레임도, 메시지 단위 인증도 필요 없다.
 *
 * **인증은 `CONNECT` 한 번으로 끝나지만 연결을 거부하지는 않는다** (ADR-0005). 미인증 연결이
 * 성립하고 공개 토픽 셋을 구독한다. 갈리는 것은 개인 큐 하나이며 서버가 거부한다.
 */

/** STOMP 프레임 종결자. 소스에 날 바이트를 두면 보이지 않아 escape 로 적는다. */
const NUL = "\0";

/**
 * 하트비트 10초.
 *
 * **틱 간격(31~36초)보다 짧아야 죽은 연결과 "아직 Tick 이 안 온 것" 을 구분한다.**
 * 10초면 한 틱 안에 세 번 확인한다.
 */
const HEARTBEAT_MS = 10_000;

/**
 * destination 과 봉투 타입은 **브라우저 클라이언트와 같은 파일에서 읽는다**
 * (`src/lib/ws.ts`). 원장을 셋이 함께 읽는 것과 같은 이유다 — 목록이 둘이면 한쪽에만
 * destination 이 추가되고, 그때 목은 보내는데 브라우저는 구독하지 않는 상태가 된다.
 *
 * 의존 방향은 **목 → 앱**이다. 그 반대로 두면 프로덕션 번들에 목 코드가 섞인다.
 */
export type Destination = (typeof DESTINATIONS)[number];

type Session = {
  socket: WebSocket;
  /** 구독 id → destination. 한 연결이 같은 destination 을 여러 번 구독할 수 있다. */
  subscriptions: Map<string, string>;
  /** 이 연결이 로그인 상태인가. 개인 큐 구독과 개인 발행이 이 값으로 갈린다. */
  authenticated: boolean;
  /** 핸드셰이크에 실려 온 세션 쿠키 값. 세션 종료로 끊을 대상을 고를 때 쓴다. */
  sessionId: string | undefined;
};

const sessions = new Set<Session>();

/**
 * @param sessionIdOf 핸드셰이크의 쿠키에서 세션 ID 를 뽑는다. 없으면 미인증 연결이다
 */
export function attachStomp(
  server: Server,
  sessionIdOf: (cookieHeader: string | undefined) => string | undefined
): void {
  const wss = new WebSocketServer({ server, path: "/ws" });

  wss.on("connection", (socket, request) => {
    // 웹은 핸드셰이크의 세션 쿠키로 인증한다 — same-origin 이라 브라우저가 업그레이드
    // 요청에 자동으로 싣는다. 쿠키는 포트를 구분하지 않아 :8081 에도 실린다.
    const sessionId = sessionIdOf(request.headers.cookie);
    const session: Session = {
      socket,
      subscriptions: new Map(),
      authenticated: sessionId !== undefined,
      sessionId,
    };
    let connected = false;

    const heartbeat = setInterval(() => {
      if (connected) {
        socket.send(`\n`);
      }
    }, HEARTBEAT_MS);

    socket.on("message", (raw) => {
      const frame = parseFrame(raw.toString());

      // 하트비트는 개행 하나다. 명령이 없으면 그것이다.
      if (!frame) {
        return;
      }

      switch (frame.command) {
        case "CONNECT":
        case "STOMP": {
          // 미인증도 연결된다. 거부는 구독 단계에서 destination 별로 한다.
          connected = true;
          sessions.add(session);
          sendFrame(socket, "CONNECTED", {
            version: "1.2",
            "heart-beat": `${HEARTBEAT_MS},${HEARTBEAT_MS}`,
          });
          return;
        }

        case "SUBSCRIBE": {
          const destination = frame.headers.destination;
          const id = frame.headers.id;

          if (!connected || !id || !destination) {
            sendFrame(socket, "ERROR", { message: "잘못된 프레임입니다." });
            socket.close();
            return;
          }
          if (!DESTINATIONS.includes(destination as Destination)) {
            sendFrame(socket, "ERROR", {
              message: `알 수 없는 destination: ${destination}`,
            });
            socket.close();
            return;
          }
          // 개인 큐는 로그인이 필요하다 (ADR-0005 결정 4). 프론트도 미인증 상태에서는 애초에
          // 구독하지 않으므로 이것은 방어선이지 정상 경로가 아니다.
          if (destination === PERSONAL_DESTINATION && !session.authenticated) {
            sendFrame(socket, "ERROR", {
              message: `로그인이 필요한 destination: ${destination}`,
            });
            socket.close();
            return;
          }

          // ── 여기가 순서 계약이다 ────────────────────────────────────────────
          // **구독 등록을 프레임 수신 시점에 동기적으로 끝낸다.** 클라이언트는 수신 확인
          // 프레임 없이 "구독 호출이 반환한 뒤" 부터 안전하다고 판정하는데(한 연결의 프레임
          // 순서가 보장되므로), 그 판정이 성립하려면 목이 이 등록을 미루지 않아야 한다.
          // 여기에 await 나 setTimeout 을 끼우면 SUBSCRIBE 와 첫 브로드캐스트 사이에
          // 구멍이 열려 ws.md 의 초기 로드 절차가 막으려던 버그가 되살아난다.
          session.subscriptions.set(id, destination);
          return;
        }

        case "UNSUBSCRIBE": {
          const id = frame.headers.id;
          if (id) {
            session.subscriptions.delete(id);
          }
          return;
        }

        case "DISCONNECT": {
          socket.close();
          return;
        }

        default: {
          // SEND 를 포함해 계약에 없는 프레임은 연결 수준 오류다.
          sendFrame(socket, "ERROR", {
            message: `처리하지 않는 프레임입니다: ${frame.command}`,
          });
          socket.close();
        }
      }
    });

    socket.on("close", () => {
      clearInterval(heartbeat);
      sessions.delete(session);
    });
  });
}

/**
 * 그 세션으로 맺은 연결을 끊는다. 끊기 전에 사유를 실어 보낸다 — 클라이언트가 재연결만 할지
 * 미인증으로 전환할지를 이 사유로 판단한다 (`ws.md` §오류).
 *
 * **세션 단위로 끊고 사용자 단위로 끊지 않는다.** 목은 쿠키 값밖에 모르므로 그것이 곧 세션이다.
 *
 * @param sessionId 끊을 세션 쿠키 값
 */
export function endSession(sessionId: string): void {
  for (const session of sessions) {
    if (session.sessionId !== sessionId) {
      continue;
    }
    sendFrame(session.socket, "ERROR", { message: SESSION_ENDED });
    session.socket.close();
  }
}

/** 구독 중인 모든 세션에 보낸다. `/user/queue/me` 는 목에서 접속자 전원이 자기 것을 받는다. */
export function broadcast(destination: Destination, envelope: Envelope): void {
  const body = JSON.stringify(envelope);

  for (const session of sessions) {
    for (const [id, subscribed] of session.subscriptions) {
      if (subscribed === destination) {
        sendFrame(
          session.socket,
          "MESSAGE",
          {
            subscription: id,
            "message-id": `${destination}-${envelope.tickSeq ?? "x"}-${id}`,
            destination,
            "content-type": "application/json",
          },
          body
        );
      }
    }
  }
}

export function connectionCount(): number {
  return sessions.size;
}

function sendFrame(
  socket: WebSocket,
  command: string,
  headers: Record<string, string>,
  body = ""
): void {
  const lines = Object.entries(headers).map(
    ([key, value]) => `${key}:${value}`
  );
  socket.send(`${command}\n${lines.join("\n")}\n\n${body}${NUL}`);
}

function parseFrame(
  raw: string
): { command: string; headers: Record<string, string>; body: string } | null {
  const text = raw.replace(new RegExp(`${NUL}$`), "");
  const separator = text.indexOf("\n\n");
  const head = separator === -1 ? text : text.slice(0, separator);
  const body = separator === -1 ? "" : text.slice(separator + 2);
  const [command, ...headerLines] = head.split("\n");

  if (!command) {
    return null;
  }

  const headers: Record<string, string> = {};
  for (const line of headerLines) {
    const colon = line.indexOf(":");
    if (colon > 0) {
      headers[line.slice(0, colon)] = line.slice(colon + 1);
    }
  }

  return { command, headers, body };
}
