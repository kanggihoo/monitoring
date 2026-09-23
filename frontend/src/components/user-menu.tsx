"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ChevronDown } from "lucide-react";
import { toast } from "sonner";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { apiFetch } from "@/lib/api";
import type { User } from "@/lib/auth";
import { isPublicRoute } from "@/lib/public-routes";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

/** 백엔드가 소유한 로그아웃 경로. 세션은 서버에서 끊는다. */
const LOGOUT_PATH = "/api/auth/logout";

/** 보호 화면에서 로그아웃했을 때 내려놓을 공개 화면. */
const PUBLIC_LANDING = "/";

/** 닉네임 · 마이페이지 · 로그아웃을 한 메뉴에 둔다. */
export function UserMenu({
  user,
  onOpenLegal,
}: {
  user: User;
  onOpenLegal?: () => void;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const [pending, setPending] = useState(false);

  async function handleLogout() {
    setPending(true);
    try {
      const response = await apiFetch(LOGOUT_PATH, { method: "POST" });
      if (!response.ok) {
        console.error("로그아웃에 실패했습니다", response.status);
        toast.error("로그아웃하지 못했습니다. 잠시 뒤 다시 시도하세요.");
        return;
      }
      // 이전 사용자의 잔고·순위·체결이 화면에 남지 않도록 개인 데이터를 즉시 지운다.
      // 서버 재조회보다 먼저다 — 재조회가 오는 사이에도 보이지 않아야 한다.
      tickStore.getState().clearPersonal();
      sessionStore.getState().clearPersonal();
      // **`/login` 으로 튕기지 않는다** (ADR-0005). 공개 화면이면 그 자리에 머무르고
      // 미인증으로 전환된다.
      //
      // 보호 화면이었다면 그냥 두어도 데이터 계층이 `/login` 으로 보내지만, 그 화면은
      // "로그인하라"는 요구다 — 방금 스스로 로그아웃한 사람에게 낼 화면이 아니다.
      // 공개 열람이 열려 있으므로(ADR-0005) 로그아웃은 서비스의 끝이 아니라 미인증으로의
      // 전환이고, 착지점도 그렇게 읽혀야 한다.
      if (!isPublicRoute(pathname)) {
        router.replace(PUBLIC_LANDING);
      }
      router.refresh();
    } catch {
      toast.error("로그아웃하지 못했습니다. 네트워크 연결을 확인하세요.");
    } finally {
      setPending(false);
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="ghost" size="sm" className="gap-2 pl-1.5" />}
      >
        <Avatar className="size-6">
          <AvatarFallback className="text-2xs">
            {user.nickname.slice(0, 1)}
          </AvatarFallback>
        </Avatar>
        <span className="hidden sm:inline">{user.nickname}</span>
        <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        {/* Base UI 의 GroupLabel 은 Group 안에서만 렌더된다. */}
        <DropdownMenuGroup>
          <DropdownMenuLabel className="text-xs text-muted-foreground">
            {user.email ?? "이메일 미제공"}
          </DropdownMenuLabel>
          {/* 이 메뉴가 말하는 마이페이지는 계정 관리(프로필·탈퇴·문의)다. 내 계좌의 기본
              화면은 `자산 현황` 이므로 서브탭을 짚어 준다 — 레일의 `내 계좌` 와 다른
              곳으로 가는 것이 의도다.

              **그 탭의 유일한 진입점이 여기다** (S15P21A605-252). 계좌의 탭 줄에서도 레일
              목차에서도 빠졌으므로 이 링크를 지우면 닉네임 변경·탈퇴에 닿을 길이 없어진다. */}
          <DropdownMenuItem render={<Link href="/mypage?tab=profile" />}>
            마이페이지
          </DropdownMenuItem>
          {onOpenLegal && (
            <DropdownMenuItem onClick={onOpenLegal}>
              데이터 제공처 및 라이선스
            </DropdownMenuItem>
          )}
          {user.role === "ADMIN" && (
            <DropdownMenuItem render={<Link href="/admin/users" />}>
              관리자
            </DropdownMenuItem>
          )}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem disabled={pending} onClick={handleLogout}>
          로그아웃
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
