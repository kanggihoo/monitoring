"use client";

import { Fragment, type MouseEvent, type ReactElement, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Lock } from "lucide-react";

import { FlashbackLogo } from "@/components/flashback-logo";
import { LoginRequiredDialog } from "@/components/login-required-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  ACCOUNT_TABS,
  type AccountTab,
  accountTabHref,
} from "@/lib/account-tabs";
import {
  ADMIN,
  INQUIRY,
  isCurrent,
  LOGIN_REQUIRED,
  NAV,
  type NavItem,
} from "@/lib/nav";
import { cn, FOCUS_RING } from "@/lib/utils";

/**
 * 좌측 내비 레일. **제 배경도 테두리도 없다** — 셸(`(app)/layout.tsx`)의 왼쪽 열이고
 * 본문과 같은 면 위에 얹힌다 (시안). 선택 항목만 흰 타일로 떠오른다.
 * 선택 표시는 무채색이다 (파랑은 하락과 혼동된다, DESIGN.md).
 */
export function AppSidebar({
  isAdmin,
  isLoggedIn = false,
}: {
  isAdmin: boolean;
  isLoggedIn?: boolean;
}) {
  const pathname = usePathname();
  const items = isAdmin ? [...NAV, ADMIN] : NAV;
  const [locked, setLocked] = useState<NavItem | null>(null);

  const isLocked = (item: NavItem) => Boolean(item.personal) && !isLoggedIn;

  /**
   * 미인증이 개인 진입점을 눌렀을 때. **화면을 넘기지 않고 안내를 얹는다.**
   *
   * 눌러도 아무 일이 없으면 고장으로 보이고, `/login` 으로 넘겨 버리면 보던 시세 화면을
   * 통째로 잃는다. 둘 다 피하려면 지금 화면 위에 안내가 떠야 한다.
   *
   * **주소는 바꾸지 않는다.** 링크는 여전히 제 화면을 가리키므로 새 탭·주소 직접 입력은
   * 그대로 성립하고, 그쪽은 프록시가 `/login` 으로 안내한다. 그래서 보조 클릭은 가로채지
   * 않는다 — 새 탭으로 열려던 사람에게 이 탭의 모달을 띄우는 것은 엉뚱한 응답이다.
   */
  const handleLockedClick = (item: NavItem) => (event: MouseEvent) => {
    if (!isLocked(item) || event.metaKey || event.ctrlKey || event.shiftKey) {
      return;
    }
    event.preventDefault();
    setLocked(item);
  };

  const labelFor = (item: NavItem) =>
    isLocked(item) ? `${item.label} · ${LOGIN_REQUIRED}` : undefined;

  return (
    <aside className="sticky top-0 hidden h-full flex-col items-center gap-1.5 self-start py-3.5 text-sidebar-foreground md:flex">
      <Link
        href="/"
        aria-label="Flashback 홈"
        // 로고 칸의 높이를 헤더 카드(64px)에 맞춘다 — 그래야 첫 내비 타일의 윗선이 옆
        // 본문의 윗선과 같아진다. 레일과 본문은 서로 다른 열이라 공유하는 행이 없고,
        // 시안도 같은 이유로 로고 칸 높이를 못 박아 두었다.
        className={cn(
          FOCUS_RING,
          "mb-1.5 flex h-16 w-full items-center justify-center text-foreground"
        )}
      >
        <FlashbackLogo className="size-9" />
      </Link>

      <nav className="flex flex-col gap-1.5" aria-label="주 메뉴">
        {items.map((item) => {
          const { href, label } = item;
          const current = isCurrent(item, pathname);
          const tile = (
            <Link
              href={href}
              onClick={handleLockedClick(item)}
              aria-label={labelFor(item)}
              aria-current={current ? "page" : undefined}
              className={cn(
                FOCUS_RING,
                // 선택 항목만 카드 면으로 떠오른다. 나머지는 셸에 잠겨 있다.
                "flex h-12.5 w-14.5 flex-col items-center justify-center gap-1 rounded-xl text-2xs",
                current
                  ? "border border-sidebar-border bg-sidebar-accent font-medium text-sidebar-accent-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <NavIcon item={item} locked={isLocked(item)} />
              <span>{label}</span>
            </Link>
          );

          // 잠긴 동안에는 펼치지 않는다 — 들어갈 수 없는 화면의 목차를 여는 것이 되고,
          // 타일을 누르면 뜨는 로그인 안내와도 엇갈린다.
          return item.flyout && !isLocked(item) ? (
            <AccountFlyout key={href}>{tile}</AccountFlyout>
          ) : (
            // 레일은 `flex flex-col` 이라 타일이 그대로 열의 항목이어야 한다.
            // `Fragment` 는 DOM 을 하나도 더하지 않는다.
            <Fragment key={href}>{tile}</Fragment>
          );
        })}
      </nav>

      {/* 화면을 옮기는 항목이 아니라 언제든 부를 수 있는 통로라 주 메뉴 밖, 레일 바닥에
          둔다. 지금 보는 화면과 무관하므로 선택 표시도 하지 않는다. */}
      <Link
        href={INQUIRY.href}
        onClick={handleLockedClick(INQUIRY)}
        aria-label={labelFor(INQUIRY)}
        className={cn(
          FOCUS_RING,
          "mt-auto flex h-12.5 w-14.5 flex-col items-center justify-center gap-1 rounded-xl text-2xs text-muted-foreground hover:text-foreground"
        )}
      >
        <NavIcon item={INQUIRY} locked={isLocked(INQUIRY)} />
        <span>{INQUIRY.label}</span>
      </Link>

      {/* 어느 항목을 눌렀는지가 문장과 복귀 경로를 정하므로 항목당 하나가 아니라 레일에
          하나만 둔다. 닫히면 `locked` 를 비워 다음에 다른 항목을 눌러도 문장이 바뀐다. */}
      {locked && (
        <LoginRequiredDialog
          target={locked.href}
          label={locked.label}
          open
          onOpenChange={(open) => !open && setLocked(null)}
        />
      )}
    </aside>
  );
}

/**
 * `내 계좌` 타일에 붙는 서브탭 목차. **오른쪽으로 펼쳐진다** — 레일이 화면 왼쪽 끝이라
 * 위로 띄우면 위쪽 항목을 가린다.
 *
 * 목차는 탭 줄과 같은 셋이다 (`lib/account-tabs.ts`). 공모주는 레일의 제 항목으로 나갔고
 * 계정 관리는 헤더 메뉴가 맡는다 — 한 화면에 진입점이 둘이면 어느 쪽이 정답인지 흐려진다.
 *
 * **타일은 그대로 링크다.** 눌러 `/mypage` 로 가는 기존 동작이 유지되고, 이 목차는 그
 * 위에 얹히는 지름길이다 (`openOnHover`). 서브탭 줄 자체는 손대지 않는다 — 한눈에 안
 * 들어온다는 피드백에 대해 **두 번째 진입 경로**를 주는 것이 이 화면의 답이다.
 *
 * 키보드로도 열린다. Base UI 메뉴가 아래 방향키로 열고 방향키로 항목을 옮기며 `Esc` 로
 * 닫는다 — 호버 전용이면 키보드 사용자에게는 이 지름길이 없는 것과 같다.
 */
function AccountFlyout({ children }: { children: ReactElement }) {
  return (
    <DropdownMenu>
      {/* `nativeButton={false}` — 실제로 그려지는 것은 `<a>` 다. 그 값이 `role="button"` 까지
          붙이므로 되돌린다: 눌렀을 때 하는 일이 이동이라 낭독기에도 링크여야 한다.
          목차가 딸렸다는 사실은 Base UI 가 붙이는 `aria-haspopup` 이 말한다. */}
      <DropdownMenuTrigger
        openOnHover
        nativeButton={false}
        role="link"
        render={children}
      />
      {/* 타일 폭(58px)에 맞춘 기본값 대신 글자 폭을 쓴다. `align="start"` 로 첫 항목의
          윗선을 타일 윗선에 맞춰, 펼쳐지는 방향이 눈에 바로 읽히게 한다.
          글자는 서브탭 줄과 같은 크기다 — 같은 목록을 가리키므로 레일에서 작아질 이유가 없다.

          **테두리가 `border-strong` 이다.** 기본값인 `border` 는 카드(흰 면) 위 기준이라
          흰 팝업이 흰 카드 위에 뜨는 이 자리에서는 대비 1.02:1 로 사라진다 (DESIGN.md).
          그림자도 한 단 올린다 — 과한 그림자는 피하되 팝오버·모달에는 허용된다. */}
      <DropdownMenuContent
        side="right"
        align="start"
        sideOffset={6}
        aria-label="내 계좌 서브탭"
        className="w-auto min-w-48 p-1.5 shadow-lg ring-border-strong"
      >
        {ACCOUNT_TABS.map((tab) => (
          <AccountFlyoutItem
            key={tab.value}
            tab={tab.value}
            label={tab.label}
          />
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function AccountFlyoutItem({ tab, label }: { tab: AccountTab; label: string }) {
  return (
    <DropdownMenuItem
      render={<Link href={accountTabHref(tab)} />}
      className="px-3 py-2 text-[15px]"
    >
      {label}
    </DropdownMenuItem>
  );
}

/**
 * 잠긴 항목의 아이콘. 자물쇠를 겹쳐 **누르기 전에** 로그인이 필요하다는 사실이 보이게 한다 —
 * 그것이 보이지 않으면 안내 모달이 뜨는 순간이 곧 처음 알게 되는 순간이 된다.
 *
 * 흐리게 처리하지 않는다. 흐린 항목은 "눌러도 소용없다"로 읽히는데 실제로는 눌러야 안내가 뜬다.
 */
function NavIcon({ item, locked }: { item: NavItem; locked: boolean }) {
  const Icon = item.icon;
  if (!locked) {
    return <Icon className="size-4" aria-hidden />;
  }
  return (
    <span className="relative flex size-4 items-center justify-center">
      <Icon className="size-4" aria-hidden />
      <Lock
        aria-hidden
        // 레일 타일이 작아 배지가 아이콘을 덮지 않도록 오른쪽 아래로 반 칸 뺀다.
        className="absolute -right-1.5 -bottom-1 size-2.5 rounded-full bg-sidebar stroke-3"
      />
    </span>
  );
}
