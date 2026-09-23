import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";

import { Button } from "@/components/ui/button";

// 설정 검증용 스모크 — jsdom · RTL · jest-dom 매처 · "@/" 별칭 해석이
// 모두 동작하는지 한 번에 확인한다.
test("버튼이 렌더되고 클릭 핸들러가 호출된다", async () => {
  const onClick = vi.fn();
  render(<Button onClick={onClick}>저장</Button>);

  const button = screen.getByRole("button", { name: "저장" });
  expect(button).toBeVisible();

  await userEvent.click(button);
  expect(onClick).toHaveBeenCalledOnce();
});
