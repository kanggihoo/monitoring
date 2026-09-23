"use client";

import { useEffect } from "react";

import { Button } from "@/components/ui/button";

/**
 * 관리자 화면의 복구 경계. 관리자 조회는 실패를 던져서 알린다 — 목록이 비어 보이는 것과
 * 못 불러온 것을 구별할 수 없게 만들지 않기 위해서다. 그 실패를 여기서 받는다.
 *
 * 경계를 관리자 묶음 하나에만 둔다. 세 화면이 같은 API 를 보고 복구 방법도 같아서 독립 복구
 * 단위가 하나다.
 *
 * 원인 코드는 화면에 싣지 않는다. 사람에게는 다음에 할 행동만 준다.
 */
export default function AdminError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("관리자 화면을 불러오지 못했습니다", error);
  }, [error]);

  return (
    <main className="flex flex-col items-center gap-4 py-16">
      <p className="text-sm text-muted-foreground">
        관리자 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
      </p>
      <Button variant="outline" onClick={reset}>
        다시 시도
      </Button>
    </main>
  );
}
