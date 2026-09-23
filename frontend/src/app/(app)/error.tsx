"use client";

import { useEffect } from "react";

import { Button } from "@/components/ui/button";

/**
 * 셸 안 화면의 복구 경계. 마켓·뉴스·랭킹·복기가 여기 걸린다 — 넷 다 조회 실패를 던져서
 * 알리고 복구 방법이 "다시 시도" 하나로 같아서 독립 복구 단위가 하나다 (관리자·문의 화면과
 * 같은 결). 경계가 없으면 실패가 루트 `error.tsx` 까지 튀어 레일과 헤더째로 사라진다.
 *
 * 자기 경계를 가진 묶음(`admin`, `mypage/inquiries`)은 그쪽이 더 가까워 여기까지 오지 않는다.
 *
 * 원인 코드는 화면에 싣지 않는다. 사람에게는 다음에 할 행동만 준다.
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("화면을 불러오지 못했습니다", error);
  }, [error]);

  return (
    <main className="flex flex-col items-center gap-4 py-16">
      <p className="text-sm text-muted-foreground">
        정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
      </p>
      <Button variant="outline" onClick={reset}>
        다시 시도
      </Button>
    </main>
  );
}
