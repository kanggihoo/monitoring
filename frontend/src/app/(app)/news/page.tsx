import { getCurrentUser } from "@/lib/auth";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { NewsScreen } from "./news-screen";

/**
 * 뉴스. 서버 컴포넌트는 껍데기만 그린다 — 기사는 매 Tick 앞에 붙고(기획 10.1.3) 관련도는
 * 요청자마다 다르므로, 여기서 미리 가져오면 그 사이 흐른 Tick 만큼 낡은 목록이 박힌다.
 */
export default async function NewsPage() {
  // 공개 화면이다 (ADR-0005). 보유·관심 필터만 로그인 안내로 갈린다.
  const user = await getCurrentUser();

  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col")}>
      <NewsScreen isLoggedIn={user !== null} />
    </main>
  );
}
