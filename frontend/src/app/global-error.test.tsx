import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import GlobalError from "@/app/global-error";

describe("전역 오류 화면", () => {
  it("루트 레이아웃 없이 복구 수단을 제공한다", () => {
    const retry = vi.fn();

    render(
      <GlobalError
        error={Object.assign(new Error("DB_PASSWORD=secret"), {
          digest: "private-digest",
        })}
        retry={retry}
      />,
      { container: document }
    );

    expect(
      screen.getByRole("heading", { name: "서비스를 표시하지 못했습니다" })
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "홈으로 이동" })).toHaveAttribute(
      "href",
      "/"
    );

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(retry).toHaveBeenCalledOnce();

    expect(screen.queryByText(/DB_PASSWORD/)).not.toBeInTheDocument();
    expect(screen.queryByText(/private-digest/)).not.toBeInTheDocument();
  });
});
