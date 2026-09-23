import { getCurrentUser } from "@/lib/auth";
import { readBackend } from "@/lib/backend";
import type { InstrumentMeta } from "@/lib/market-types";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { HomeDashboard } from "./home-dashboard";

/**
 * 대시보드 — 오늘의 시장 한눈에. 종목 메타(IPO 때만 바뀜)는 서버 컴포넌트가 가져오고, Tick 으로
 * 흐르는 것(시세·평가액·순위·히트맵)은 클라이언트 스토어가 그린다 (§G 서버/클라이언트 경계).
 */
export default async function DashboardPage() {
  // 공개 화면이다 (ADR-0005). 미인증이면 이름 붙일 사람이 없다.
  const user = await getCurrentUser();
  const { instruments } = await readBackend<{ instruments: InstrumentMeta[] }>(
    "/api/v1/market/instruments",
    "종목 목록 조회에 실패했습니다"
  );

  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col gap-3")}>
      <h1 className="shrink-0 text-lg font-semibold tracking-tight">
        {user ? `${user.nickname}님의 오늘` : "오늘의 시장"}
      </h1>
      <HomeDashboard instruments={instruments} isLoggedIn={user !== null} />
    </main>
  );
}
