import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { AbsenceSummary } from "@/lib/market-types";

import { AbsenceSummaryDialog } from "./absence-summary";

type Absent = Extract<AbsenceSummary, { absent: true }>;

const SUMMARY: Absent = {
  absent: true,
  fromTickSeq: 412,
  toTickSeq: 501,
  fromGameDate: "2022-02-01",
  toGameDate: "2022-04-15",
  filledOrderCount: 2,
  valuationChangeKrw: "3820000",
  returnChange: 3.44,
  eventCount: 5,
};

function renderDialog(summary: Absent) {
  render(
    <AbsenceSummaryDialog
      summary={summary}
      open
      onOpenChange={() => {}}
      onAcknowledge={() => {}}
    />
  );
}

afterEach(cleanup);

describe("AbsenceSummaryDialog", () => {
  /**
   * 이 모달의 답은 "비운 사이 얼마가 되었나" 하나다. 남은 줄과 같은 무게로 두면 가장
   * 궁금한 값을 여러 줄 중에서 찾아 읽어야 한다.
   */
  it("평가액 변동을 금액·비율·방향으로 함께 말한다", () => {
    renderDialog(SUMMARY);

    // 부호가 금액에 붙는다 — 색만으로 방향을 말하지 않는다 (DESIGN.md).
    expect(screen.getByText("+3,820,000원")).toBeInTheDocument();
    expect(
      screen.getByLabelText("평가액이 3,820,000원 늘었습니다")
    ).toBeInTheDocument();
    expect(screen.getByText("3.44% 상승")).toBeInTheDocument();
  });

  /** 빼기 기호는 하이픈이 아니라 U+2212 다. 하이픈은 tabular-nums 옆에서 자릿수를 흔든다. */
  it("줄어든 구간은 빼기 기호와 하락 방향으로 말한다", () => {
    renderDialog({
      ...SUMMARY,
      valuationChangeKrw: "-1240000",
      returnChange: -1.12,
    });

    expect(screen.getByText("−1,240,000원")).toBeInTheDocument();
    expect(
      screen.getByLabelText("평가액이 1,240,000원 줄었습니다")
    ).toBeInTheDocument();
    expect(screen.getByText("1.12% 하락")).toBeInTheDocument();
  });

  /**
   * 복원할 수 없으면 두 값이 응답에서 함께 빠진다 (`acc.md`). 0 으로 채우면 "변동이
   * 없었다" 로 읽히므로 판째로 그리지 않는다.
   */
  it("평가액을 복원하지 못한 구간은 판을 그리지 않는다", () => {
    renderDialog({
      ...SUMMARY,
      valuationChangeKrw: undefined,
      returnChange: undefined,
    });

    expect(
      screen.queryByText("자리를 비운 사이 평가액")
    ).not.toBeInTheDocument();
    expect(screen.getByText("예약 체결")).toBeInTheDocument();
  });

  /** 건수만 남은 줄이라 읽는 사람이 무엇을 해야 할지 말하지 않는다. */
  it("주요 이벤트 줄은 없다", () => {
    renderDialog(SUMMARY);

    expect(screen.queryByText("주요 이벤트")).not.toBeInTheDocument();
  });
});
