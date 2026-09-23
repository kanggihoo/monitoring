import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { InstrumentMeta, PriceEntry } from "@/lib/market-types";

import { MoversBoard } from "./movers-board";

function instrument(instrumentId: number, name: string): InstrumentMeta {
  return {
    instrumentId,
    name,
    category: "KRX",
    assetType: "STOCK",
    currency: "KRW",
    priceScale: 0,
    watched: false,
    listingStatus: "LISTED",
    listedGameDate: null,
    isNew: false,
    sector: "정보기술",
  };
}

function price(instrumentId: number, changeRate: number | null): PriceEntry {
  return {
    instrumentId,
    close: "1000",
    closeKrw: "1000",
    prevClose: "1000",
    changeRate,
    marketClosed: false,
    tradedAmountKrw: "0",
  };
}

/** 등락률만 주면 종목과 시세를 함께 만든다. 이름은 등락률을 그대로 쓴다. */
function board(rates: (number | null)[]) {
  const instruments = rates.map((rate, index) =>
    instrument(index + 1, `종목${index + 1}`)
  );
  const prices = Object.fromEntries(
    rates.map((rate, index) => [index + 1, price(index + 1, rate)])
  );
  return { instruments, prices };
}

/** `상승` · `하락` 열에 실제로 그려진 종목 이름. 빈 줄(`—`)은 이름이 없으므로 빠진다. */
function namesIn(title: string) {
  const column = screen.getByText(title).parentElement!;
  return within(column)
    .queryAllByText(/^종목\d+$/)
    .map((node) => node.textContent);
}

afterEach(cleanup);

describe("MoversBoard", () => {
  it("오른 종목이 모자라도 하락·보합 종목으로 상승 칸을 채우지 않는다", () => {
    // 오른 것은 둘뿐이고 나머지는 보합·하락이다.
    render(<MoversBoard {...board([4.2, 1.43, 0, -0.43, -3.01])} />);

    expect(namesIn("상승")).toEqual(["종목1", "종목2"]);
  });

  it("보합은 어느 칸에도 넣지 않고, 빈 열은 그 사실을 말한다", () => {
    render(<MoversBoard {...board([0, 0, 0])} />);

    expect(namesIn("상승")).toEqual([]);
    expect(namesIn("하락")).toEqual([]);
    // 열 제목만 남기지 않는다 — 등락이 전부 보합인 구간이 그렇다.
    expect(screen.getByText("오른 종목이 없습니다.")).toBeInTheDocument();
    expect(screen.getByText("내린 종목이 없습니다.")).toBeInTheDocument();
  });

  it("오른 종목이 모자란 자리는 순위 번호를 남긴 채 비운다", () => {
    render(<MoversBoard {...board([4.2, 1.4, -1, -2, -3, -4, -5])} />);

    const up = within(screen.getByText("상승").parentElement!);
    expect(up.queryAllByText("—").length).toBeGreaterThan(0);
    // 몇 번째 자리가 비었는지가 그 줄이 말하는 것이다.
    expect(up.queryAllByText(/^[1-5]$/).map((n) => n.textContent)).toEqual([
      "1",
      "2",
      "3",
      "4",
      "5",
    ]);
  });

  it("하락 칸은 가장 많이 내린 종목부터 세운다", () => {
    render(<MoversBoard {...board([1.5, -0.5, -3.2, -1.1])} />);

    expect(namesIn("하락")).toEqual(["종목3", "종목4", "종목2"]);
  });

  it("양쪽 모두 다섯 종을 넘기지 않는다", () => {
    render(
      <MoversBoard {...board([6, 5, 4, 3, 2, 1, -1, -2, -3, -4, -5, -6])} />
    );

    expect(namesIn("상승")).toHaveLength(5);
    expect(namesIn("하락")).toHaveLength(5);
  });

  it("휴장 종목(등락률 null)은 빼고 센다", () => {
    render(<MoversBoard {...board([2.5, null, -1.2])} />);

    expect(namesIn("상승")).toEqual(["종목1"]);
    expect(namesIn("하락")).toEqual(["종목3"]);
  });

  it("종목을 누르면 해당 종목의 마켓 주소(/?instrumentId=...)로 연결된다", () => {
    render(<MoversBoard {...board([3.5, -2.1])} />);

    const link = screen.getByRole("link", { name: /종목1/ });
    expect(link).toHaveAttribute("href", "/?instrumentId=1");
  });
});
