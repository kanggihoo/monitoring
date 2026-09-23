"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { cn, FOCUS_RING } from "@/lib/utils";

const LINKS = [
  { href: "/admin/users", label: "사용자" },
  { href: "/admin/inquiries", label: "문의" },
  { href: "/admin/audit-logs", label: "감사 로그" },
  { href: "/admin/notices", label: "공지" },
  { href: "/admin/replay", label: "재생 제어" },
];

/**
 * 관리자 화면 사이를 오가는 링크. 현재 화면을 표시해야 하므로 경로를 읽는 클라이언트 컴포넌트다.
 *
 * 선택 표시는 무채색 `accent` 면이다 — 파랑은 하락(`price-down`)과 혼동된다 (DESIGN.md).
 */
export function AdminNav() {
  const pathname = usePathname();

  return (
    <nav className="flex flex-col gap-4 border-b border-border-strong pb-4 sm:flex-row sm:items-end sm:justify-between">
      <p className="text-2xl font-semibold tracking-[-0.04em]">관리자</p>
      <div className="flex gap-1 overflow-x-auto">
        {LINKS.map(({ href, label }) => {
          const current = pathname.startsWith(href);

          return (
            <Link
              key={href}
              href={href}
              aria-current={current ? "page" : undefined}
              className={cn(
                FOCUS_RING,
                "rounded-sm px-3 py-2 text-base whitespace-nowrap",
                current
                  ? "bg-accent font-medium text-accent-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              {label}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
