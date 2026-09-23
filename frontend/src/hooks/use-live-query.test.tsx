import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";

import type { Envelope } from "@/lib/ws";
import { liveQueryStore } from "@/stores/live-query-store";

import { useLiveQuery } from "./use-live-query";

type Bag = { items: number[] };

const wrap = (n: number): Envelope =>
  ({
    type: "order.changed",
    payload: { n },
    tickSeq: null,
    gameDate: null,
    replayEpoch: null,
  }) as unknown as Envelope;

/** 사건 하나를 목록 뒤에 붙인다. 관련 없는 타입은 참조를 그대로 둔다. */
const reduce = (data: Bag, event: Envelope): Bag =>
  event.type === "order.changed"
    ? { items: [...data.items, (event.payload as { n: number }).n] }
    : data;

function Probe({
  read,
  enabled = true,
}: {
  read: () => Promise<Bag>;
  enabled?: boolean;
}) {
  const { data, error } = useLiveQuery(read, reduce, enabled);
  return (
    <div data-testid="out">
      {error ?? (data ? data.items.join(",") : "loading")}
    </div>
  );
}

const out = () => screen.getByTestId("out").textContent;
const send = (n: number) =>
  act(() => {
    liveQueryStore.getState().receive(wrap(n));
  });
const flush = () =>
  act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });

afterEach(() => {
  cleanup();
  act(() => {
    liveQueryStore.setState({ event: null, generation: 0 });
  });
  vi.restoreAllMocks();
});

// ── 시나리오 1: 초기 조회 중 WS 도착 ────────────────────────────────────────
test("조회가 도는 동안 온 사건은 버려지지 않고 응답 위에 순서대로 얹힌다", async () => {
  let settle: (value: Bag) => void = () => {};
  const read = () =>
    new Promise<Bag>((resolve) => {
      settle = resolve;
    });

  render(<Probe read={read} />);
  expect(out()).toBe("loading");

  send(1);
  send(2);
  expect(out()).toBe("loading"); // 아직 기준이 없다 — 버퍼에만 쌓인다

  await act(async () => {
    settle({ items: [0] });
  });
  expect(out()).toBe("0,1,2");
});

test("조회가 끝난 뒤 온 사건은 곧바로 반영된다", async () => {
  render(<Probe read={() => Promise.resolve({ items: [0] })} />);
  await flush();
  expect(out()).toBe("0");

  send(7);
  expect(out()).toBe("0,7");
});

test("조회 중 128건을 넘기면 버퍼 대신 재조회로 간다", async () => {
  let settle: (value: Bag) => void = () => {};
  const read = vi.fn(
    () =>
      new Promise<Bag>((resolve) => {
        settle = resolve;
      })
  );

  render(<Probe read={read} />);
  for (let n = 0; n < 130; n += 1) send(n);
  expect(read).toHaveBeenCalledTimes(1);

  await act(async () => {
    settle({ items: [0] });
  });
  // 낡은 응답을 그리지 않고 처음부터 다시 읽는다.
  expect(read).toHaveBeenCalledTimes(2);
  await act(async () => {
    settle({ items: [9] });
  });
  expect(out()).toBe("9");
});

test("조회가 실패하면 문구를 보이고 버퍼를 비운다", async () => {
  render(
    <Probe read={() => Promise.reject(new Error("서버가 답하지 않습니다"))} />
  );
  await flush();
  expect(out()).toBe("서버가 답하지 않습니다");

  // 목록이 없는데 reduce 를 돌리면 안 된다 (current === null 가드).
  send(1);
  expect(out()).toBe("서버가 답하지 않습니다");
});

// ── 시나리오 2: 재연결 ──────────────────────────────────────────────────────
test("재연결(reset)이면 다시 조회하고, 끊긴 사이의 사건은 이어붙이지 않는다", async () => {
  let served = 0;
  const read = vi.fn(() => Promise.resolve({ items: [served++] }));

  render(<Probe read={read} />);
  await flush();
  send(5);
  expect(out()).toBe("0,5");

  // MarketLiveProvider.onConnect 가 하는 일.
  await act(async () => {
    liveQueryStore.getState().reset();
  });
  await flush();
  expect(read).toHaveBeenCalledTimes(2);
  expect(out()).toBe("1"); // 새 조회 결과만 — 끊기기 전 5 가 남지 않는다
});

test("되감기(reset) 직후 도착한 사건도 새 조회 위에 정확히 한 번만 얹힌다", async () => {
  let settle: (value: Bag) => void = () => {};
  const read = vi.fn(
    () =>
      new Promise<Bag>((resolve) => {
        settle = resolve;
      })
  );

  render(<Probe read={read} />);
  await act(async () => {
    settle({ items: [0] });
  });

  // ws-dispatch: 세대가 바뀌면 reset() 뒤 같은 봉투를 receive() 한다.
  await act(async () => {
    liveQueryStore.getState().reset();
    liveQueryStore.getState().receive(wrap(9));
  });
  expect(read).toHaveBeenCalledTimes(2);
  await act(async () => {
    settle({ items: [1] });
  });
  // 새 조회에 이미 9 가 들어 있다고 보는 것이 맞다 — 구독은 reset 뒤에 다시 걸리므로 중복이 없다.
  expect(out()).toBe("1");
});

// ── 시나리오 3: 조건/권한 전환 ──────────────────────────────────────────────
test("enabled 가 꺼져 있으면 조회하지 않고, 켜지면 그때 읽는다", async () => {
  const read = vi.fn(() => Promise.resolve({ items: [0] }));
  const view = render(<Probe read={read} enabled={false} />);
  await flush();
  expect(read).not.toHaveBeenCalled();
  expect(out()).toBe("loading");

  view.rerender(<Probe read={read} enabled />);
  await flush();
  expect(read).toHaveBeenCalledTimes(1);
  expect(out()).toBe("0");
});

test("read 참조가 안정적이면 리렌더로 다시 읽지 않는다", async () => {
  const read = vi.fn(() => Promise.resolve({ items: [0] }));
  const view = render(<Probe read={read} />);
  await flush();
  view.rerender(<Probe read={read} />);
  await flush();
  expect(read).toHaveBeenCalledTimes(1);
});

test("풀린 화면에는 더 이상 쓰지 않는다", async () => {
  const error = vi.spyOn(console, "error").mockImplementation(() => {});
  const view = render(<Probe read={() => Promise.resolve({ items: [0] })} />);
  await flush();
  view.unmount();
  send(1);
  expect(error).not.toHaveBeenCalled();
});
