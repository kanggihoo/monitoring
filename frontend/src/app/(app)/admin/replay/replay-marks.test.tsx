import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ReplayClock, ReplayMark } from "@/lib/replay";

import { ReplayMarks } from "./replay-marks";

const clock = (over: Partial<ReplayClock> = {}): ReplayClock => ({
  seasonId: 1,
  seasonDay: 1,
  offsetSec: 5000,
  anchorOffsetSec: 5000,
  anchorAt: "2026-09-21T02:05:00.000Z",
  speed: 0,
  phase: "TRADING",
  serverTime: "2026-09-21T02:05:00.000Z",
  tickSeq: 30,
  // 시즌 첫 Tick. `tickSeq` 는 시즌별 순번이 아니라 전역 식별자라 1 이라고 가정하지 않는다
  // (`replay.ts`) — 지점에서 물러설 수 있는 최대 Tick 수를 이 값에서 센다.
  firstTickSeq: 1,
  gameDate: "2020-01-02",
  gameMonth: "2020-01",
  nextTickAt: null,
  phaseEndsAt: null,
  feeFree: false,
  feeFreeEndsAt: null,
  leaderboardBlackout: false,
  replayEpoch: 0,
  scheduledStartAt: null,
  warping: false,
  ...over,
});

const mark = (over: Partial<ReplayMark> & { key: string }): ReplayMark => ({
  kind: "BOUNDARY",
  label: over.key,
  seasonDay: 1,
  offsetSec: 0,
  tickSeq: null,
  passed: false,
  ...over,
});

/** 시즌 첫 지점 · 지나간 경계 · 월 · 앞의 공모 · 앞의 경계 · 다음 일차. */
const BRIEFING = mark({
  key: "day1.briefing",
  label: "1일차 브리핑",
  offsetSec: -300,
  passed: true,
});
const MONTH = mark({
  key: "month.2020-01",
  kind: "MONTH",
  label: "2020-01 첫 틱",
  offsetSec: 100,
  tickSeq: 12,
  passed: true,
});
const IPO_CLOSE = mark({
  key: "ipo1.close",
  kind: "IPO",
  label: "코코아게임즈 청약 마감",
  offsetSec: 9000,
  tickSeq: 50,
});
const DAY1_CLOSE = mark({
  key: "day1.close",
  label: "1일차 폐장",
  offsetSec: 20100,
});
const DAY2_OPEN = mark({
  key: "day2.open",
  label: "2일차 개장",
  seasonDay: 2,
});
const MARKS = [BRIEFING, MONTH, IPO_CLOSE, DAY1_CLOSE, DAY2_OPEN];

function renderMarks(over: Partial<ReplayClock> = {}) {
  const onSeek = vi.fn();
  render(
    <ReplayMarks
      marks={MARKS}
      clock={clock(over)}
      position={5000}
      pending={false}
      onSeek={onSeek}
    />
  );
  return onSeek;
}

const rowOf = (label: string) =>
  screen.getByRole("row", { name: new RegExp(label) });
const goIn = (label: string) =>
  within(rowOf(label)).getByRole("button", { name: /여기로/ });

afterEach(cleanup);

