import { requireAdmin } from "@/lib/auth";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { AdminNav } from "./admin-nav";

/**
 * 관리자 화면의 공통 껍데기.
 *
 * 상단 바를 그대로 받아야 로그아웃과 샌드박스 안내 띠가 동작하므로 기존 레이아웃 그룹 안에 둔다.
 *
 * 여기서 권한을 확인하고 **각 페이지에서도 다시 확인한다.** 중복이지만 페이지를 추가하며
 * 빠뜨리는 사고가 훨씬 흔하고, 조회에 렌더 단위 캐시가 걸려 있어 호출 비용이 늘지 않는다.
 */
export default async function AdminLayout({ children }: LayoutProps<"/admin">) {
  await requireAdmin();

  return (
    <div className={cn(PAGE_CONTAINER, "flex flex-col gap-6 py-3")}>
      <AdminNav />
      {children}
    </div>
  );
}
