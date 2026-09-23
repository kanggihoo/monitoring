import { type PageResponse, readBackend } from "@/lib/backend";

import "server-only";

/**
 * 관리자 목록의 한 행.
 *
 * 식별자와 가입일을 함께 보여주는 이유는 동명이인 때문이다 — 이메일이 없는 사용자끼리는
 * 닉네임만으로 구별할 수 없다.
 */
export type AdminUser = {
  id: number;
  /** 이메일이 없는 사용자는 null 이다. */
  email: string | null;
  nickname: string;
  role: "USER" | "ADMIN";
  createdAt: string;
  providers: string[];
};

export type AdminUserDetail = AdminUser & { activeSessionCount: number };

/** 감사 로그에 남는 행위. 샌드박스 진입은 남기지 않는다 (backend ADR-0003 8번). */
export const AUDIT_ACTIONS = ["USER_ROLE_UPDATE", "SESSION_REVOKE"] as const;

export type AuditAction = (typeof AUDIT_ACTIONS)[number];

/**
 * 감사 로그 한 줄.
 *
 * 두 사용자 식별자는 참조가 아니라 **그 시점의 기록**이다. 그 사이 대상이 탈퇴했다면 이 값으로
 * 사용자를 조회할 수 없으며, 화면은 그 경우를 정상으로 다뤄야 한다.
 */
export type AuditLog = {
  id: number;
  actorUserId: number;
  action: AuditAction;
  targetUserId: number | null;
  detail: string | null;
  createdAt: string;
};

/** 한 화면에 담는 행 수. 백엔드 기본값과 맞춰 둔다. */
export const PAGE_SIZE = 20;

/**
 * 사용자를 검색한다. 정렬은 가입일 최신순으로 백엔드가 고정하므로 정렬 인자가 없다.
 *
 * @param keyword 이메일·닉네임 부분일치 검색어. 비우면 최근 가입자부터 나온다
 */
export async function searchUsers(
  keyword: string,
  page: number
): Promise<PageResponse<AdminUser>> {
  const query = new URLSearchParams({
    keyword,
    page: String(page),
    size: String(PAGE_SIZE),
  });

  return read(`/api/v1/admin/users?${query}`);
}

/**
 * 사용자 상세를 조회한다.
 *
 * @throws 백엔드가 404 를 주면 렌더를 멈추고 404 화면을 낸다 (Test User 이거나 없는 식별자다)
 */
export async function getUser(id: string): Promise<AdminUserDetail> {
  return read(`/api/v1/admin/users/${encodeURIComponent(id)}`);
}

/**
 * 감사 로그를 최신순으로 읽는다.
 *
 * @param action 걸러낼 행위 종류. null 이면 전체
 */
export async function searchAuditLogs(
  action: AuditAction | null,
  page: number
): Promise<PageResponse<AuditLog>> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(PAGE_SIZE),
  });
  if (action) {
    query.set("action", action);
  }

  return read(`/api/v1/admin/audit-logs?${query}`);
}

async function read<T>(path: string): Promise<T> {
  return readBackend<T>(path, "관리자 조회에 실패했습니다");
}
