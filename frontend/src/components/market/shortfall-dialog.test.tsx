import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";

import { type Shortfall, ShortfallDialog } from "./shortfall-dialog";

afterEach(cleanup);

const preview = (sendAmount: string): Shortfall => ({
  currency: "USD",
  shortfall: "120.00",
  shortfallExchange: { direction: "KRW_TO_USD", sendAmount },
  availableCash: "0.00",
});

/**
 * 이 대화상자는 사용자가 방금 시작한 주문을 이어 가려고 뜬다. 기본 초점은 탭 순서상 첫
 * 버튼인 `나중에` 라, 매수를 누르고 이어서 Enter 를 친 사람이 제 주문을 취소한다.
 */
test("열면 환전 버튼에 초점이 가 Enter 가 환전을 고른다", async () => {
  render(
    <ShortfallDialog
      preview={preview("170000")}
      open
      onOpenChange={() => {}}
      rate={1400}
      sendAvailable={1000000}
      onConfirm={() => {}}
    />
  );

  const confirm = screen.getByRole("button", { name: /환전하고 주문/ });
  await waitFor(() => expect(confirm).toHaveFocus());
});

/** 보낼 돈이 모자라면 버튼이 꺼져 있다. 꺼진 버튼에 초점을 주면 Enter 가 거짓 약속이 된다. */
test("보낼 돈이 모자라면 환전 버튼이 꺼지고 초점을 가져가지 않는다", async () => {
  const onConfirm = vi.fn();
  render(
    <ShortfallDialog
      preview={preview("170000")}
      open
      onOpenChange={() => {}}
      rate={1400}
      sendAvailable={1000}
      onConfirm={onConfirm}
    />
  );

  const confirm = screen.getByRole("button", { name: /환전하고 주문/ });
  expect(confirm).toBeDisabled();
  expect(confirm).not.toHaveFocus();
  expect(onConfirm).not.toHaveBeenCalled();
});