describe("이동 지점 표", () => {
  it("월 첫 틱은 기본으로 접어 두고 칩으로 켠다", async () => {
    renderMarks();

    // 시즌당 80개라 기본으로 보이면 목록이 그것으로 덮인다.
    expect(screen.queryByText("2020-01 첫 틱")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /월 첫 틱/ }));
    expect(screen.getByText("2020-01 첫 틱")).toBeInTheDocument();
  });

  it("현재 일차만 펼치고 나머지는 접는다", async () => {
    renderMarks();

    expect(screen.getByText("1일차 폐장")).toBeInTheDocument();
    expect(screen.queryByText("2일차 개장")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /2일차/ }));
    expect(screen.getByText("2일차 개장")).toBeInTheDocument();
  });

  it("지나간 항목을 숨길 수 있다", async () => {
    renderMarks();

    expect(screen.getByText("1일차 브리핑")).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: "지나간 항목 숨기기" })
    );
    expect(screen.queryByText("1일차 브리핑")).not.toBeInTheDocument();
  });

  it("시간 경계는 Tick 번호 없이, 가격 Tick 은 번호와 함께 낸다", () => {
    renderMarks();

    expect(rowOf("1일차 폐장")).toHaveTextContent("—");
    expect(rowOf("코코아게임즈 청약 마감")).toHaveTextContent("50");
  });

  it("현재 위치에 표시선을 놓고 지나간 것을 흐리게 한다", () => {
    renderMarks();

    expect(screen.getByText(/현재 위치 12:28:20/)).toBeInTheDocument();
    expect(rowOf("1일차 브리핑")).toHaveClass("opacity-45");
    expect(rowOf("코코아게임즈 청약 마감")).not.toHaveClass("opacity-45");
  });

  it("앞으로 이동은 처리할 Tick 수와 이벤트 수를 미리 보여 준다", () => {
    renderMarks();

    // 50 번 지점에 기본 3 Tick 전으로 착지한다: 30 → 47 이라 17 건이다.
    expect(rowOf("코코아게임즈 청약 마감")).toHaveTextContent(
      "틱 17건 · 이벤트 1건"
    );
    // 경계는 그 앞의 마지막 Tick 지점까지로 어림한다.
    expect(rowOf("1일차 폐장")).toHaveTextContent("틱 약 20건 · 이벤트 2건");
  });

  it("앞선 Tick 이 없는 지점은 Tick 수 입력을 막는다", () => {
    renderMarks();

    // 1일차 브리핑 앞에는 Tick 이 하나도 없다. 서버가 400 으로 거절할 요청을 만들지 않는다.
    expect(
      within(rowOf("1일차 브리핑")).getByLabelText(/앞 Tick 수/)
    ).toBeDisabled();
    expect(
      within(rowOf("코코아게임즈 청약 마감")).getByLabelText(/앞 Tick 수/)
    ).toBeEnabled();
  });

  it("앞으로 이동은 확인 없이 바로 요청한다", async () => {
    const onSeek = renderMarks();

    await userEvent.click(goIn("코코아게임즈 청약 마감"));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(onSeek).toHaveBeenCalledWith(IPO_CLOSE, 3);
  });

  it("요청마다 몇 Tick 앞에 설지를 정한다", async () => {
    const onSeek = renderMarks();
    const input = within(rowOf("코코아게임즈 청약 마감")).getByLabelText(
      /앞 Tick 수/
    );

    await userEvent.clear(input);
    await userEvent.type(input, "1");
    await userEvent.click(goIn("코코아게임즈 청약 마감"));

    expect(onSeek).toHaveBeenCalledWith(IPO_CLOSE, 1);
  });

  it("뒤로 이동에만 확인 대화상자를 띄우고 취소는 요청을 보내지 않는다", async () => {
    const onSeek = renderMarks();

    await userEvent.click(goIn("1일차 브리핑"));

    const dialog = screen.getByRole("alertdialog");
    expect(
      within(dialog).getByText("이 지점으로 되돌리면 시즌이 초기화됩니다")
    ).toBeInTheDocument();
    expect(dialog).toHaveTextContent(/지워지는 것/);
    expect(dialog).toHaveTextContent(/남는 것/);

    await userEvent.click(
      within(dialog).getByRole("button", { name: "그대로 두기" })
    );
    expect(onSeek).not.toHaveBeenCalled();
  });

  it("시즌 처음으로 버튼이 1일차 브리핑 확인을 띄운다", async () => {
    const onSeek = renderMarks();

    await userEvent.click(
      screen.getByRole("button", { name: /시즌 처음으로/ })
    );
    const dialog = screen.getByRole("alertdialog");
    expect(dialog).toHaveTextContent("1일차 브리핑");

    await userEvent.click(
      within(dialog).getByRole("button", { name: /초기화하고 이동/ })
    );
    // 앞선 Tick 이 없으므로 그 지점 자체로 간다.
    expect(onSeek).toHaveBeenCalledWith(BRIEFING, 0);
  });

  it("앞으로와 뒤로의 차이를 상시 문구로 남긴다", () => {
    renderMarks();

    expect(screen.getByText(/앞으로 = /)).toHaveTextContent(/계좌는 유지된다/);
    expect(screen.getByText(/뒤로 = /)).toHaveTextContent(/되감기가 아니다/);
  });
});
