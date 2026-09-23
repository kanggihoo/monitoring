"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, RefreshCw } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * 루트 레이아웃 내부 오류 경계.
 *
 * 루트 레이아웃 내부의 모든 페이지 및 컴포넌트에서 예외가 발생했을 때
 * 500 오류 화면 대신 사용자에게 일시적 오류 안내 및 재시도 수단을 제공합니다.
 */
export default function RootError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();

  useEffect(() => {
    console.error("애플리케이션 렌더링 중 오류가 발생했습니다:", error);
  }, [error]);

  // Next.js 는 프로덕션 빌드에서 서버 컴포넌트의 오류 메시지를 digest 로 치환한다.
  // 실제 메시지가 클라이언트까지 오지 않으므로 **message 를 들여다보는 판정은 로컬
  // 개발에서만 성립한다.** 그 사실을 조건에 적어 둔다 — 안 그러면 배포 환경에서도
  // 이 안내가 뜬다고 믿게 되고, 실제로는 한 번도 뜨지 않는다.
  const isDev = process.env.NODE_ENV !== "production";
  const isLocalBackendDown =
    isDev &&
    (error.message?.includes("fetch failed") ||
      error.message?.includes("ECONNREFUSED"));

  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background px-4 text-center">
      <div className="flex max-w-md flex-col items-center gap-4 rounded-xl border border-border bg-card p-8 shadow-xs">
        <div className="flex size-12 items-center justify-center rounded-full bg-destructive/10 text-destructive">
          <AlertCircle className="size-6" />
        </div>

        <h1 className="text-xl font-semibold tracking-tight text-foreground">
          {isLocalBackendDown
            ? "백엔드 서버에 연결할 수 없습니다"
            : "일시적인 오류가 발생했습니다"}
        </h1>

        <p className="text-sm leading-relaxed text-muted-foreground">
          {isLocalBackendDown
            ? "백엔드(8080) 서버가 실행 중이지 않거나 네트워크 연결이 원활하지 않습니다. 서버 상태를 확인한 후 다시 시도해 주세요."
            : "요청을 처리하는 중에 예상치 못한 문제가 발생했습니다. 잠시 후 다시 시도해 주세요."}
        </p>

        {isDev && error.message && (
          <pre className="max-h-32 w-full overflow-auto rounded bg-muted p-2 text-left font-mono text-xs text-muted-foreground">
            {error.message}
          </pre>
        )}

        {/* 배포 환경에서 사용자가 전달할 수 있는 유일한 단서다. 서버 로그의
            같은 digest 를 찾으면 원본 스택에 닿는다. */}
        {!isDev && error.digest && (
          <p className="font-mono text-xs text-muted-foreground">
            오류 코드 {error.digest}
          </p>
        )}

        <div className="mt-2 flex w-full gap-2">
          <Button
            variant="outline"
            className="flex-1"
            onClick={() => router.push("/login")}
          >
            로그인 화면으로
          </Button>
          <Button className="flex-1 gap-1.5" onClick={() => reset()}>
            <RefreshCw className="size-4" />
            다시 시도
          </Button>
        </div>
      </div>
    </main>
  );
}
