"use client";

import { useSyncExternalStore } from "react";
import Link from "next/link";

import { LoginProviders } from "@/components/login-providers";
import { peekCancelPath, takeCancelPath } from "@/lib/login-return";

/** 값이 바뀌는 것을 구독할 필요가 없다 — 쓰는 쪽은 로그인 이후 화면이다. */
const noSubscribe = () => () => {};

export function LoginButtons() {
  // 로그인을 시작한 화면이 담겨 있다. 취소·실패해도 돌아갈 길을 남긴다 (ADR-0005) —
  // 취소가 곧 이탈이 되지 않도록.
  //
  // **복귀 경로가 아니라 취소 경로다.** 안내 모달에서 `내 계좌` 로 로그인을 시작했다가
  // OAuth 가 실패하면 백엔드가 이 화면으로 되돌리는데, 복귀 경로(`/mypage`)로 보내면
  // 프록시가 다시 여기로 돌려보내 제자리를 맴돈다.
  const cancelPath = useSyncExternalStore(
    noSubscribe,
    peekCancelPath,
    () => null
  );

  return (
    <div className="flex flex-col gap-3">
      <LoginProviders />
      {cancelPath && (
        <Link
          href={cancelPath}
          // 로그인하지 않겠다는 신호다. 담아 둔 경로를 여기서 지운다 — 남겨 두면 나중에
          // 로그인한 사람이 이 화면으로 끌려온다.
          onClick={() => takeCancelPath()}
          className="mx-auto mt-1 w-fit text-base text-muted-foreground underline-offset-4 hover:underline"
        >
          로그인하지 않고 돌아가기
        </Link>
      )}
    </div>
  );
}
