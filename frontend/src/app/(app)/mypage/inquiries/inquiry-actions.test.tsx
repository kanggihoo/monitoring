import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import { DeleteInquiry } from "./[id]/delete-inquiry";
import { InquiryForm } from "./new/inquiry-form";

const { apiFetchMock, refreshMock, replaceMock, backMock } = vi.hoisted(() => ({
  apiFetchMock: vi.fn(),
  refreshMock: vi.fn(),
  replaceMock: vi.fn(),
  backMock: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  apiFetch: apiFetchMock,
  apiErrorMessage: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    refresh: refreshMock,
    replace: replaceMock,
    back: backMock,
  }),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

test("문의를 지우려면 확인을 거쳐야 한다", async () => {
  const user = userEvent.setup();
  render(<DeleteInquiry inquiryId={1} />);

  await user.click(screen.getByRole("button", { name: "문의 지우기" }));

  // 첫 클릭은 확인만 띄운다. 되돌릴 수 없는 동작이라 한 번의 클릭으로 지워지지 않는다.
  expect(apiFetchMock).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "정말 지웁니다" })).toBeEnabled();
});

test("문의 삭제 중 네트워크 오류가 나면 안내하고 다시 시도할 수 있다", async () => {
  apiFetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  const user = userEvent.setup();
  render(<DeleteInquiry inquiryId={1} />);

  await user.click(screen.getByRole("button", { name: "문의 지우기" }));
  const confirm = screen.getByRole("button", { name: "정말 지웁니다" });
  await user.click(confirm);

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "문의를 지우지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
  );
  expect(confirm).toBeEnabled();
});

test("제목이나 내용이 비어 있으면 보낼 수 없다", async () => {
  const user = userEvent.setup();
  render(<InquiryForm />);

  const submit = screen.getByRole("button", { name: "보내기" });
  expect(submit).toBeDisabled();

  await user.type(screen.getByLabelText("제목"), "로그인이 안 됩니다");
  expect(submit).toBeDisabled();

  await user.type(
    screen.getByLabelText("내용"),
    "버튼을 눌러도 반응이 없습니다"
  );
  expect(submit).toBeEnabled();
});

test("문의 작성 중 네트워크 오류가 나면 안내하고 다시 시도할 수 있다", async () => {
  apiFetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  const user = userEvent.setup();
  render(<InquiryForm />);

  await user.type(screen.getByLabelText("제목"), "제목");
  await user.type(screen.getByLabelText("내용"), "내용");
  const submit = screen.getByRole("button", { name: "보내기" });
  await user.click(submit);

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "문의를 보내지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
  );
  expect(submit).toBeEnabled();
});
