import { requireUser } from "@/lib/auth";
import { readBackend } from "@/lib/backend";
import type { DailyReportEntry, InstrumentMeta } from "@/lib/market-types";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { ReviewScreen } from "./review-screen";

/**
 * 복기. 서버 컴포넌트는 **일차 목록과 종목 메타만** 가져온다 — 리포트 본문은 사용자가 고른
 * 일차에 달려 있고, 정산 발표 전이면 계약이 409 로 답하므로(`rpt.md`) 화면 쪽에서 받는다.
 *
 * 리포트는 확정된 스냅샷이라 Tick 을 구독하지 않는다. 마켓 화면과 달리 여기는 정지 화면이다.
 */
export default async function ReviewPage() {
  await requireUser();
  const [{ items }, { instruments }] = await Promise.all([
    readBackend<{ items: DailyReportEntry[] }>(
      "/api/v1/reports/daily",
      "복기 일차 목록 조회에 실패했습니다"
    ),
    readBackend<{ instruments: InstrumentMeta[] }>(
      "/api/v1/market/instruments",
      "종목 목록 조회에 실패했습니다"
    ),
  ]);

  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col")}>
      <ReviewScreen days={items} instruments={instruments} />
    </main>
  );
}
