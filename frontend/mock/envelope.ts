import type { ServerResponse } from "node:http";

import { isoUtc } from "./time.ts";

/**
 * 응답 봉투는 기존 `ApiResponse` 형태를 그대로 쓴다 (`_common.md §3`). 조회 응답 처리기가
 * `data` 를 꺼내는 방식이 목과 Spring 에서 같아야 한다 — 다르면 이관 시점에 호출부가
 * 전부 바뀐다.
 */

export function sendOk(
  response: ServerResponse,
  data: unknown,
  status = 200
): void {
  send(response, status, {
    success: true,
    status,
    message: null,
    data,
    timestamp: isoUtc(),
  });
}

/**
 * 실패 응답. 구조는 `ErrorResponse` 를 따르고 식별은 **HTTP 상태 + `message`** 로 한다 —
 * 별도 비즈니스 에러 코드를 두지 않는다 (`_common.md §4`).
 */
export function sendError(
  response: ServerResponse,
  status: number,
  message: string,
  path: string
): void {
  send(response, status, {
    success: false,
    status,
    message,
    errors: [],
    path,
    timestamp: isoUtc(),
  });
}

/** 204 는 본문이 없으므로 `ApiResponse` 를 쓰지 않는다 (`_common.md §3`). */
export function sendNoContent(response: ServerResponse): void {
  response.writeHead(204).end();
}

function send(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  response
    .writeHead(status, {
      "content-type": "application/json; charset=utf-8",
      "content-length": Buffer.byteLength(payload),
    })
    .end(payload);
}
