import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import { INSTRUMENTS, POSITIONS } from "@/components/market/fixtures";

import { HoldingsTable } from "./holdings-table";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

afterEach(cleanup);

function displayedNames() {
  return screen
    .getAllByRole("row")
    .slice(1)
    .map((row) => (row as HTMLTableRowElement).cells[0]?.textContent);
}

test("비중과 수익률을 누르면 내림차순으로 정렬하고 다시 누르면 방향을 바꾼다", async () => {
  const user = userEvent.setup();
  render(<HoldingsTable positions={POSITIONS} instruments={INSTRUMENTS} />);

  await user.click(screen.getByRole("button", { name: "비중 정렬" }));
  expect(displayedNames()).toEqual([
    "삼성전자",
    "엔비디아",
    "이더리움",
    "KODEX 200",
  ]);
  expect(
    screen.getByRole("button", { name: "비중 내림차순 정렬" })
  ).toBeVisible();

  await user.click(screen.getByRole("button", { name: "비중 내림차순 정렬" }));
  expect(displayedNames()).toEqual([
    "KODEX 200",
    "이더리움",
    "엔비디아",
    "삼성전자",
  ]);

  await user.click(screen.getByRole("button", { name: "수익률 정렬" }));
  expect(displayedNames()).toEqual([
    "엔비디아",
    "KODEX 200",
    "삼성전자",
    "이더리움",
  ]);
});
