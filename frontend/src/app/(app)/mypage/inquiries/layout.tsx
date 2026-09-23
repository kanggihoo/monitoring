import { requireUser } from "@/lib/auth";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

/**
 * 문의 화면의 공통 껍데기. 읽는 폭은 마이페이지와 같게 두고 왼쪽 축은 상단 바와 맞춘다.
 *
 * 여기서 로그인을 확인하고 **각 페이지에서도 다시 확인한다.** 중복이지만 페이지를 추가하며
 * 빠뜨리는 사고가 훨씬 흔하고, 조회에 렌더 단위 캐시가 걸려 있어 호출 비용이 늘지 않는다
 * (관리자 화면과 같은 결).
 */
export default async function InquiriesLayout({
  children,
}: LayoutProps<"/mypage/inquiries">) {
  await requireUser();

  return (
    <div className={cn(PAGE_CONTAINER, "py-3")}>
      <div className="flex flex-col gap-3">{children}</div>
    </div>
  );
}
