/**
 * 문의 상태와 그 화면 문구.
 *
 * 조회 함수(`lib/inquiry.ts`)와 나누어 둔다 — 그쪽은 `server-only` 라 브라우저에서 불러오면
 * 빌드가 깨진다. 상태 표시는 클라이언트 컴포넌트에서도 쓸 수 있어야 한다.
 */

/** 답변 전과 답변 후 둘뿐이다. 처리 중·보류 같은 중간 상태는 없다. */
export type InquiryStatus = "OPEN" | "ANSWERED";

/** 상태 필터로 고를 수 있는 값. 주소로 들어온 값을 이 목록으로 검증한다. */
export const INQUIRY_STATUSES = ["OPEN", "ANSWERED"] as const;

/** 상태를 화면 문구로 바꾼다. 값 자체는 백엔드의 enum 이다. */
export const STATUS_LABELS: Record<InquiryStatus, string> = {
  OPEN: "답변 전",
  ANSWERED: "답변 완료",
};
