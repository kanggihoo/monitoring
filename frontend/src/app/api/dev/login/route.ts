import { NextRequest, NextResponse } from "next/server";

/**
 * 개발/테스트 환경 전용 로그인 엔드포인트.
 *
 * 브라우저에서 이 경로(예: /api/dev/login)를 호출하면, 백엔드의
 * /api/v1/test-support/login 을 호출하여 발급된 SESSION 쿠키를
 * 브라우저에 심어준 뒤 홈(또는 지정된 경로)으로 리다이렉트합니다.
 *
 * 프로덕션 환경에서는 404를 반환해 안전하게 차단됩니다.
 *
 * ⚠ **로컬 `next dev` 에서만 동작합니다.** 배포된 dev 서버(:8443)에서도 안 됩니다.
 * 막는 것이 둘이고, 주된 쪽은 NODE_ENV 가드가 아닙니다.
 *
 *   ① nginx 가 `/api` 를 전부 백엔드로 보냅니다 (dev: 8091, prod: 8090).
 *      그래서 이 라우트에 요청이 도달하지 못하고 Spring 이 401 을 냅니다.
 *      NODE_ENV 를 development 로 바꿔도 여전히 안 됩니다.
 *   ② 그 위에 아래 NODE_ENV 가드가 404 를 냅니다. frontend/Dockerfile 이
 *      런타임에 production 을 박으므로 dev 컨테이너에서도 걸립니다.
 *
 * 로컬에서 되는 이유는 `next.config.ts` 의 `rewrites()` 가 배열을 반환해
 * `afterFiles` 가 되고, Next 는 파일시스템 라우트를 그보다 우선하기 때문입니다.
 *
 * 배포된 dev 서버에서도 쓰려면 경로가 `/api` **밖**으로 나와야 합니다
 * (예: `/dev/login`). 지금 `proxy.ts` 의 자동 로그인 리다이렉트도 이 경로를
 * 가리키므로, dev 서버에 DEV_AUTO_LOGIN=1 을 주면 브라우저가 Spring 의 401 로
 * 튕겨 로그인 화면조차 못 봅니다.
 */
export async function GET(request: NextRequest) {
  if (process.env.NODE_ENV === "production") {
    return new NextResponse("Not Found", { status: 404 });
  }

  const apiKey = process.env.TEST_SUPPORT_API_KEY;
  if (!apiKey) {
    return new NextResponse(
      "TEST_SUPPORT_API_KEY 가 .env.local 에 설정되어 있지 않습니다.",
      { status: 500 }
    );
  }

  const searchParams = request.nextUrl.searchParams;
  const username = searchParams.get("user") ?? "k6-0001";
  const rawReturnTo = searchParams.get("returnTo");
  const returnTo = !rawReturnTo || rawReturnTo === "/login" ? "/" : rawReturnTo;

  const backendOrigin = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

  try {
    const backendRes = await fetch(
      `${backendOrigin}/api/v1/test-support/login`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Test-Support-Key": apiKey,
        },
        body: JSON.stringify({ name: username }),
      }
    );

    if (!backendRes.ok) {
      const errorText = await backendRes.text().catch(() => "");
      return new NextResponse(
        `백엔드 테스트 로그인 실패 (${backendRes.status}): ${errorText}`,
        { status: backendRes.status }
      );
    }

    const redirectUrl = new URL(returnTo, request.url);
    const response = NextResponse.redirect(redirectUrl);

    // 백엔드가 준 Set-Cookie 를 **그대로** 넘긴다.
    //
    // 파싱해서 다시 만들면 옮겨 적지 않은 속성이 전부 조용히 사라진다. 이전
    // 코드가 그래서 두 가지를 망쳤다.
    //
    //   ① httpOnly 를 전부 true 로 덮었다. Spring 은 위조 방지 토큰을
    //      `XSRF-TOKEN=...; Path=/` 로 **HttpOnly 없이** 내려주는데(실측),
    //      스크립트가 그 값을 읽어 헤더로 돌려보내야 한다. httpOnly 가 붙으면
    //      상태 변경 요청이 전부 403 이 된다. e2e 스펙도 csrf.httpOnly === false
    //      를 계약으로 못박아 뒀다.
    //   ② Max-Age·Expires·Domain 을 버리고 Path 와 SameSite 를 고정값으로
    //      덮었다. 지금 백엔드 값과 우연히 같아서 드러나지 않았을 뿐이다.
    //
    // 손대는 것은 Secure 하나다. 로컬 개발은 http 라서 Secure 쿠키를 브라우저가
    // 저장하지 않는다.
    //
    // ⚠ SameSite=None 인 쿠키에는 이 제거가 위험하다 — None 은 Secure 를
    //   요구하므로 브라우저가 쿠키째로 거부한다. 현재 백엔드는 SESSION 에 Lax,
    //   XSRF-TOKEN 에 무지정이라 해당되지 않는다. 백엔드가 None 을 쓰기
    //   시작하면 여기도 함께 고쳐야 한다.
    const rawCookies =
      typeof backendRes.headers.getSetCookie === "function"
        ? backendRes.headers.getSetCookie()
        : [backendRes.headers.get("set-cookie")].filter(Boolean);

    for (const cookieStr of rawCookies) {
      if (!cookieStr) continue;
      response.headers.append(
        "set-cookie",
        cookieStr
          .split(";")
          .filter((attr) => attr.trim().toLowerCase() !== "secure")
          .join(";")
      );
    }

    return response;
  } catch (error) {
    return new NextResponse(
      `백엔드 연결 실패 (${backendOrigin}): ${error instanceof Error ? error.message : String(error)}`,
      { status: 502 }
    );
  }
}
