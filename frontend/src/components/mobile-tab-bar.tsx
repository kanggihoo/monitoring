"use client";

import { type MouseEvent, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Ellipsis, Info, Lock } from "lucide-react";

import { DataSourceDialog } from "@/components/data-source-dialog";
import { LoginRequiredDialog } from "@/components/login-required-dialog";
import { ThemeToggleRow } from "@/components/theme-toggle";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
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
 * 폰의 주 내비. 넓은 화면의 좌측 레일(`app-sidebar.tsx`)과 같은 목록을 읽되
 * (`lib/nav.ts`) 다섯 칸으로 줄여 엄지가 닿는 화면 아래에 둔다 (ADR-0003).
 *
 * **왜 드로어가 아니라 탭바인가.** 폰에서도 주문을 넣을 수 있으므로 마켓 ↔ 내 계좌를
 * 자주 오간다. 드로어는 그 왕복마다 두 번 누르고 애니메이션을 기다리게 한다.
 *
 * 자리를 차지하지 않고 화면에 고정된다(`fixed`) — 셸(`(app)/layout.tsx`)이 본문 아래에
 * 같은 높이의 여백을 두어 마지막 줄이 탭바에 가리지 않게 한다.
 */
export function MobileTabBar({
  isAdmin,
  isLoggedIn = false,
}: {
  isAdmin: boolean;
  isLoggedIn?: boolean;
}) {
  const pathname = usePathname();
  const [locked, setLocked] = useState<NavItem | null>(null);
  const [moreOpen, setMoreOpen] = useState(false);
  const [legalOpen, setLegalOpen] = useState(false);

  const primary = NAV.filter((item) => item.mobilePrimary);
  const secondary = [
    ...NAV.filter((item) => !item.mobilePrimary),
    ...(isAdmin ? [ADMIN] : []),
    INQUIRY,
  ];

  const isLocked = (item: NavItem) => Boolean(item.personal) && !isLoggedIn;

  /**
   * 미인증이 개인 진입점을 눌렀을 때. 레일과 같은 판단이다 — 화면을 넘기지 않고 안내를
   * 얹는다. 주소는 바꾸지 않으므로 새 탭·주소 직접 입력은 그대로 성립한다.
   */
  const handleLockedClick = (item: NavItem) => (event: MouseEvent) => {
    if (!isLocked(item) || event.metaKey || event.ctrlKey || event.shiftKey) {
      return;
    }
    event.preventDefault();
    setMoreOpen(false);
    setLocked(item);
  };

  const labelFor = (item: NavItem) =>
    isLocked(item) ? `${item.label} · ${LOGIN_REQUIRED}` : undefined;

  // 더보기 안의 화면을 보고 있으면 더보기 칸이 현재 위치를 대신 말한다 — 그러지 않으면
  // 뉴스를 보는 동안 탭바의 어느 칸도 켜지지 않아 자기가 어디 있는지 알 수 없다.
  const moreCurrent = secondary.some((item) => isCurrent(item, pathname));

  return (
    <>
      <nav
        aria-label="주 메뉴 (모바일)"
        className="fixed inset-x-0 bottom-0 z-40 flex border-t border-border bg-card pb-[env(safe-area-inset-bottom)] md:hidden"
      >
        {primary.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            onClick={handleLockedClick(item)}
            aria-label={labelFor(item)}
            aria-current={isCurrent(item, pathname) ? "page" : undefined}
            className={cn(TAB, isCurrent(item, pathname) ? TAB_ON : TAB_OFF)}
          >
            <TabIcon item={item} locked={isLocked(item)} />
            <span>{item.label}</span>
          </Link>
        ))}

        <Sheet open={moreOpen} onOpenChange={setMoreOpen}>
          <SheetTrigger
            className={cn(TAB, moreCurrent ? TAB_ON : TAB_OFF)}
            aria-label="더보기"
          >
            <Ellipsis className="size-5" aria-hidden />
            <span>더보기</span>
          </SheetTrigger>
          <SheetContent>
            <SheetHeader>
              <SheetTitle>더보기</SheetTitle>
            </SheetHeader>
            <ul className="flex flex-col">
              {secondary.map((item) => (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    onClick={handleLockedClick(item)}
                    aria-label={labelFor(item)}
                    aria-current={
                      isCurrent(item, pathname) ? "page" : undefined
                    }
                    className={cn(
                      FOCUS_RING,
                      "flex h-12 items-center gap-3 rounded-lg px-2 text-[15px]",
                      isCurrent(item, pathname)
                        ? "font-semibold text-foreground"
                        : "text-foreground/80"
                    )}
                  >
                    <TabIcon item={item} locked={isLocked(item)} />
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
            {/* 이동이 아니므로 목록에서 떼어 놓는다. 헤더에서 뺀 테마 조작 및 데이터 출처가
                여기로 온다 — 미로그인도 열 수 있는 자리여야 해서 사용자 메뉴가 아니라 시트다. */}
            <div className="flex flex-col gap-1 border-t border-border pt-2">
              <ThemeToggleRow />
              <button
                type="button"
                onClick={() => {
                  setMoreOpen(false);
                  setLegalOpen(true);
                }}
                className={cn(
                  FOCUS_RING,
                  "flex h-12 w-full items-center gap-3 rounded-lg px-2 text-[15px] text-foreground/80 hover:text-foreground active:bg-muted"
                )}
              >
                <Info
                  className="size-4 shrink-0 text-muted-foreground"
                  aria-hidden
                />
                <span>데이터 제공처 및 라이선스</span>
              </button>
            </div>
          </SheetContent>
        </Sheet>
      </nav>

      <DataSourceDialog open={legalOpen} onOpenChange={setLegalOpen} />

      {/* 어느 항목을 눌렀는지가 문장과 복귀 경로를 정하므로 항목당 하나가 아니라 탭바에
          하나만 둔다 (레일과 같다). */}
      {locked && (
        <LoginRequiredDialog
          target={locked.href}
          label={locked.label}
          open
          onOpenChange={(open) => !open && setLocked(null)}
        />
      )}
    </>
  );
}

/**
 * 칸 하나. **높이를 56px 로 잡는다** — 터치 최소치(44px)를 넘기고, 다섯 칸으로 나눈 360px
 * 화면에서 칸 하나가 72px 라 가로도 넉넉하다.
 */
const TAB = cn(
  FOCUS_RING,
  "flex h-14 flex-1 flex-col items-center justify-center gap-1 rounded-none text-2xs"
);
const TAB_ON = "font-medium text-foreground";
const TAB_OFF = "text-muted-foreground";

/**
 * 잠긴 항목에 자물쇠를 겹친다. **누르기 전에** 로그인이 필요하다는 사실이 보여야 한다 —
 * 그러지 않으면 안내가 뜨는 순간이 곧 처음 알게 되는 순간이 된다 (레일과 같은 판단).
 */
function TabIcon({ item, locked }: { item: NavItem; locked: boolean }) {
  const Icon = item.icon;
  if (!locked) {
    return <Icon className="size-5" aria-hidden />;
  }
  return (
    <span className="relative flex size-5 items-center justify-center">
      <Icon className="size-5" aria-hidden />
      <Lock
        aria-hidden
        className="absolute -right-1.5 -bottom-1 size-2.5 rounded-full bg-card stroke-3"
      />
    </span>
  );
}
