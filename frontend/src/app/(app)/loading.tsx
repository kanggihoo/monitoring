import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

/**
 * 셸 안 화면의 로딩 자리. 자기 로딩 자리를 가진 화면(`admin`, `mypage/inquiries`)은 그쪽이
 * 더 가까워 여기까지 오지 않는다.
 *
 * 화면마다 격자가 달라 특정 레이아웃을 흉내 내지 않는다 — 한 화면에 맞추면 나머지에서 오히려
 * 더 튄다. 카드 한 장의 자리만 잡는다.
 */
export default function Loading() {
  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col")}>
      {/* 화면 단위로 한 번만 알린다. 블록 하나하나는 장식이라 읽어 주지 않는다. */}
      <span role="status" className="sr-only">
        불러오는 중입니다.
      </span>

      <Card size="sm" className="min-h-0 flex-1">
        <CardContent className="flex shrink-0 items-center justify-between gap-3">
          <Skeleton className="h-8 w-48" />
          <Skeleton className="h-8 w-32" />
        </CardContent>

        <CardContent className="min-h-56 flex-1">
          <Skeleton className="h-full w-full" />
        </CardContent>
      </Card>
    </main>
  );
}
