import { TableSkeleton } from "@/components/table-skeleton";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/** 문의 목록의 로딩 자리. 실제 화면과 같은 순서로 카드 → 표를 그린다. */
export default function Loading() {
  return (
    <main className="flex flex-col gap-4">
      {/* 화면 단위로 한 번만 알린다. 블록 하나하나는 장식이라 읽어 주지 않는다. */}
      <span role="status" className="sr-only">
        문의 목록을 불러오는 중입니다.
      </span>

      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-16" />
          <Skeleton className="h-4 w-48" />
        </CardHeader>

        <CardContent className="px-0">
          <TableSkeleton columns={3} rows={5} />
        </CardContent>
      </Card>
    </main>
  );
}
