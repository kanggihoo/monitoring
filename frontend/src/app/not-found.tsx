import Link from "next/link";
import { FileQuestion } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * 루트 404. 셸(`(app)/layout.tsx`) 밖이므로 전역 복구 경계(`error.tsx`)와 같은 전면 카드다.
 *
 * 여기로 오는 것은 어느 그룹에도 속하지 않는 경로다 — 오타 URL 이나 지워진 링크. 로그인한
 * 사용자가 앱 안에서 만나는 404 는 `(app)/not-found.tsx` 가 셸 안에서 받는다.
 */
export default function NotFound() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background px-4 text-center">
      <div className="flex max-w-md flex-col items-center gap-4 rounded-xl border border-border bg-card p-8 shadow-xs">
        <div className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
          <FileQuestion className="size-6" />
        </div>

        <h1 className="text-xl font-semibold tracking-tight text-foreground">
          페이지를 찾을 수 없습니다
        </h1>

        <p className="text-sm leading-relaxed text-muted-foreground">
          주소가 잘못되었거나 더 이상 존재하지 않는 페이지입니다.
        </p>

        <Button
          nativeButton={false}
          render={<Link href="/" />}
          className="mt-2 w-full"
        >
          홈으로
        </Button>
      </div>
    </main>
  );
}
