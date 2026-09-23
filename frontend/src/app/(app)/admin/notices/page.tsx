import { requireAdmin } from "@/lib/auth";

import { NoticeForm } from "./notice-form";

/**
 * 전원 공지 발송. 관리자 콘솔 안의 페이지라 레이아웃과 권한 가드를 그대로 쓴다.
 *
 * 보낸 공지의 목록을 두지 않는다 — 알림에는 조회 API 가 개인 것 하나뿐이고(`noti.md`),
 * 관리자도 전원에 포함되므로 자기 알림 센터에서 방금 보낸 것을 그대로 본다.
 */
export default async function AdminNoticesPage() {
  await requireAdmin();

  return (
    <main className="flex flex-col gap-4">
      <NoticeForm />
    </main>
  );
}
