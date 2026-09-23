import Link from "next/link";

import { Button } from "@/components/ui/button";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

/**
 * 셸 안의 404. 레일과 헤더를 그대로 둔 채 본문만 바뀐다 — 길을 잃은 사람에게 돌아갈 길을
 * 화면에 남겨 두는 편이 전면 404 보다 낫다.
 *
 * `lib/auth.ts` 의 `requireAdmin()` 이 관리자가 아닌 사용자에게 던지는 `notFound()` 가
 * 여기로 온다. 그쪽 주석이 말하듯 그 404 는 인가가 아니라 UX 이므로, 없는 주소를 친 것과
 * 똑같이 보여야 화면의 존재가 드러나지 않는다.
 */
export default function NotFound() {
  return (
    <main
      className={cn(
        PAGE_CONTAINER,
        "flex min-h-0 flex-1 flex-col items-center justify-center gap-4 py-16 text-center"
      )}
    >
      <h1 className="text-lg font-semibold tracking-tight">
        페이지를 찾을 수 없습니다
      </h1>
      <p className="text-sm text-muted-foreground">
        주소가 잘못되었거나 더 이상 존재하지 않는 페이지입니다.
      </p>
      <Button variant="outline" nativeButton={false} render={<Link href="/" />}>
        홈으로
      </Button>
    </main>
  );
}
