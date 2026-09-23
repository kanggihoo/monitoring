import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { GuidedTour, place, type TourStep } from "./guided-tour";

const STEPS: TourStep[] = [
  { target: "one", title: "첫째", body: "첫 칸" },
  { target: "two", title: "둘째", body: "둘째 칸" },
  { target: "three", title: "셋째", body: "셋째 칸" },
];

function renderTour(steps: TourStep[] = STEPS) {
  const onClose = vi.fn();
  render(<GuidedTour steps={steps} open onClose={onClose} />);
  return onClose;
}

afterEach(cleanup);

describe("GuidedTour", () => {
  it("몇 번째 단계인지, 몇 단계짜리인지 말한다", async () => {
    renderTour();

    expect(screen.getByText("1 / 3")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "다음" }));
    expect(screen.getByRole("heading", { name: "둘째" })).toBeInTheDocument();
    expect(screen.getByText("2 / 3")).toBeInTheDocument();
  });

  // 첫 단계에서 `이전` 이 눌리면 아무 일도 없는 버튼이 되고, 그러면 고장으로 읽힌다.
  it("첫 단계에서는 이전이 잠겨 있다", async () => {
    renderTour();

    expect(screen.getByRole("button", { name: "이전" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "다음" }));
    expect(screen.getByRole("button", { name: "이전" })).toBeEnabled();
  });

  it("마지막 단계에서는 다음이 완료가 되고, 누르면 끝난다", async () => {
    const onClose = renderTour();

    await userEvent.click(screen.getByRole("button", { name: "다음" }));
    await userEvent.click(screen.getByRole("button", { name: "다음" }));

    const done = screen.getByRole("button", { name: "완료" });
    expect(done).toBeInTheDocument();
    await userEvent.click(done);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("닫기와 Esc 로도 끝난다", async () => {
    const onClose = renderTour();

    await userEvent.click(
      screen.getByRole("button", { name: "튜토리얼 닫기" })
    );
    await userEvent.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("좌우 방향키로 단계를 옮긴다", async () => {
    renderTour();

    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("heading", { name: "둘째" })).toBeInTheDocument();
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("heading", { name: "첫째" })).toBeInTheDocument();
  });

  // 덮개 뒤를 누르려던 사람이 튜토리얼을 잃지 않아야 한다.
  it("덮개를 눌러서는 닫히지 않는다", async () => {
    const onClose = renderTour();

    await userEvent.click(screen.getByRole("dialog"));

    expect(onClose).not.toHaveBeenCalled();
  });

  it("닫혀 있으면 아무것도 그리지 않는다", () => {
    render(<GuidedTour steps={STEPS} open={false} onClose={vi.fn()} />);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("말풍선 자리", () => {
  const viewport = { width: 1000, height: 800 };
  const size = { width: 320, height: 200 };

  it("원하는 쪽에 붙인다", () => {
    const rect = { top: 300, left: 400, width: 200, height: 100 };

    const { top } = place(rect, size, "bottom", viewport);

    // 대상 아래 + 간격.
    expect(top).toBe(412);
  });

  it("자리가 모자라면 반대쪽으로 뒤집는다", () => {
    // 아래로 60px 밖에 없다 — 말풍선(200)이 들어가지 않는다.
    const rect = { top: 640, left: 400, width: 200, height: 100 };

    const { top } = place(rect, size, "bottom", viewport);

    expect(top).toBe(640 - 200 - 12);
  });

  // 뒤집기만 하면 좁은 화면에서 말풍선이 화면 밖으로 나간다.
  it("뒤집어도 넘치면 화면 안으로 끌어당긴다", () => {
    const narrow = { width: 360, height: 640 };
    const rect = { top: 200, left: 300, width: 40, height: 40 };

    const { left } = place(rect, size, "right", narrow);

    expect(left).toBeGreaterThanOrEqual(16);
    expect(left + size.width).toBeLessThanOrEqual(narrow.width - 16);
  });
});
