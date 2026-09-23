import { type PageResponse, readBackend } from "@/lib/backend";
import type { InquiryStatus } from "@/lib/inquiry-status";

import "server-only";

/** 목록의 한 줄. 본문과 답변은 담기지 않는다 — 상세에서 읽는다. */
export type InquirySummary = {
  id: number;
  title: string;
  status: InquiryStatus;
  /** 답변 전이면 null. */
  answeredAt: string | null;
  createdAt: string;
};

/** 문의 하나의 전문. 질문과 답변을 나란히 보여주는 데 쓴다. */
export type Inquiry = {
  id: number;
  title: string;
  content: string;
  status: InquiryStatus;
  /** 답변 전이면 null. */
  answer: string | null;
  answeredAt: string | null;
  createdAt: string;
};

/**
 * 관리자가 보는 문의 하나. 목록에서 바로 답변하므로 본문과 답변이 함께 온다.
 *
 * `answeredBy` 는 참조가 아니라 그 시점의 기록이다 — 그 관리자가 탈퇴했다면 이 값으로 사용자를
 * 조회할 수 없으며, 그것이 정상이다. 반면 작성자는 외래키가 있어 항상 살아 있다.
 */
export type AdminInquiry = Inquiry & {
  authorUserId: number;
  authorNickname: string;
  answeredBy: number | null;
};

/** 한 화면에 담는 행 수. 백엔드 기본값과 맞춰 둔다. */
export const PAGE_SIZE = 20;

/**
 * 내 문의를 읽는다. 정렬은 최신순으로 백엔드가 고정하므로 정렬 인자가 없다.
 * 다른 사용자의 문의는 애초에 담기지 않는다.
 */
export async function searchMyInquiries(
  page: number
): Promise<PageResponse<InquirySummary>> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(PAGE_SIZE),
  });

  return read(`/api/v1/inquiries?${query}`);
}

/**
 * 내 문의 하나를 읽는다.
 *
 * @throws 남의 문의이거나 없는 식별자면 백엔드가 404 를 주고, 렌더를 멈추고 404 화면을 낸다
 */
export async function getMyInquiry(id: string): Promise<Inquiry> {
  return read(`/api/v1/inquiries/${encodeURIComponent(id)}`);
}

/**
 * 관리자용. 들어온 문의를 최신순으로 읽는다.
 *
 * @param status 걸러낼 상태. null 이면 전체
 */
export async function searchInquiries(
  status: InquiryStatus | null,
  page: number
): Promise<PageResponse<AdminInquiry>> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(PAGE_SIZE),
  });
  if (status) {
    query.set("status", status);
  }

  return read(`/api/v1/admin/inquiries?${query}`);
}

async function read<T>(path: string): Promise<T> {
  return readBackend<T>(path, "문의를 불러오지 못했습니다");
}
