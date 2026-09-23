import {
  ChartLine,
  History,
  LayoutDashboard,
  type LucideIcon,
  MessageSquareText,
  Newspaper,
  ShieldCheck,
  Ticket,
  Trophy,
  UserRound,
} from "lucide-react";

/**
 * 주 내비 항목. **정의는 여기 하나다** — 넓은 화면의 레일(`components/app-sidebar.tsx`)과
 * 폰의 하단 탭바(`components/mobile-tab-bar.tsx`)가 같은 배열을 읽는다. 두 곳에 각자
 * 적어 두면 화면이 하나 늘 때 한 곳이 조용히 빠진다 (`lib/account-tabs.ts` 와 같은 판단).
 */
export type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  /** 루트(`/`)는 접두사 매칭이 모든 경로를 잡으므로 정확히 일치할 때만 현재 화면이다. */
  exact?: boolean;
  /** 로그인이 필요한 화면. 미인증에는 진입점을 남기고 로그인 안내로 바꾼다 (ADR-0005). */
  personal?: boolean;
  /** 서브탭 목차를 오른쪽으로 펼치는 항목. 지금은 `내 계좌` 하나다 (`AccountFlyout`). */
  flyout?: boolean;
  /**
   * 폰의 하단 탭바에 직접 나가는 항목. 다섯 칸 중 넷이고 나머지 한 칸은 `더보기` 다 —
   * 엄지로 닿는 줄에 여섯을 넣으면 칸이 터치 최소 폭 아래로 내려간다.
   *
   * 고르는 기준은 **방문 빈도**다. 복기는 시즌 끝에 몰려 보는 화면이라 빠져 있는데,
   * 그때가 정작 가장 많이 찾는 때다 — 실제 사용을 보고 다시 판단한다 (ADR-0003).
   */
  mobilePrimary?: boolean;
};

export const NAV: NavItem[] = [
  {
    href: "/",
    label: "마켓",
    icon: ChartLine,
    exact: true,
    mobilePrimary: true,
  },
  {
    href: "/dashboard",
    label: "대시보드",
    icon: LayoutDashboard,
    mobilePrimary: true,
  },
  {
    href: "/mypage",
    label: "내 계좌",
    icon: UserRound,
    personal: true,
    flyout: true,
    mobilePrimary: true,
  },
  // 계좌에서 떨어져 나온 화면이라 그 옆에 둔다 (S15P21A605-252). 청약은 보유 자산을 보는
  // 일이 아니라 기간이 정해진 별개의 일이고, 알림이 곧장 데려오는 자리라 레일에 제
  // 진입점이 있어야 한다. 폰에서는 `더보기` 안이다 — 하단 다섯 칸은 매일 오가는 것이 쓴다.
  { href: "/ipo", label: "공모주", icon: Ticket, personal: true },
  { href: "/news", label: "뉴스", icon: Newspaper },
  { href: "/ranking", label: "랭킹", icon: Trophy, mobilePrimary: true },
  // 복기는 하루가 끝난 뒤에 보는 화면이라 레일 끝에 둔다 (시안의 내비 순서).
  { href: "/review", label: "복기", icon: History, personal: true },
];

/** 개인 진입점에 붙는 꼬리표. 링크의 접근 가능한 이름에 들어가야 스크린리더에도 전달된다. */
export const LOGIN_REQUIRED = "로그인 필요";

/** 관리자 화면의 진입 지점. 이 링크가 유일한 진입 수단이다. */
export const ADMIN: NavItem = {
  href: "/admin/users",
  label: "관리자",
  icon: ShieldCheck,
};

/**
 * 문의 쓰기. 목록이 아니라 **작성 화면**으로 곧장 보낸다 — 내비에서 누르는 사람은
 * 지난 문의를 읽으려는 것이 아니라 지금 막힌 것을 말하려는 것이다. 지난 문의는
 * 내 계좌의 `문의 내역` 이 맡는다.
 */
export const INQUIRY: NavItem = {
  href: "/mypage/inquiries/new",
  label: "문의",
  icon: MessageSquareText,
  personal: true,
};

/** 지금 보는 화면인가. 레일과 탭바가 같은 규칙으로 판단해야 표시가 엇갈리지 않는다. */
export function isCurrent(item: NavItem, pathname: string): boolean {
  return item.exact ? pathname === item.href : pathname.startsWith(item.href);
}
