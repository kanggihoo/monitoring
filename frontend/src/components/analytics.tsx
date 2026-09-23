"use client";

import { Suspense, useEffect } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import Script from "next/script";

type AnalyticsProps = {
  /** GA4 측정 ID (`G-...`). 없으면 GA 를 아예 렌더하지 않는다. */
  gaId?: string;
  /** Clarity 프로젝트 ID. 없으면 Clarity 를 아예 렌더하지 않는다. */
  clarityId?: string;
  /** GA4 DebugView 로 보낼지. 운영에서 켜면 실제 트래픽이 디버그 스트림으로 샌다. */
  debug?: boolean;
};

/**
 * GA4 와 Microsoft Clarity 태그를 얹는다. 루트 레이아웃에서 한 번만 부른다 — Next 가
 * 라우트를 옮겨도 스크립트를 다시 싣지 않는다.
 *
 * **ID 가 없으면 아무것도 렌더하지 않는 것이 유일한 차단 수단이다.** 별도 스위치를 두지
 * 않는다 — `.env.local` 을 비워 두는 것이 곧 꺼짐이고, 그래서 로컬 개발과 E2E 는 손대지
 * 않아도 계측이 붙지 않는다.
 *
 * `strategy` 는 기본값(`afterInteractive`)에 맡긴다. `beforeInteractive` 로 올리면 분석
 * 스크립트가 첫 페인트 앞을 막아 LCP 가 밀린다 — 분석은 화면보다 뒤에 와야 한다.
 */
export function Analytics({ gaId, clarityId, debug = false }: AnalyticsProps) {
  return (
    <>
      {gaId && (
        <>
          <Script src={`https://www.googletagmanager.com/gtag/js?id=${gaId}`} />
          {/* 인라인 스크립트에는 id 가 필수다 (Next 가 중복 실행을 막는 기준). */}
          <Script id="ga4-init">
            {`window.dataLayer=window.dataLayer||[];
function gtag(){dataLayer.push(arguments)}
window.gtag=gtag;
gtag('js',new Date());
gtag('config','${gaId}',{send_page_view:false${debug ? ",debug_mode:true" : ""}});`}
          </Script>
          <Suspense fallback={null}>
            <PageView gaId={gaId} />
          </Suspense>
        </>
      )}

      {clarityId && (
        <Script id="clarity-init">
          {`(function(c,l,a,r,i,t,y){c[a]=c[a]||function(){(c[a].q=c[a].q||[]).push(arguments)};t=l.createElement(r);t.async=1;t.src="https://www.clarity.ms/tag/"+i;y=l.getElementsByTagName(r)[0];y.parentNode.insertBefore(t,y)})(window,document,"clarity","script","${clarityId}");`}
        </Script>
      )}
    </>
  );
}

/**
 * 라우트가 바뀔 때마다 `page_view` 를 직접 보낸다.
 *
 * GA4 의 향상된 측정도 히스토리 변경을 잡지만, App Router 는 URL 을 먼저 바꾸고 화면을 뒤에
 * 그려서 **바뀌기 전 `document.title` 이 실린다** — 보고서에서 페이지 제목이 한 칸씩 밀린다.
 * 그래서 `send_page_view:false` 로 자동 전송을 끄고 렌더가 끝난 이 시점에 보낸다.
 *
 * `useSearchParams` 를 함께 보는 이유는 쿼리만 바뀌는 이동(계좌 탭 등)이 있어서다 —
 * `usePathname` 만 보면 그 이동이 조회로 잡히지 않는다. 대신 이 훅은 정적 프리렌더 시
 * 상위 트리를 클라이언트 렌더로 떨어뜨리므로 호출부에서 `<Suspense>` 로 감싼다.
 */
function PageView({ gaId }: { gaId: string }) {
  const pathname = usePathname();
  const searchParams = useSearchParams();

  useEffect(() => {
    const query = searchParams.toString();
    window.gtag?.("event", "page_view", {
      page_path: query ? `${pathname}?${query}` : pathname,
      page_location: window.location.href,
      page_title: document.title,
      send_to: gaId,
    });
  }, [gaId, pathname, searchParams]);

  return null;
}
