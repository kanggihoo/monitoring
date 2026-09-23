"use client";

import { useEffect } from "react";

import { LAST_PROVIDER_KEY } from "@/lib/provider";

/**
 * 마지막으로 성공한 로그인 방법을 기록한다. 로그인 화면이 이 값을 배지로 보여줘, 계정 연결이
 * 없는 상태에서 다른 방법으로 들어가 빈 계정을 새로 만드는 사고를 줄인다.
 *
 * 로그인 화면의 클릭 시점에 쓰지 않는 이유: 취소·실패한 시도가 직전에 성공했던 방법을 덮어써
 * 돌아온 사용자를 엉뚱한 버튼으로 안내하게 된다. 인증이 끝난 화면에서만 쓴다.
 */
export function RememberLoginProvider({ provider }: { provider: string }) {
  useEffect(() => {
    localStorage.setItem(LAST_PROVIDER_KEY, provider);
  }, [provider]);

  return null;
}
