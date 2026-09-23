import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";

import { useNow } from "./use-now";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function Probe({ label }: { label: string }) {
  const now = useNow();
  return <span data-testid={label}>{now}</span>;
}

const read = (label: string) => screen.getByTestId(label).textContent;

/**
 * 드리프트의 정체는 위상차다. 컴포넌트마다 자기 시점에 `setInterval` 을 걸면 초 경계를
 * 넘는 순간이 달라, 같은 절대 시각에서 계산한 남은 초가 1 초씩 어긋나 보인다.
 */
test("마운트 시점이 다른 소비자들이 같은 시각을 본다", () => {
  vi.useFakeTimers();

  render(<Probe label="first" />);
  // 두 번째 소비자가 초 경계 한가운데에서 붙는다 — 이전 판이라면 여기서 위상이 갈렸다.
  act(() => void vi.advanceTimersByTime(400));
  render(<Probe label="second" />);

  expect(read("second")).toBe(read("first"));

  act(() => void vi.advanceTimersByTime(1000));
  expect(read("second")).toBe(read("first"));

  act(() => void vi.advanceTimersByTime(1000));
  expect(read("second")).toBe(read("first"));
});

/** 타이머가 멎어 있던 만큼 값이 낡는다. 첫 구독자가 그 낡은 값으로 한 박자를 보내면 안 된다. */
test("구독이 끊겼다 다시 붙으면 실제 시각으로 보정한다", () => {
  vi.useFakeTimers();

  const { unmount } = render(<Probe label="first" />);
  act(() => void vi.advanceTimersByTime(1000));
  const before = read("first");
  unmount();

  // 화면에 시계가 하나도 없는 동안 타이머는 접혀 있다.
  act(() => void vi.advanceTimersByTime(60_000));
  render(<Probe label="second" />);

  expect(Number(read("second"))).toBe(Number(before) + 60_000);
});
