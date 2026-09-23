import { AbsenceGate } from "@/components/absence-gate";
import { AnalyticsIdentify } from "@/components/analytics-identify";
import { AppSidebar } from "@/components/app-sidebar";
import { BriefingGate } from "@/components/briefing-gate";
import { LoginReturnProvider } from "@/components/login-return-provider";
import { MarketLiveProvider } from "@/components/market-live-provider";
import { MobileTabBar } from "@/components/mobile-tab-bar";
import { RememberLoginProvider } from "@/components/remember-login-provider";
import { SiteHeader } from "@/components/site-header";
import { getCurrentUser } from "@/lib/auth";

/**
 * 셸 — 회색 프레임 위에 레일·헤더·본문이 12px 격자로 떠 있는 카드다 (Flashback 시안).
 * 로그인 화면에는 이 셸이 없으므로 `/login` 은 이 그룹 밖에 둔다.
 *
 * **폰에서는 같은 셸이 다른 모양을 한다** (ADR-0003): 레일이 하단 탭바로 바뀌고, 카드가
 * 화면 끝까지 펴지며, 높이를 묶지 않는다. 분기는 전부 CSS 다 — 서버는 뷰포트를 모르므로
 * JS 로 고르면 첫 렌더가 반드시 틀린다.
 *
 * 실시간 연결(`MarketLiveProvider`)을 여기 두어 어느 화면에 있어도 상단 바의 지표가 흐른다.
 * 현재 사용자 조회는 렌더 단위 캐시가 걸려 있어 상단 바·페이지를 합쳐도 백엔드 호출은 한 번이다.
 *
 * **로그인 상태는 그 조회에서 계산한 불리언 하나로 내린다**
 * (`docs/specs/public-market-access.md` §로그인 상태는 prop 하나로 내린다).
 * Context 도, 스토어의 user 필드도 두지 않는다 — 필요한 것은 "로그인했나" 하나지 사용자 객체가
 * 아니고, 모듈 싱글턴에 사용자를 넣으면 서버에서 요청 간 공유되어 섞일 위험이 하나 더 생긴다.
 */
export default async function AppLayout({ children }: LayoutProps<"/">) {
  const user = await getCurrentUser();
  const provider = user?.providers[0];
  const isLoggedIn = user !== null;

  const shell = (
    // **폰에서는 높이를 묶지 않는다.** 넓은 화면의 `h-dvh` 는 트레이딩 터미널의 모양이지만
    // (헤더는 제자리, 본문만 흐른다), 폰에서 같은 것을 하면 주소창이 접히고 펴질 때마다
    // `dvh` 가 요동쳐 화면이 위아래로 튄다. 폰은 문서가 통째로 흐르고 탭바만 떠 있다.
    <div className="bg-background md:h-dvh md:overflow-hidden md:p-3">
      {/* 셸 — 레일과 본문이 한 덩어리다. 프레임(회색 한 단계 밝은 면) 위에 이 덩어리가
          뜬다 (시안). 레일을 따로 띄우면 둘 사이에 프레임이 새어 들어와 축이 갈린다.
          폭을 1440px 에 묶고 가운데 놓는다 — 넓은 화면에서 끝까지 늘리면 헤더의 지표가
          양 끝으로 벌어져 한눈에 안 들어온다. **셸을 가운데 두는 것이지 본문을 가운데
          두는 것이 아니다** (`PAGE_CONTAINER` 는 셸 안에서 여전히 왼쪽부터 채운다).

          폰에서는 프레임도 둥근 모서리도 없다. 360px 에서 여백과 라운드는 화면을 꾸미는
          대신 읽을 폭을 깎는다 — 카드가 곧 화면이다. */}
      <div className="mx-auto flex w-full max-w-[90rem] flex-col bg-sidebar md:grid md:h-full md:grid-cols-[4.875rem_minmax(0,1fr)] md:overflow-hidden md:rounded-3xl">
        <AppSidebar isAdmin={user?.role === "ADMIN"} isLoggedIn={isLoggedIn} />
        {/* 폰에서는 좌우 여백을 같게 준다. 넓은 화면이 `pr-3.5` 만 주는 비대칭은 왼쪽
            여백을 레일이 만들어 주기 때문인데, 레일이 없는 폰에서는 본문이 화면 왼쪽
            끝에 붙는다. 아래 여백은 탭바 높이(3.5rem)에 홈 인디케이터를 더한 만큼이다 —
            그러지 않으면 마지막 줄이 탭바 뒤에 깔려 읽을 수도 누를 수도 없다. */}
        <div className="flex min-h-0 min-w-0 flex-col gap-3 px-3 pt-3 pb-[calc(3.5rem+env(safe-area-inset-bottom)+0.75rem)] md:px-0 md:pt-3.5 md:pr-3.5 md:pb-3.5">
          <SiteHeader />
          {provider && <RememberLoginProvider provider={provider} />}
          {user && <AnalyticsIdentify userId={user.id} />}
          {user && <LoginReturnProvider />}
          {/* 스크롤은 **넓은 화면에서만** 헤더 아래 이 상자가 가진다 — 페이지도 열 전체도
              아니다. 그래서 (1) 화면은 셸이 잡아먹는 높이를 알 필요가 없고(`flex-1` 이면
              남은 만큼 채운다), 셸 치수를 바꿔도 화면 쪽 상수가 어긋날 일이 없다.
              (2) 긴 화면을 내려도 헤더의 지표는 제자리에 남는다.
              폰에서는 문서가 흐르므로 여기서 또 가로채지 않는다 — 상자 안에 스크롤이
              중첩되면 관성이 끊기고 주소창도 접히지 않는다. */}
          {/* **md~lg 사이에서는 화면이 제 키대로 서고 이 상자가 스크롤을 준다.**
              화면들의 데스크톱 격자는 `lg` 부터 열리므로 그 아래에서는 카드가 한 열로
              쌓이는데, 그 상태로 화면이 `flex-1` 이면 뷰포트 높이에 눌려 잘린다 —
              스크롤은 이 상자가 가지고 있으나 자식이 딱 제 높이로 줄어드니 넘칠 것이
              없어 스크롤도 생기지 않는다 (리더보드·뉴스가 사라져 보이던 원인).
              `flex-none` 이 기준 크기를 내용으로 되돌려 자식이 넘치게 하고, 그제서야
              이 상자가 스크롤한다. `lg` 부터는 원래대로 화면이 높이를 채운다. */}
          <div className="flex min-h-0 flex-1 flex-col md:overflow-y-auto md:max-lg:*:flex-none">
            {children}
          </div>
        </div>
      </div>
      <MobileTabBar isAdmin={user?.role === "ADMIN"} isLoggedIn={isLoggedIn} />
    </div>
  );

  return (
    <MarketLiveProvider isLoggedIn={isLoggedIn}>
      {/* **개인 게이트는 로그인한 사람에게만 건다** (ADR-0005). 부재 요약
          (`/accounts/me/absence-summary`)과 개장 브리핑(`/seasons/current/briefing`)은
          둘 다 `사용자` 권한이라, 미인증에 걸면 공개 화면을 열 때마다 401 이 한 번씩 나간다.
          두 게이트가 실패를 삼키므로 화면은 멀쩡하지만 **서버 거부는 방어선이지 정상 경로가
          아니다** — 미인증은 애초에 부르지 않는다 (개인 큐를 구독하지 않는 것과 같은 판단). */}
      {isLoggedIn ? (
        <AbsenceGate>
          <BriefingGate>{shell}</BriefingGate>
        </AbsenceGate>
      ) : (
        shell
      )}
    </MarketLiveProvider>
  );
}
