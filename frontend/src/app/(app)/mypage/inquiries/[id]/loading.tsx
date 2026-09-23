import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";

/** 표가 아니라 글이라 목록과 골격이 다르다. 그래서 이 세그먼트가 따로 갖는다. */
export default function Loading() {
  return (
    <main className="flex flex-col gap-4">
      <span role="status" className="sr-only">
        문의를 불러오는 중입니다.
      </span>

      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-52" />
          <Skeleton className="h-4 w-40" />
        </CardHeader>

        <CardContent>
          <div className="flex flex-col gap-2">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        </CardContent>

        <Separator />

        <CardContent>
          <div className="flex flex-col gap-2">
            <Skeleton className="h-4 w-10" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-1/2" />
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
