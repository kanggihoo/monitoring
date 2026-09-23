import type { Metadata, Viewport } from "next";

import { Analytics } from "@/components/analytics";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { pretendard } from "@/fonts/pretendard";
import { THEME_KEY } from "@/lib/theme";
import { cn } from "@/lib/utils";

import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://flashback.my"),
  title: "Flashback",
  description:
    "과거 시장을 함께 재생하며 투자 판단을 경험하는 멀티플레이어 모의투자 게임입니다.",
};

/**
 * 폭과 배율은 건드리지 않는다 — Next 가 넣는 기본값이 이미 맞고, Next 문서도 "수동 설정은
 * 대개 불필요" 라고 말한다. 확대를 막는 `maximumScale` 도 두지 않는다 (접근성).
 *
 * `viewportFit: "cover"` 만 켠다. 이 값이 있어야 `env(safe-area-inset-*)` 가 0 이 아닌 값을
 * 돌려주고, 그래야 하단 탭바(`mobile-tab-bar.tsx`)와 시트(`ui/sheet.tsx`)가 아이폰 홈
 * 인디케이터에 가리지 않는다 (ADR-0003).
 */
export const viewport: Viewport = {
  viewportFit: "cover",
};

/**
 * 저장된 테마를 첫 페인트 전에 얹는다. 없으면 OS 설정과 저장된 선택이 다를 때 화면이 반대
 * 테마로 한 번 그려졌다가 바뀐다. `useEffect` 는 페인트 뒤에 돌아 이 깜빡임을 못 막는다 —
 * HTML 을 파싱하는 중에 동기로 도는 인라인 스크립트여야 한다.
 * 저장된 선택이 없으면(`system`) 아무것도 하지 않고 미디어 쿼리에 맡긴다.
 */
const APPLY_STORED_THEME = `try{var t=localStorage.getItem("${THEME_KEY}");if(t==="dark"||t==="light")document.documentElement.classList.add(t)}catch(e){}`;

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // 스크립트가 하이드레이션 전에 class 를 바꾸므로 React 에 DOM 쪽을 인정하라고 알린다.
    <html
      lang="ko"
      className={cn("font-sans", pretendard.variable)}
      suppressHydrationWarning
    >
      <head suppressHydrationWarning>
        <script dangerouslySetInnerHTML={{ __html: APPLY_STORED_THEME }} />
      </head>
      <body className="antialiased">
        <TooltipProvider>{children}</TooltipProvider>
        <Toaster />
        {/* 측정 ID 는 **런타임** 환경변수다 (`NEXT_PUBLIC_` 접두사를 일부러 쓰지 않는다).
            그 접두사는 값을 번들에 구워서 dev·prod 이미지가 갈라지고 — 같은 TAG 로 두 환경을
            배포·롤백할 수 없게 된다 — compose 로 갈아 끼우지도 못한다 (.env.example 의 같은
            경고). 여기서 서버가 읽어 prop 으로 내리면 `BACKEND_ORIGIN` 과 같은 경로가 된다.
            전제는 이 레이아웃이 정적 프리렌더되지 않는 것이고, 모든 라우트가 세션 쿠키를 읽어
            동적으로 렌더되므로 성립한다. */}
        <Analytics
          gaId={process.env.ANALYTICS_GA_ID}
          clarityId={process.env.ANALYTICS_CLARITY_ID}
          debug={process.env.NODE_ENV !== "production"}
        />
      </body>
    </html>
  );
}
