import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";

/** 표가 아니라 항목 목록이라 사용자 목록과 골격이 다르다. 그래서 이 세그먼트가 따로 갖는다. */
export default function Loading() {
  return (
    <main className="flex flex-col gap-4">
      <span role="status" className="sr-only">
        사용자 정보를 불러오는 중입니다.
      </span>

      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-4 w-20" />
        </CardHeader>

        <CardContent>
          <div className="flex flex-col gap-3">
            {/* 이메일·로그인 방법·권한·가입일·활성 세션 다섯 줄. */}
            {Array.from({ length: 5 }, (_, row) => (
              <div key={row} className="flex gap-4">
                <Skeleton className="h-4 w-24 shrink-0" />
                <Skeleton className="h-4 w-40" />
              </div>
            ))}
          </div>
        </CardContent>

        <Separator />

        <CardContent>
          <div className="flex flex-col gap-3">
            <Skeleton className="h-4 w-10" />
            <div className="flex gap-2">
              <Skeleton className="h-8 w-28" />
              <Skeleton className="h-8 w-28" />
            </div>
            <Skeleton className="h-4 w-full max-w-md" />
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
