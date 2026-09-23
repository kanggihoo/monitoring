"use client";

import { useState } from "react";
import { Eye, Info } from "lucide-react";

import { DataSourceDialog } from "@/components/data-source-dialog";
import { FlashbackLogo } from "@/components/flashback-logo";
import { FlashbackWordmark } from "@/components/flashback-wordmark";
import { HeaderLive } from "@/components/header-live";
import { LoginLink } from "@/components/login-prompt";
import { NotificationsMenu } from "@/components/notifications-menu";
import { ThemeToggle } from "@/components/theme-toggle";
import { TourReplayButton } from "@/components/tour/tour-replay-button";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { UserMenu } from "@/components/user-menu";
import type { User } from "@/lib/auth";
import { isInSandbox } from "@/lib/sandbox";

/**
 * 상단 지표 바. 카드 하나에 워드마크 · 주요 지표(실시간) · 알림 · 테마 · 사용자를 놓는다.
 * 사용자 정보를 인자로 받는 순수 컴포넌트라 로그인 없이도 각 상태를 확인할 수 있다 —
 * 조회는 `site-header.tsx` 가 한다. 화면 이동은 좌측 레일(`app-sidebar.tsx`)이 맡는다.
 */
export function Header({ user }: { user: User | null }) {
  const [legalOpen, setLegalOpen] = useState(false);

  return (
    <>
      {user && isInSandbox(user) && <SandboxBanner />}
      <header
        data-tour="header"
        className="flex h-16 shrink-0 items-center gap-3 rounded-xl border border-border bg-card px-3 md:gap-6 md:px-5"
      >
        {/* 폰에서는 마크만 남긴다. 워드마크는 97px 라, 레일이 빠져 360px 를 온전히 쓰는
            자리에서도 오른쪽 조작부와 합쳐 폭을 넘긴다 — 넘친 만큼은 셸이 잘라 내므로
            스크롤조차 없이 사라진다. 마크는 28px 이고 같은 것을 가리킨다 (ADR-0003). */}
        <FlashbackLogo className="size-7 text-foreground md:hidden" />
        <FlashbackWordmark className="hidden h-4 md:block" />
        <HeaderLive isLoggedIn={user !== null} />
        <div className="flex shrink-0 items-center gap-1">
          <TourReplayButton />
          <NotificationsMenu />
          {/* 테마는 폰에서 `더보기` 시트가 맡는다 (`ThemeToggleRow`). 매일 누르는 것이
              아니라 한 번 정해 두는 값이라, 좁은 줄에 상시로 둘 이유가 없다. */}
          <span className="hidden md:block">
            <ThemeToggle />
          </span>
          {/* 로그인 시에는 UserMenu(프로필 드롭다운) 내부에 포함되므로,
              비로그인 상태일 때만 헤더에 데이터 출처/라이선스 안내 버튼을 노출한다. */}
          {!user && (
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setLegalOpen(true)}
                    aria-label="데이터 제공처 및 라이선스 안내"
                  />
                }
              >
                <Info className="size-4 text-muted-foreground" aria-hidden />
              </TooltipTrigger>
              <TooltipContent side="bottom">
                데이터 제공처 및 라이선스 안내
              </TooltipContent>
            </Tooltip>
          )}
          {user ? (
            <UserMenu user={user} onOpenLegal={() => setLegalOpen(true)} />
          ) : (
            <LoginLink />
          )}
        </div>
      </header>

      <DataSourceDialog open={legalOpen} onOpenChange={setLegalOpen} />
    </>
  );
}

/**
 * 지금 사용자 시점이라는 사실을 계속 보이게 둔다. 나가는 방법을 함께 적는 이유는 복귀 장치가
 * 없기 때문이다 — 보관해 둔 관리자 세션은 그 자체가 권한 상승 경로라 만들지 않았다.
 */
function SandboxBanner() {
  return (
    // **장식이 아니라 안내다.** 회색 면 위 `muted-foreground` 한 줄을 가운데 놓으면 화면
    // 맨 위의 띠 장식으로 읽혀, 정작 "관리자 화면이 왜 안 보이는지" 를 말하는 문장을 아무도
    // 읽지 않는다. 아이콘과 굵은 머리말로 시작해 왼쪽에 세우고 글자를 `foreground-subtle`
    // 로 올린다 (회색 면 위 7.9:1).
    //
    // **색은 쓰지 않는다.** 이 띠는 세션 내내 모든 화면에 떠 있다 — 경고색으로 칠하면
    // 늘 무언가 잘못된 것처럼 보이고, 정작 급한 안내(`LockedNotice`)와 무게가 같아진다.
    <p
      role="status"
      className="flex items-center gap-2 rounded-xl border border-border bg-muted px-4 py-2 text-sm text-foreground-subtle"
    >
      <Eye className="size-4 shrink-0 text-foreground" aria-hidden />
      <span className="font-semibold text-foreground">샌드박스</span>
      사용자 시점으로 보고 있습니다. 관리자로 돌아가려면 로그아웃한 뒤 관리자
      계정으로 다시 로그인하세요.
    </p>
  );
}
