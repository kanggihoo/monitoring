import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";

import { isPublicRoute } from "@/lib/public-routes";

/** 백엔드가 심는 세션 쿠키 이름. */
const SESSION_COOKIE = "SESSION";

/**
 * 자리를 옮긴 화면. 옛 주소로 남은 북마크·공유 링크를 잃지 않으려고 둔다.
 * 마켓이 `/market` 에서 `/` 로 올라갔다 (S15P21A605-206).
 */
const MOVED: Record<string, string> = { "/market": "/" };

/**
 * 보호된 라우트의 낙관적 체크. 세션 쿠키의 **존재**와 **경로가 공개인지**를 본다 (ADR-0005).
 *
 * Proxy 는 프리페치를 포함한 모든 라우트에서 실행되므로 여기서 백엔드를 호출하면 안 되고,
 * 유일한 방어선이 되어서도 안 된다. 실제 검증은 데이터 접근 계층(`src/lib/auth.ts`)이 한다.
 *
 * 만료된 쿠키를 들고 공개 경로에 들어오면 로그인으로 보내지 않는다 — 쿠키 유무만 보므로 여기를
 * 통과하고, 그 뒤 데이터 계층이 미인증으로 판정해 그 화면을 미인증 상태로 그린다.
 */
export function proxy(request: NextRequest) {
  // 옛 주소는 새 자리로 보낸다. **로그인 판정보다 앞이다** — 뒤에 두면 미인증이 옛 주소로
  // 들어올 때 로그인 화면으로 채여 새 자리를 못 본다. `nextUrl` 을 복제하므로 쿼리도 따라간다
  // (`/market?instrumentId=3` 딥링크가 종목을 잃지 않는다).
  const movedTo = MOVED[request.nextUrl.pathname];
  if (movedTo) {
    const url = request.nextUrl.clone();
    url.pathname = movedTo;
    return NextResponse.redirect(url);
  }

  if (request.cookies.has(SESSION_COOKIE)) {
    return NextResponse.next();
  }

  // pnpm dev:login (DEV_AUTO_LOGIN=1) 으로 띄운 경우 테스트 계정 자동 로그인.
  // 공개 경로 판정보다 앞이다 — 이 모드로 띄운 사람은 로그인한 화면을 보려는 것이다.
  if (
    process.env.DEV_AUTO_LOGIN === "1" &&
    process.env.NODE_ENV !== "production"
  ) {
    const devLoginUrl = new URL("/api/dev/login", request.url);
    devLoginUrl.searchParams.set("returnTo", request.nextUrl.pathname);
    return NextResponse.redirect(devLoginUrl);
  }

  if (isPublicRoute(request.nextUrl.pathname)) {
    return NextResponse.next();
  }

  return NextResponse.redirect(new URL("/login", request.nextUrl));
}

export const config = {
  // /api 는 백엔드로 넘어가는 경로이고, /login 은 보호 대상이 아니다.
  // 정적 자산까지 가로채면 CSS·JS 요청이 로그인 화면으로 튄다. public/ 의 파일은 확장자가
  // 있어 `.` 을 가진 경로를 통째로 제외한다 — 우리 라우트에는 `.` 이 없다.
  // (로그인 화면의 브랜드 버튼 이미지가 여기 걸리면 미인증 사용자에게 보이지 않는다.)
  matcher: ["/((?!api|login|_next/static|_next/image|.*\\.).*)"],
};
