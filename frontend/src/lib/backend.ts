import { cookies } from "next/headers";
import { notFound } from "next/navigation";

import { targetFor } from "@/lib/mock-ledger";

import "server-only";

// 서버 컴포넌트는 상대경로를 쓸 수 없어 백엔드 주소가 필요하다. 브라우저는 이 값을 모르고,
// 항상 상대경로 /api/* 를 호출한다 (NEXT_PUBLIC_* 로 노출하지 않는 이유).
const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
const MOCK_ORIGIN = process.env.MOCK_ORIGIN ?? "http://localhost:8081";

/**
 * 이 경로에 답할 백엔드의 주소. **`next.config.ts` 의 rewrite 와 같은 원장을 같은 순서로
 * 평가한다** — 브라우저와 서버 컴포넌트가 서로 다른 백엔드를 보는 일이 없어야 한다.
 *
 * **프로덕션에서는 언제나 `BACKEND_ORIGIN` 이다.** 그 환경에 목이 없으므로 원장이
 * 프로덕션에서 목 규칙을 아예 펼치지 않는다 (`ledger()`). 배포 설정에 스위치를 넣는 대신
 * 원장이 막는 이유는, 빠뜨린 한 줄이 컨테이너를 healthy 로 남겨 둔 채 화면만 비우기
 * 때문이다 — 헬스체크(`/login`)는 종목을 읽지 않는다.
 *
 * @param path `/api` 로 시작하는 경로
 */
export function originFor(path: string): string {
  return targetFor(path) === "mock" ? MOCK_ORIGIN : BACKEND_ORIGIN;
}

/**
 * 서버에서 백엔드를 호출한다. 받은 세션 쿠키를 그대로 넘겨 백엔드가 실제로 검증하게 한다.
 *
 * **조회 전용이다.** 상태 변경은 브라우저가 `lib/api.ts` 의 `apiFetch` 로 보낸다 — 위조 방지
 * 토큰을 한 곳에서만 붙이기 위해서다 (ADR-0001).
 *
 * @param path `/api` 로 시작하는 경로 (절대 URL 금지)
 */
export async function backendFetch(path: string): Promise<Response> {
  return fetch(`${originFor(path)}${path}`, {
    headers: { cookie: (await cookies()).toString() },
    cache: "no-store",
  });
}

/** 백엔드가 쓰는 페이지 응답. 필드 이름은 `backend/docs/conventions/code.md` 6절을 따른다. */
export type PageResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

/**
 * 조회 응답의 공통 처리. 404 는 화면을 숨기고, 나머지 실패는 던져 라우트 오류로 다룬다 —
 * 목록이 비어 보이는 것과 못 불러온 것을 구별할 수 없게 만들지 않는다.
 *
 * @param path 조회할 경로
 * @param failureMessage 404 가 아닌 실패에 담을 문구. 화면 문구가 아니라 복구 경계
 *                       (`error.tsx`) 의 콘솔 기록에 남아 어느 조회가 깨졌는지 가리킨다
 * @throws 404 면 렌더를 멈추고 404 화면을 낸다. 그 밖의 실패는 Error 를 던진다
 */
export async function readBackend<T>(
  path: string,
  failureMessage: string
): Promise<T> {
  const response = await backendFetch(path);

  if (response.status === 404) {
    notFound();
  }
  if (!response.ok) {
    throw new Error(`${failureMessage} (${response.status})`);
  }

  const body = (await response.json()) as { data: T };
  return body.data;
}
