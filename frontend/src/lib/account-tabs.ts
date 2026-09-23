/**
 * 내 계좌의 서브탭. **정의는 여기 하나다** — 탭 줄(`mypage/account-tabs.tsx`), 주소 파라미터
 * 해석(`mypage/page.tsx`), 레일의 플라이아웃(`components/app-sidebar.tsx`) 셋이 같은 배열을
 * 읽는다. 세 곳에 각자 적어 두면 탭이 하나 늘 때 한 곳이 조용히 빠진다.
 *
 * 주문 둘은 계약의 조회 경로 하나씩에 그대로 대응한다 — `예약 주문` 은 `GET /orders`(미체결),
 * `체결 내역` 은 `GET /executions`(체결)다.
 *
 * `취소·만료` 를 따로 두지 않는다. 취소는 `DELETE` 로 사라지고 미체결 예약은 폐장에 소멸해,
 * 그 둘만 담는 경로가 계약에 없다 (`ord.md`). 그래서 체결 내역은 **체결만** 담고, 예약의
 * 정정·취소는 `예약 주문` 탭의 각 줄에서 한다.
 *
 * `거래 내역` 안에서 체결과 환전을 전환한다. 기획 7.4.3 이 "환전 이력도 내역에 포함" 하라고
 * 한 것을 **탭 하나 안의 전환**으로 읽었다 — 환전에는 종목·수량·손익이 없어 열이 하나도
 * 겹치지 않고 (`acc.md`), 한 표에 합치면 절반이 늘 빈 칸이 된다.
 *
 * **공모주는 여기 없다.** 제 화면(`/ipo`)으로 나가 레일의 항목이 되었다 (S15P21A605-252).
 */
export const ACCOUNT_TABS = [
  { value: "assets", label: "자산 현황" },
  { value: "reserved", label: "예약 주문" },
  { value: "filled", label: "거래 내역" },
] as const;

/**
 * 탭 줄에도 레일 목차에도 나오지 않는 탭. **헤더 사용자 메뉴가 유일한 진입점이다** — 계정
 * 관리(프로필·탈퇴·문의)는 아바타 메뉴에서 찾는 것이라, 매일 보는 계좌 탭 줄에 자리를
 * 하나 더 차지할 이유가 없다 (S15P21A605-252). 주소는 그대로라 북마크와 딥링크는 산다.
 */
export const PROFILE_TAB = "profile";

export type AccountTab =
  (typeof ACCOUNT_TABS)[number]["value"] | typeof PROFILE_TAB;

/** 탭을 짚는 주소. 헤더 메뉴·레일이 모두 이 형태를 쓴다. */
export function accountTabHref(tab: AccountTab): string {
  return `/mypage?tab=${tab}`;
}

/** 주소가 짚은 탭. 모르는 값이면 기본값으로 떨어진다. */
export function accountTabParam(
  value: string | string[] | undefined
): AccountTab {
  if (value === PROFILE_TAB) return PROFILE_TAB;
  return ACCOUNT_TABS.find((tab) => tab.value === value)?.value ?? "assets";
}
