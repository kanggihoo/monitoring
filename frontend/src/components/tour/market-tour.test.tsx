import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { hasSeenTour, requestTour, TOUR_VERSION } from "@/lib/tour";

import { MARKET_TOUR, MarketTour } from "./market-tour";

const KEY = "flashback.tour.market";

beforeEach(() => localStorage.clear());
afterEach(cleanup);

/** 자동 실행은 마이크로태스크 한 마디 뒤다 (떠 있는 모달이 있으면 그것이 닫힐 때). */
function tourOpens() {
  return waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
}

describe("MarketTour", () => {
  it("처음 온 사람에게는 저절로 뜬다", async () => {
    render(<MarketTour isLoggedIn={false} />);

    await tourOpens();
    expect(screen.getByText("1 / 6")).toBeInTheDocument();
  });

  it("본 적 있으면 뜨지 않는다", async () => {
    localStorage.setItem(KEY, String(TOUR_VERSION));
    render(<MarketTour isLoggedIn={false} />);

    // "아직 안 뜬 것" 과 "안 뜰 것" 을 한 틱으로는 못 가르므로 넉넉히 기다린다.
    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  // 끝까지 안 보고 닫은 사람에게 새로고침마다 다시 띄우지 않는다.
  it("도중에 닫아도 봤다고 기억한다", async () => {
    render(<MarketTour isLoggedIn={false} />);
    await tourOpens();

    await userEvent.click(
      screen.getByRole("button", { name: "튜토리얼 닫기" })
    );

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(hasSeenTour(MARKET_TOUR)).toBe(true);
  });

  it("다시 보기 신호를 받으면 처음부터 다시 연다", async () => {
    localStorage.setItem(KEY, String(TOUR_VERSION));
    render(<MarketTour isLoggedIn={false} />);

    requestTour(MARKET_TOUR);

    await tourOpens();
    expect(screen.getByText("1 / 6")).toBeInTheDocument();
  });

  // 개장 브리핑과 겹치면 어느 쪽을 닫아야 하는지가 사라진다.
  it("다른 모달이 떠 있는 동안에는 열지 않는다", async () => {
    const briefing = document.createElement("div");
    briefing.setAttribute("role", "dialog");
    document.body.append(briefing);

    render(<MarketTour isLoggedIn={false} />);
    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(screen.queryByRole("dialog")).toBe(briefing);

    briefing.remove();
    await tourOpens();
  });
});
