import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PriceChange } from "./price-change";

describe("PriceChange", () => {
  it("색 외에 부호와 기호로도 방향을 낸다", () => {
    const { container, rerender } = render(<PriceChange value={3.8} />);
    expect(screen.getByText("3.80% 상승")).toHaveClass("sr-only");
    expect(container.firstChild).toHaveTextContent("3.80% 상승▲+3.80%");

    rerender(<PriceChange value={-3.8} />);
    // 하이픈이 아니라 U+2212 여야 tabular-nums 열이 어긋나지 않는다.
    expect(screen.getByText("3.80% 하락")).toHaveClass("sr-only");
    expect(container.firstChild).toHaveTextContent("3.80% 하락▼−3.80%");

    rerender(<PriceChange value={0} />);
    expect(screen.getByText("0.00% 보합")).toHaveClass("sr-only");
    expect(container.firstChild).toHaveTextContent("0.00% 보합—0.00%");
  });

  it("등락폭도 같은 규칙으로 낸다", () => {
    const { container } = render(
      <PriceChange value={-12500} unit="원" digits={0} />
    );
    expect(screen.getByText("12500원 하락")).toHaveClass("sr-only");
    expect(container.firstChild).toHaveTextContent("▼−12500원");
  });
});
