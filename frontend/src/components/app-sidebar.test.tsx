import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import { AppSidebar } from "./app-sidebar";

vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

test("권한이 관리자면 관리자 화면 링크가 보인다", () => {
  render(<AppSidebar isAdmin isLoggedIn />);

  expect(screen.getByRole("link", { name: "관리자" })).toBeInTheDocument();
});

test("일반 사용자에게는 관리자 화면 링크가 보이지 않는다", () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  expect(
    screen.queryByRole("link", { name: "관리자" })
  ).not.toBeInTheDocument();
});

// 진입점을 지우지 않는다 — 무엇을 할 수 있는 서비스인지 알 수 있어야 한다 (ADR-0005).
test("로그인하지 않았어도 개인 메뉴가 사라지지 않고 로그인 안내가 붙는다", () => {
  render(<AppSidebar isAdmin={false} isLoggedIn={false} />);

  const account = screen.getByRole("link", { name: /내 계좌/ });
  expect(account).toHaveAccessibleName("내 계좌 · 로그인 필요");
  // 주소는 그대로다 — 로그인으로 보내는 일은 프록시가 하고, 링크는 제 화면을 가리켜야
  // 딥링크가 성립한다.
  expect(account).toHaveAttribute("href", "/mypage");
  expect(screen.getByRole("link", { name: /복기/ })).toHaveAccessibleName(
    "복기 · 로그인 필요"
  );
});

/**
 * 눌러도 아무 일이 없으면 고장으로 보이고, `/login` 으로 넘겨 버리면 보던 화면을 잃는다.
 * 지금 화면 위에 안내가 떠야 둘 다 피한다.
 */
test("미인증이 개인 메뉴를 누르면 화면을 넘기지 않고 안내가 뜬다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn={false} />);

  await userEvent.click(screen.getByRole("link", { name: /내 계좌/ }));

  const dialog = screen.getByRole("alertdialog");
  expect(dialog).toHaveTextContent(
    "내 계좌는 로그인해야 볼 수 있는 메뉴입니다"
  );
  // 어느 항목을 눌렀는지가 문장에 들어간다 — 문의도 같은 모달을 쓰지만 다른 문장이다.
  expect(
    screen.getByRole("link", { name: "Google로 로그인" })
  ).toBeInTheDocument();
});

test("모달을 열기만 해서는 복귀 경로가 담기지 않는다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn={false} />);

  await userEvent.click(screen.getByRole("link", { name: /내 계좌/ }));

  // 그냥 닫고 떠난 사람의 경로가 남으면 나중에 다른 화면에서 로그인한 사람이 끌려온다.
  expect(sessionStorage.getItem("loginReturnPath")).toBeNull();
});

test("모달에서 공급자를 고르면 가려던 화면이 복귀 경로가 된다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn={false} />);

  await userEvent.click(screen.getByRole("link", { name: /복기/ }));
  await userEvent.click(screen.getByRole("link", { name: "SSAFY 로그인" }));

  expect(sessionStorage.getItem("loginReturnPath")).toBe("/review");
});

test("로그인했으면 개인 메뉴를 눌러도 안내가 뜨지 않는다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  await userEvent.click(screen.getByRole("link", { name: "내 계좌" }));

  expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
});

// 공개 화면은 미인증에도 제 경로로 간다.
test("로그인하지 않았어도 공개 메뉴는 제 경로로 간다", () => {
  render(<AppSidebar isAdmin={false} isLoggedIn={false} />);

  expect(screen.getByRole("link", { name: "마켓" })).toHaveAttribute(
    "href",
    "/"
  );
});

test("로그인했으면 개인 메뉴가 제 경로로 가고 안내가 없다", () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  const account = screen.getByRole("link", { name: "내 계좌" });
  expect(account).toHaveAttribute("href", "/mypage");
  expect(screen.queryByText(/로그인 필요/)).not.toBeInTheDocument();
});

test("내 계좌에 호버하면 서브탭 목차가 펼쳐진다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  await userEvent.hover(screen.getByRole("link", { name: "내 계좌" }));

  const menu = await screen.findByRole("menu");
  // 목차는 `lib/account-tabs.ts` 의 정의를 그대로 읽는다 — 여기서 순서까지 못 박아,
  // 탭이 늘거나 이름이 바뀌었는데 레일만 옛 목록에 머무는 일을 막는다.
  expect(
    within(menu)
      .getAllByRole("menuitem")
      .map((item) => item.textContent)
  ).toEqual(["자산 현황", "예약 주문", "거래 내역"]);
  expect(
    within(menu).getByRole("menuitem", { name: "거래 내역" })
  ).toHaveAttribute("href", "/mypage?tab=filled");
});

// 호버 전용이면 키보드 사용자에게는 이 지름길이 없는 것과 같다.
test("키보드로도 서브탭 목차를 열고 닫을 수 있다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  screen.getByRole("link", { name: "내 계좌" }).focus();
  await userEvent.keyboard("{ArrowDown}");

  expect(await screen.findByRole("menu")).toBeInTheDocument();

  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
});

// 들어갈 수 없는 화면의 목차를 여는 것이 되고, 타일을 누르면 뜨는 로그인 안내와도 엇갈린다.
test("미인증에는 서브탭 목차가 열리지 않는다", async () => {
  render(<AppSidebar isAdmin={false} isLoggedIn={false} />);

  await userEvent.hover(screen.getByRole("link", { name: /내 계좌/ }));

  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
});

// 공모주가 계좌의 탭이 아니라 레일의 항목이 되었다 (S15P21A605-252). 알림이 데려오는
// 자리이기도 해서, 목차 안에 숨어 있으면 안 된다.
test("공모주가 레일의 제 항목으로 있다", () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  expect(screen.getByRole("link", { name: "공모주" })).toHaveAttribute(
    "href",
    "/ipo"
  );
});

test("현재 화면의 링크에 aria-current 가 붙는다", () => {
  render(<AppSidebar isAdmin={false} isLoggedIn />);

  expect(screen.getByRole("link", { name: "마켓" })).toHaveAttribute(
    "aria-current",
    "page"
  );
  expect(screen.getByRole("link", { name: "대시보드" })).not.toHaveAttribute(
    "aria-current"
  );
});
