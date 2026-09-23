import { Badge } from "@/components/ui/badge";
import type { Phase } from "@/lib/market-types";

/** phase 문구 (`_common.md §7.2`). 무료 타임은 phase 가 아니라 플래그라 따로 붙인다. */
export const PHASE_LABEL: Record<Phase, string> = {
  BRIEFING: "개장 브리핑",
  TRADING: "재생 중",
  SETTLING: "정산 중",
  REVIEW_ONLY: "열람 전용",
};

export function PhaseBadge({
  phase,
  feeFree = false,
}: {
  phase: Phase;
  feeFree?: boolean;
}) {
  return (
    <span className="inline-flex items-center gap-1">
      <Badge variant={phase === "TRADING" ? "default" : "outline"}>
        {PHASE_LABEL[phase]}
      </Badge>
      {feeFree && <Badge variant="secondary">수수료 무료</Badge>}
    </span>
  );
}
