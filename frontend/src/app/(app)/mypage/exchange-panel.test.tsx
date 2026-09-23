import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clockSnapshot, VALUATION } from "@/components/market/fixtures";
import type { ExchangePreview } from "@/lib/order-api";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

import { ExchangePanel } from "./exchange-panel";

/**
 * 미리보기는 **묻는 금액과 무관하게 같은 상한**을 답한다. 서버도 그렇다 — `availableCash`
 * 는 계좌의 상한이지 이번 요청의 값이 아니다.
 */
function previewFor(amount: string, availableCash: string): ExchangePreview {
  return {
    direction: "KRW_TO_USD",
    rate: "1187.20",
    sendAmount: amount,
    feeRate: 0.05,
    fee: "500",
    receiveAmount: "842.03",
    availableCash,
  };
}

/** 서버가 말하는 상한. 계좌 잔고(3,800만)보다 작게 둬 둘이 갈릴 때를 본다. */
const SERVER_AVAILABLE = "12500000";

function mockPreview(availableCash = SERVER_AVAILABLE) {
  vi.stubGlobal(
    "fetch",
    vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      const amount = new URL(url, "http://localhost").searchParams.get(
        "amount"
      );
      return Promise.resolve(
        new Response(
          JSON.stringify({ data: previewFor(amount ?? "0", availableCash) }),
          {
            status: 200,
            headers: { "content-type": "application/json" },
          }
        )
      );
    })
  );
}

beforeEach(() => {
  sessionStore.getState().applyClock(clockSnapshot());
  sessionStore.getState().setConnected(true);
  tickStore.setState({ valuation: VALUATION, usdKrwRate: "1187.20" });
  mockPreview();
});

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
});

const amountInput = () =>
  screen.getByLabelText(/보낼 금액/) as HTMLInputElement;
const maxButton = () => screen.getByRole("button", { name: "전액" });

describe("ExchangePanel 전액 버튼", () => {
  it("환전 가능 금액을 자릿점과 함께 채운다", async () => {
    render(<ExchangePanel />);

    await userEvent.click(maxButton());
    expect(amountInput().value).toBe("38,000,000");
  });

  /**
   * 미리보기가 계좌 잔고보다 작은 상한을 말하면 채운 금액도 따라 줄어야 한다. 옛 상한이
   * 남으면 "전액" 을 눌렀는데 초과 경고가 뜬 채 환전 버튼이 닫힌다.
   */
  it("미리보기가 더 작은 상한을 말하면 그 값까지 내려간다", async () => {
    render(<ExchangePanel />);

    await userEvent.click(maxButton());
    await waitFor(() => expect(amountInput().value).toBe("12,500,000"));

    expect(screen.queryByRole("alert")).toBeNull();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "환전하기" })).toBeEnabled()
    );
  });

  /** 손으로 고치면 따라가기를 멈춘다 — 그때부터는 사용자가 정한 금액이다. */
  it("손으로 고친 뒤에는 상한을 따라가지 않는다", async () => {
    render(<ExchangePanel />);

    await userEvent.click(maxButton());
    await userEvent.clear(amountInput());
    await userEvent.type(amountInput(), "1000");

    await waitFor(() => expect(amountInput().value).toBe("1,000"));
    expect(amountInput().value).toBe("1,000");
  });

  /** 보낼 것이 없으면 채울 것도 없다. */
  it("환전 가능 금액이 없으면 잠긴다", () => {
    tickStore.setState({
      valuation: { ...VALUATION, krwCash: "0", lockedKrw: "0" },
    });
    render(<ExchangePanel />);

    expect(maxButton()).toBeDisabled();
  });
});
