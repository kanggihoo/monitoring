import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";

/** 표가 아니라 카드 목록이라 사용자 목록과 골격이 다르다. 그래서 이 세그먼트가 따로 갖는다. */
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
          <Skeleton className="h-4 w-56" />
        </CardHeader>
        <CardContent>
          <div className="flex gap-2">
            <Skeleton className="h-5 w-12" />
            <Skeleton className="h-5 w-16" />
            <Skeleton className="h-5 w-20" />
          </div>
        </CardContent>
      </Card>

      {/* 카드 두 장만 채운다. 기다리는 화면을 실제보다 길게 늘이지 않는다. */}
      {Array.from({ length: 2 }, (_, card) => (
        <Card key={card}>
          <CardHeader>
            <Skeleton className="h-5 w-48" />
            <Skeleton className="h-4 w-40" />
          </CardHeader>
          <CardContent>
            <div className="flex flex-col gap-2">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-3/4" />
            </div>
          </CardContent>
          <Separator />
          <CardContent>
            <div className="flex flex-col gap-2">
              <Skeleton className="h-4 w-10" />
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-8 w-24" />
            </div>
          </CardContent>
        </Card>
      ))}
    </main>
  );
}
