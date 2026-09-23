import { cache } from "react";
import { notFound, redirect } from "next/navigation";

import { backendFetch } from "@/lib/backend";

import "server-only";

/** 백엔드가 돌려주는 사용자 정보. 용어는 `../../backend/docs/CONTEXT.md` 를 따른다. */
export type User = {
  id: number;
  /** IdP 가 이메일을 주지 않으면 null 이다. */
  email: string | null;
  nickname: string;
  role: "USER" | "ADMIN";
  /** 이 사용자가 쓰는 로그인 방법. 계정 연결 기능이 없어 지금은 항상 하나다. */
  providers: string[];
};

/**
 * 현재 사용자를 조회한다. 받은 세션 쿠키를 그대로 백엔드에 넘겨 실제로 검증한다.
 * React `cache` 로 감싸 한 번의 렌더에서 여러 번 불러도 백엔드 호출은 한 번이다.
 *
 * @returns 로그인 상태면 사용자, 미인증이면 null
 * @throws 백엔드가 401 이 아닌 오류를 돌려줄 때
 */
export const getCurrentUser = cache(async (): Promise<User | null> => {
  const response = await backendFetch("/api/v1/users/me");

  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`현재 사용자 조회에 실패했습니다 (${response.status})`);
  }

  const body = (await response.json()) as { data: User };
  return body.data;
});

/**
 * 로그인이 필요한 화면에서 쓴다. 미인증이면 렌더를 멈추고 로그인 화면으로 보낸다.
 * `proxy.ts` 의 쿠키 확인은 낙관적 체크일 뿐이라 여기가 실제 방어선이다.
 */
export async function requireUser(): Promise<User> {
  const user = await getCurrentUser();
  if (!user) {
    redirect("/login");
  }
  return user;
}

/**
 * 관리자 전용 화면에서 쓴다. 관리자가 아니면 로그인 화면으로 보내지 않고 **404** 를 낸다 —
 * 리다이렉트는 "여기에 뭔가 있다"를 알려주므로, 화면의 존재 자체를 숨긴다.
 *
 * 이 404 는 인가가 아니라 UX 다. 실제 방어선은 백엔드가 주는 403 이고 여기는 낙관적 가드다
 * (ADR-0001 과 같은 결). 미인증 사용자는 여기까지 오지 않는다 — `proxy.ts` 가 세션 쿠키 부재를
 * 먼저 잡아 로그인 화면으로 보내므로, 404 는 **로그인한 비관리자**에게만 나온다.
 *
 * 관리자 레이아웃과 각 페이지에서 모두 부른다. 중복이지만 페이지를 추가하며 빠뜨리는 사고가
 * 훨씬 흔하고, 조회에 렌더 단위 캐시가 걸려 있어 호출 비용이 늘지 않는다.
 */
export async function requireAdmin(): Promise<User> {
  const user = await requireUser();
  if (user.role !== "ADMIN") {
    notFound();
  }
  return user;
}
