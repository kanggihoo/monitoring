import { TableSkeleton } from "@/components/table-skeleton";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/** 감사 로그의 로딩 자리. 검색줄 대신 필터 뱃지 세 개가 오는 것만 사용자 목록과 다르다. */
export default function Loading() {
  return (
    <main className="flex flex-col gap-4">
      <span role="status" className="sr-only">
        감사 로그를 불러오는 중입니다.
      </span>

      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-24" />
          <Skeleton className="h-4 w-72" />
        </CardHeader>

        <CardContent>
          <div className="flex gap-2">
            <Skeleton className="h-5 w-12" />
            <Skeleton className="h-5 w-20" />
            <Skeleton className="h-5 w-24" />
          </div>
        </CardContent>

        <CardContent className="px-0">
          <TableSkeleton columns={5} />
        </CardContent>
      </Card>
    </main>
  );
}
