import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import { INSTRUMENTS, POSITIONS, PRICES } from "./fixtures";
import { InstrumentListV2 } from "./instrument-list.v2";

vi.mock("@/lib/api", () => ({ apiFetch: vi.fn(), apiErrorMessage: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

afterEach(cleanup);

function list(positions = POSITIONS) {
  return render(
    <InstrumentListV2
      instruments={INSTRUMENTS}
      prices={PRICES}
      selectedId={null}
      onSelect={vi.fn()}
      isLoggedIn
      positions={positions}
    />
  );
}

function rowOf(name: string) {
  const row = screen.getByText(name).closest("li");
  expect(row).not.toBeNull();
  return row as HTMLElement;
}

const held = () =>
  fireEvent.click(screen.getByRole("button", { name: "보유 4" }));

/** 전체 목록은 "지금 얼마"로 고르는 자리다. 보유 정보가 끼면 다섯 수가 한 줄에 붙는다. */
test("전체 목록에서는 보유 정보를 쓰지 않는다", () => {
  list();
  const row = rowOf("엔비디아");

  expect(row).toHaveTextContent("미국 · 주식");
  expect(row).toHaveTextContent("+2.50%");
  expect(row).not.toHaveTextContent("6,372,889원");
  // 두 %가 같은 모양이라 머리글이 어느 쪽인지를 말한다.
  expect(screen.getByText("현재가 · 전일대비")).toBeInTheDocument();
});

/** 칸을 더하지 않고 같은 두 칸의 내용을 바꾼다 — 줄 높이가 v1 과 같아야 한다. */
test("보유 필터에서는 같은 칸이 평단·평가금·수익률을 말한다", () => {
  list();
  held();
  const row = rowOf("엔비디아");

  // 평단은 현지 통화, 평가금은 원화 환산이다 (`acc.md`). 평가금은 접어서 낸다 —
  // 이 칸이 길어지면 그만큼을 종목명 칸에서 가져간다 (`compactKrw`).
  expect(row).toHaveTextContent("$118.40");
  expect(row).toHaveTextContent("637만원");
  expect(row).toHaveTextContent("+13.34%");
  // 시세는 이 칸의 주인이 아니게 된다.
  expect(row).not.toHaveTextContent("+2.50%");
  expect(screen.getByText("평가금 · 수익률")).toBeInTheDocument();
});

/** 누를 이유가 없는 칩은 내보내지 않는다 — 미인증도 여기로 걸러진다. */
test("보유가 비면 보유 칩이 없다", () => {
  list([]);
  expect(screen.queryByRole("button", { name: /^보유/ })).toBeNull();
});

/**
 * `/market` 이 실제로 쓰는 목록이 이쪽이므로 정렬도 여기서 고정한다 (`market-screen.tsx`).
 *
 * 값은 서버가 시세에 실어 주는 `tradedAmountKrw` 뿐이다 — 빠지면 목록이 조용히 기본
 * 순서로 돌아가 **정렬이 도는 것처럼 보이면서 아무것도 하지 않는다.** 그래서 필드가 아니라
 * 눈에 보이는 줄 순서로 고정한다.
 *
 * **금액이 문자열로 온다.** 수로 바꾸지 않고 비교하면 `"900000000"` 이
 * `"14300000000"` 보다 크게 서므로, 픽스처의 자릿수를 흩어 그 실수를 잡게 해 두었다.
 */
test("거래대금 정렬은 거래대금 내림차순으로 줄을 세운다", async () => {
  const user = userEvent.setup();
  list();

  await user.click(screen.getByRole("button", { name: /거래대금/ }));

  const expected = [...INSTRUMENTS]
    .sort(
      (a, b) =>
        Number(PRICES[b.instrumentId]?.tradedAmountKrw ?? -1) -
        Number(PRICES[a.instrumentId]?.tradedAmountKrw ?? -1)
    )
    .map((instrument) => instrument.name);

  // 앞의 셋만 본다 — 체결 0 인 종목끼리는 기본 순서로 남고, 그 구간까지 묶으면 픽스처를
  // 한 줄 고칠 때마다 이 단언이 깨진다.
  const rows = screen.getAllByRole("listitem");
  expected.slice(0, 3).forEach((name, index) => {
    expect(rows[index]).toHaveTextContent(name);
  });
});

/**
 * 검색은 **분류·필터와 곱해진다.** 검색이 다른 조건을 무시하고 전체를 뒤지면, `코인` 탭에서
 * 찾은 종목을 눌렀을 때 목록이 말한 갈래와 다른 것이 열린다.
 */
test("검색은 이름으로 좁히고 분류를 벗어나지 않는다", async () => {
  const user = userEvent.setup();
  list();

  await user.type(screen.getByLabelText("종목 검색"), "카카오");
  expect(screen.getByText("카카오")).toBeInTheDocument();
  expect(screen.queryByText("엔비디아")).toBeNull();

  // 코스피 종목이므로 코인 분류에서는 걸리지 않는다.
  await user.click(screen.getByRole("tab", { name: "코인" }));
  expect(screen.queryByText("카카오")).toBeNull();
  expect(screen.getByText("해당하는 종목이 없습니다.")).toBeInTheDocument();
});

/** 대소문자를 구별하면 `qqq` 로 `QQQ` 를 찾지 못한다. 종목명에 라틴 문자가 섞여 있다. */
test("검색은 대소문자를 가리지 않는다", async () => {
  const user = userEvent.setup();
  list();

  await user.type(screen.getByLabelText("종목 검색"), "qqq");
  expect(screen.getByText("Invesco QQQ")).toBeInTheDocument();
});

/**
 * **금액 칸이 이름 칸을 밀어내지 못해야 한다.** 평가금 칸은 `shrink-0` 이라 길어진 만큼을
 * 왼쪽에서 가져간다 — 접지 않으면 `48,251,000,000원` 하나에 360px 에서 종목명이 먼저
 * 잘려, 정작 어느 종목인지가 안 보인다.
 */
test("평가금이 커져도 칸을 넘기지 않게 접는다", () => {
  const huge = POSITIONS.map((position) =>
    position.instrumentId === 3
      ? { ...position, valuationKrw: "48251000000" }
      : position
  );
  list(huge);
  held();
  const row = rowOf("엔비디아");

  expect(row).toHaveTextContent("482.5억원");
  expect(row).not.toHaveTextContent("48,251,000,000원");
});

/** 같은 기준을 다시 누르면 방향만 뒤집힌다 — 내림↔오름 둘뿐이다. */
test("같은 기준을 다시 누르면 방향이 뒤집힌다", async () => {
  const user = userEvent.setup();
  list();

  const first = () => screen.getAllByRole("listitem")[0] as HTMLElement;
  const button = screen.getByRole("button", { name: /거래대금/ });

  await user.click(button);
  const top = first().textContent;
  expect(button).toHaveAttribute("data-direction", "desc");

  await user.click(button);
  expect(button).toHaveAttribute("data-direction", "asc");
  // 내림차순의 첫 줄과 오름차순의 첫 줄이 같으면 방향이 돌지 않은 것이다.
  expect(first().textContent).not.toBe(top);

  // 세 번째 눌러도 해제되지 않는다 — 방향 둘만 오간다.
  await user.click(button);
  expect(button).toHaveAttribute("data-direction", "desc");
  expect(first().textContent).toBe(top);
});

/** 이름만 가나다순이 먼저다 — 등락률·거래대금은 큰 값을 보려고 누른다. */
test("이름은 오름차순으로 시작한다", async () => {
  const user = userEvent.setup();
  list();

  const button = screen.getByRole("button", { name: /이름/ });
  await user.click(button);

  expect(button).toHaveAttribute("data-direction", "asc");
  const names = [...INSTRUMENTS]
    .map((instrument) => instrument.name)
    .sort((a, b) => a.localeCompare(b, "ko"));
  // **끝까지 본다.** 휴장·상장 예정도 이름은 그대로 있는 값이라 가나다순에서 빠지지
  // 않는다 (등락률·거래대금 정렬과 갈리는 지점이다).
  const rows = screen.getAllByRole("listitem");
  names.forEach((name, index) => {
    expect(rows[index]).toHaveTextContent(name);
  });
});

/** 아직 상장 전이라 그날 값이 하나도 없는 종목. */
const UPCOMING = INSTRUMENTS.filter(
  (instrument) => instrument.listingStatus === "UPCOMING"
).map((instrument) => instrument.name);

/** 휴장 — 등락은 없지만 그날 오간 돈은 있다. */
const RESTING = INSTRUMENTS.filter(
  (instrument) => PRICES[instrument.instrumentId]?.marketClosed
).map((instrument) => instrument.name);

/** 목록에 보이는 줄 이름을 위에서부터. */
const shown = () =>
  screen.getAllByRole("listitem").map((row) => row.textContent ?? "");

/** 그 이름들이 전부 바닥 구간에 모여 있는가. */
function atBottom(names: string[]) {
  const rows = shown();
  const bottom = rows.slice(rows.length - names.length);
  return names.every((name) => bottom.some((text) => text.includes(name)));
}

/**
 * 상장 예정은 등락률과 거래대금 둘 다에서 바닥이다 — 그날 값이 하나도 없다.
 *
 * **오름차순이 이 시험의 핵심이다.** 값이 없는 쪽으로 읽히므로, 방향을 뒤집는 순간 맨
 * 위로 올라와 정작 보려던 종목을 밀어낸다.
 */
test("상장 예정은 등락률·거래대금 정렬에서 방향을 뒤집어도 맨 아래에 남는다", async () => {
  const user = userEvent.setup();
  list();
  expect(UPCOMING.length).toBeGreaterThan(0);

  for (const label of [/등락률/, /거래대금/]) {
    const button = screen.getByRole("button", { name: label });
    await user.click(button);
    expect(atBottom(UPCOMING)).toBe(true);
    await user.click(button);
    expect(atBottom(UPCOMING)).toBe(true);
  }
});

/** 휴장은 등락률에서만 바닥이다. 견줄 등락이 없다. */
test("휴장은 등락률 정렬에서 맨 아래에 남는다", async () => {
  const user = userEvent.setup();
  list();
  expect(RESTING.length).toBeGreaterThan(0);

  const button = screen.getByRole("button", { name: /등락률/ });
  await user.click(button);
  expect(atBottom([...RESTING, ...UPCOMING])).toBe(true);
  await user.click(button);
  expect(atBottom([...RESTING, ...UPCOMING])).toBe(true);
});

/**
 * **휴장이어도 거래대금으로는 줄을 선다.** 그날 오간 돈은 휴장 종목에도 있는 값이라,
 * 바닥에 내리면 거래가 많았던 종목이 목록 끝에 숨는다.
 */
test("거래대금 정렬은 휴장을 값대로 세운다", async () => {
  const user = userEvent.setup();
  list();

  await user.click(screen.getByRole("button", { name: /거래대금/ }));

  const expected = INSTRUMENTS.filter(
    (instrument) => instrument.listingStatus !== "UPCOMING"
  )
    .sort(
      (a, b) =>
        Number(PRICES[b.instrumentId]?.tradedAmountKrw ?? -1) -
        Number(PRICES[a.instrumentId]?.tradedAmountKrw ?? -1)
    )
    .map((instrument) => instrument.name);

  const rows = shown();
  expected.slice(0, 3).forEach((name, index) => {
    expect(rows[index]).toContain(name);
  });
});

/**
 * **보유 목록의 등락 칸은 전일 대비가 아니라 수익률이다** (`HEADINGS`). 화면이 수익률을
 * 적어 두고 전일 대비로 줄을 세우면, 정렬한 뒤의 순서가 보이는 숫자와 어긋난다.
 */
test("보유 필터의 등락률 정렬은 수익률로 줄을 세운다", async () => {
  const user = userEvent.setup();
  list();
  held();

  await user.click(screen.getByRole("button", { name: /등락률/ }));

  const expected = [...POSITIONS]
    .sort((a, b) => b.returnRate - a.returnRate)
    .map(
      (position) =>
        INSTRUMENTS.find(
          (instrument) => instrument.instrumentId === position.instrumentId
        )?.name ?? ""
    );

  const rows = shown();
  expected.forEach((name, index) => {
    expect(rows[index]).toContain(name);
  });
});

/**
 * 관심 필터도 같은 규칙으로 선다. **거르는 일과 줄 세우는 일은 서로를 모른다** — 필터가
 * 목록을 줄인 뒤 정렬이 그 위에서 돌아야, 무엇을 걸든 순서 규칙이 하나로 남는다.
 */
test("관심 필터에서도 등락률·거래대금·이름 정렬이 규칙대로 선다", async () => {
  const user = userEvent.setup();
  // 휴장·상장 예정을 섞어 담는다 — 고정 규칙이 여기서도 도는지 봐야 한다.
  const watched = [15, 25, 1, 9];
  render(
    <InstrumentListV2
      instruments={INSTRUMENTS.map((instrument) => ({
        ...instrument,
        watched: watched.includes(instrument.instrumentId),
      }))}
      prices={PRICES}
      selectedId={null}
      onSelect={vi.fn()}
      isLoggedIn
      positions={POSITIONS}
    />
  );
  // 별 버튼의 이름도 `관심` 으로 시작하므로 칩의 이름을 그대로 집는다.
  await user.click(
    screen.getByRole("button", { name: `관심 ${watched.length}` })
  );

  const names = (ids: number[]) =>
    ids.map(
      (id) =>
        INSTRUMENTS.find((instrument) => instrument.instrumentId === id)
          ?.name ?? ""
    );
  const expectOrder = (ids: number[]) => {
    const rows = shown();
    expect(rows).toHaveLength(ids.length);
    names(ids).forEach((name, index) => {
      expect(rows[index]).toContain(name);
    });
  };

  // 등락률 ▼ — 이더리움 6.05 · QQQ 2.0, 그 아래로 휴장(삼성전자)과 상장 예정(코인베이스).
  await user.click(screen.getByRole("button", { name: /등락률/ }));
  expectOrder([25, 1, 15, 9]);

  // 거래대금 ▼ — 76억 · 37억 · 0. **휴장도 값대로 선다.** 상장 예정만 바닥이다.
  await user.click(screen.getByRole("button", { name: /거래대금/ }));
  expectOrder([25, 1, 15, 9]);

  // 거래대금 ▲ — 뒤집으면 0원인 휴장이 맨 위로 온다. 상장 예정은 그대로 바닥이다.
  await user.click(screen.getByRole("button", { name: /거래대금/ }));
  expectOrder([15, 1, 25, 9]);

  // 이름 ▲ — 삼성전자 · 이더리움 · Invesco QQQ · 모더나(가나다 뒤 라틴).
  await user.click(screen.getByRole("button", { name: /이름/ }));
  const alphabetical = [...names(watched)].sort((a, b) =>
    a.localeCompare(b, "ko")
  );
  const rows = shown();
  alphabetical.forEach((name, index) => {
    expect(rows[index]).toContain(name);
  });
});
