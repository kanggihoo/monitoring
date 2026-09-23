"use client";

import { useEffect } from "react";

import { identifyUser } from "@/lib/analytics";

/**
 * 로그인한 사용자를 분석 도구에 알려 기기가 바뀌어도 한 사람으로 묶는다.
 *
 * 태그를 얹는 `Analytics` 와 달리 이쪽이 `(app)` 레이아웃에 있는 이유는 현재 사용자 조회가
 * 거기 있기 때문이다. 루트로 올리면 로그인 화면에서도 조회가 한 번 더 나간다.
 */
export function AnalyticsIdentify({ userId }: { userId: number }) {
  useEffect(() => {
    identifyUser(userId);
  }, [userId]);

  return null;
}
