import { getCurrentUser } from "@/lib/auth";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { RankingScreen } from "./ranking-screen";

/**
 * 랭킹. 서버 컴포넌트는 껍데기만 그린다 — 순위는 매 Tick 바뀌고 암전 구간에서는 서버가
 * 아예 주지 않으므로, 여기서 미리 가져오면 그 사이 흐른 Tick 만큼 낡은 값이 박힌다.
 */
export default async function RankingPage() {
  // 공개 화면이다 (ADR-0005). 내 순위만 로그인 안내로 갈린다.
  const user = await getCurrentUser();

  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col")}>
      <RankingScreen isLoggedIn={user !== null} />
    </main>
  );
}
