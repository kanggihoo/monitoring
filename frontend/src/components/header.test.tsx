import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";

import type { User } from "@/lib/auth";

import { Header } from "./header";

// Header 가 품는 LogoutButton 이 클라이언트 훅을 쓴다. 여기서 검증하는 것은 로그아웃이
// 아니라 무엇이 보이느냐이므로 경계만 막아 둔다.
vi.mock("@/lib/api", () => ({ apiFetch: vi.fn(), apiErrorMessage: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }),
  // 로그아웃 착지점이 공개 경로 여부로 갈리므로 UserMenu 가 현재 경로를 읽는다.
  usePathname: () => "/",
}));

afterEach(cleanup);

function user(overrides: Partial<User> = {}): User {
  return {
    id: 1,
    email: "hong@example.com",
    nickname: "홍길동",
    role: "USER",
    providers: ["google"],
    ...overrides,
  };
}

test("로그인하지 않았으면 로그인 링크가 보인다", () => {
  render(<Header user={null} />);

  expect(screen.getByRole("link", { name: "로그인" })).toBeInTheDocument();
});

// 빈 값으로 두면 "데이터가 없는 것" 과 "권한이 없는 것" 을 구별할 수 없다 (ADR-0005).
test("로그인하지 않았으면 개인 지표 자리에 로그인 안내가 보인다", () => {
  render(<Header user={null} />);

  expect(screen.getByText(/내 자산과 순위는 로그인/)).toBeInTheDocument();
  expect(screen.queryByText("Total assets")).not.toBeInTheDocument();
  expect(screen.queryByText("Rank")).not.toBeInTheDocument();
});

// 공개 지표는 미인증에도 그대로 있다 — 그것이 공개 화면에서 보여줄 것이다.
test("로그인하지 않아도 게임 시계와 환율은 보인다", () => {
  render(<Header user={null} />);

  expect(screen.getByText("Replay date")).toBeInTheDocument();
  expect(screen.getByText("USD/KRW")).toBeInTheDocument();
});

test("로그인했으면 개인 지표가 보이고 로그인 안내가 없다", () => {
  render(<Header user={user()} />);

  expect(screen.getByText("Total assets")).toBeInTheDocument();
  expect(screen.getByText("Rank")).toBeInTheDocument();
  expect(screen.queryByText(/내 자산과 순위는 로그인/)).not.toBeInTheDocument();
});

test("로그인했으면 닉네임이 사용자 메뉴에 보인다", () => {
  render(<Header user={user()} />);

  expect(screen.getByRole("button", { name: /홍길동/ })).toBeInTheDocument();
});

test("로그인 방법이 하나도 없으면 샌드박스 안내와 나가는 방법이 보인다", () => {
  render(<Header user={user({ providers: [] })} />);

  const banner = screen.getByRole("status");
  expect(banner).toHaveTextContent("샌드박스");
  // 갇혔다고 느끼지 않도록 나가는 방법이 문구에 있어야 한다.
  expect(banner).toHaveTextContent("로그아웃");
});

test("로그인 방법이 있으면 샌드박스 안내가 보이지 않는다", () => {
  render(<Header user={user({ providers: ["google"] })} />);

  expect(screen.queryByRole("status")).not.toBeInTheDocument();
});
