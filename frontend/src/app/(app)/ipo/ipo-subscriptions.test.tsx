import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import type { Briefing } from "@/lib/market-types";

import { IpoSubscriptions } from "./ipo-subscriptions";

const { fetchBriefingMock, fetchIpoOfferingsMock, reloadMock } = vi.hoisted(
  () => ({
    fetchBriefingMock: vi.fn(),
    fetchIpoOfferingsMock: vi.fn(),
    reloadMock: vi.fn(),
  })
);

const BRIEFING: Briefing = {
  seasonDay: 1,
  replayStartGameMonth: "2020-08",
  schedule: {
    tradingStartsAt: "2026-09-16T02:05:00Z",
    feeFreeStartsAt: "2026-09-16T03:00:00Z",
    feeFreeEndsAt: "2026-09-16T03:10:00Z",
    blackoutStartsAt: "2026-09-16T07:00:00Z",
    closesAt: "2026-09-16T07:40:00Z",
    settlementAt: "2026-09-16T08:00:00Z",
  },
  ipoWindows: [
    {
      disclosureAt: "2026-09-16T03:17:00Z",
      subscriptionClosesAt: "2026-09-16T03:29:00Z",
    },
  ],
};

vi.mock("@/lib/market-api", () => ({
  fetchBriefing: fetchBriefingMock,
  fetchIpoOffering: vi.fn(),
  fetchIpoOfferings: fetchIpoOfferingsMock,
}));

vi.mock("@/hooks/use-live-query", () => ({
  useLiveQuery: (read: unknown) =>
    read === fetchBriefingMock
      ? { data: BRIEFING, error: null, reload: reloadMock }
      : { data: { offerings: [] }, error: null, reload: reloadMock },
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

test("청약 절차와 규칙 아이콘을 누르면 상세 규칙이 열린다", async () => {
  const user = userEvent.setup();
  render(<IpoSubscriptions />);

  expect(screen.queryByText("예고 공시")).toBeNull();

  await user.click(screen.getByRole("button", { name: "청약 절차와 규칙" }));

  expect(await screen.findByText("예고 공시")).toBeInTheDocument();
  expect(screen.getByText("마감 · 추첨")).toBeInTheDocument();
});
