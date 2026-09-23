import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import { NicknameForm } from "./nickname-form";
import { WithdrawSection } from "./withdraw-section";

const { apiFetchMock, refreshMock, replaceMock } = vi.hoisted(() => ({
  apiFetchMock: vi.fn(),
  refreshMock: vi.fn(),
  replaceMock: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  apiFetch: apiFetchMock,
  apiErrorMessage: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: refreshMock, replace: replaceMock }),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

test("닉네임 변경 중 네트워크 오류가 나면 안내하고 다시 시도할 수 있다", async () => {
  apiFetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  const user = userEvent.setup();
  render(<NicknameForm nickname="홍길동" />);

  const submit = screen.getByRole("button", { name: "저장" });
  await user.click(submit);

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "닉네임을 바꾸지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
  );
  expect(submit).toBeEnabled();
});

test("로그인 방법이 하나도 없으면 탈퇴 자리가 없다", () => {
  render(<WithdrawSection providers={[]} />);

  expect(screen.queryByRole("button", { name: "탈퇴하기" })).toBeNull();
});

test("탈퇴 중 네트워크 오류가 나면 안내하고 다시 시도할 수 있다", async () => {
  apiFetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  const user = userEvent.setup();
  render(<WithdrawSection providers={["google"]} />);

  await user.click(screen.getByRole("button", { name: "탈퇴하기" }));
  const confirm = screen.getByRole("button", { name: "정말 탈퇴합니다" });
  await user.click(confirm);

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "탈퇴하지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
  );
  expect(confirm).toBeEnabled();
});
