import { Badge } from "@/components/ui/badge";
import { type InquiryStatus, STATUS_LABELS } from "@/lib/inquiry-status";

/**
 * 답변 여부 표시. 색이 아니라 뱃지 안의 글자가 값을 말한다 (DESIGN.md 색상 의존성).
 * 답변이 달린 쪽을 채운 뱃지로 두는데, 사용자가 목록에서 찾는 것이 그 상태다.
 *
 * 사용자 화면과 관리자 화면이 같은 것을 쓴다 — 같은 상태가 두 화면에서 다르게 보이면 안 된다.
 */
export function InquiryStatusBadge({ status }: { status: InquiryStatus }) {
  return (
    <Badge variant={status === "ANSWERED" ? "default" : "outline"}>
      {STATUS_LABELS[status]}
    </Badge>
  );
}
