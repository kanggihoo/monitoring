"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

import { takeReturnPath } from "@/lib/login-return";

/**
 * 로그인 직후 보던 화면으로 되돌린다. 백엔드는 고정 주소로 복귀시키므로(오픈 리다이렉트 표면을
 * 만들지 않기 위해서다 — ADR-0005) 그 다음 한 걸음을 프론트가 놓는다.
 *
 * **로그인한 상태에서만 렌더된다.** 그러니 저장된 값이 있다는 것은 곧 방금 로그인이 끝났다는
 * 뜻이고, 읽으면서 지우므로 두 번 움직이지 않는다.
 */
export function LoginReturnProvider() {
  const router = useRouter();

  useEffect(() => {
    const path = takeReturnPath();
    if (path && path !== window.location.pathname + window.location.search) {
      router.replace(path);
    }
  }, [router]);

  return null;
}
