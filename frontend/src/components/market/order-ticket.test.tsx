import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import type { OrderPreview } from "@/lib/order-api";

import { OrderTicket } from "./order-ticket";

/**
 * 부족분이 있는 매수 미리보기. **`shortfallExchange` 가 있다는 것은 "부족분이 있다" 는
 * 뜻이지 "환전으로 채울 수 있다" 는 뜻이 아니다** — 보낼 돈이 있는지는 서버가 보지 않는다.
 */
function previewWithShortfall(sendAmount: string): OrderPreview {
  return {
    currency: "USD",
    referencePrice: "100",
    estimatedAmount: "1000",
    appliedFeeRate: 0,
    estimatedFee: "0",
    totalAmount: "1000",
    availableCash: "0",
    shortfall: "1000",
    shortfallExchange: { direction: "KRW_TO_USD", sendAmount },
    feeFree: false,
  };
}

const BUY = { name: /매수 주문/ } as const;
const SELL = { name: /매도 주문/ } as const;

/**
 * 방향 탭을 고른다. 매수·매도는 버튼이 아니라 탭이고, 제출 버튼은 고른 쪽 이름을 쓴다 —
 * 수수료·최대 수량·잠금 사유가 전부 이 선택을 따른다.
 */
async function selectSide(name: "매수" | "매도") {
  await userEvent.click(screen.getByRole("tab", { name }));
}

/** 수량이 0 이면 두 버튼 모두 잠긴다. 잠금의 **이유**를 보려면 수량을 채워야 한다. */
async function typeQuantity(value: string) {
  await userEvent.type(screen.getByLabelText("수량"), value);
}

afterEach(cleanup);

describe("OrderTicket 수량 입력", () => {
  function renderTicket() {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={10_000}
        exchangeableCash={10_000_000}
        holdingQuantity={6}
        isLoggedIn
      />
    );
    return screen.getByLabelText("수량") as HTMLInputElement;
  }

  it("앞자리 0 은 남지 않는다", async () => {
    const input = renderTicket();

    await userEvent.type(input, "000");
    expect(input.value).toBe("");

    await userEvent.type(input, "007");
    expect(input.value).toBe("7");
  });

  /**
   * 쉼표는 **보이는 값에만** 있다. 지운 글자 자리에 다시 들어오는 것이 아니라, 상태가
   * 들고 있는 숫자를 그릴 때 끊는 것이라 `1a2-3.5` 도 `1,235` 로 선다.
   */
  it("숫자가 아닌 글자는 들어가지 않고, 남은 숫자는 천 단위로 끊는다", async () => {
    const input = renderTicket();

    await userEvent.type(input, "1a2-3.5");
    expect(input.value).toBe("1,235");
  });

  /** 쉼표를 직접 쳐도 같은 자리로 돌아온다 — 되돌아올 때 숫자가 아닌 글자는 지워진다. */
  it("쉼표를 쳐 넣어도 수량은 흔들리지 않는다", async () => {
    const input = renderTicket();

    await userEvent.type(input, "2,449,073");
    expect(input.value).toBe("2,449,073");
  });

  it("빼기 버튼이 0 을 남기지 않는다", async () => {
    const input = renderTicket();

    await userEvent.type(input, "1");
    await userEvent.click(screen.getByRole("button", { name: "1주 빼기" }));
    expect(input.value).toBe("");
  });
});

/**
 * 거래 구간이 아닐 때. 서버가 어차피 409 로 거절하므로(`PhaseGate.requireTrading`) 화면이
 * 먼저 막아야 하고, **무엇보다 왜 막혔는지 말해야 한다** — 이유 없이 죽은 버튼은 고장으로
 * 읽힌다 (QA U-ERR-04).
 */
describe("OrderTicket 거래 시간", () => {
  it("거래 시간이 아니면 매수를 잠그고 그 까닭을 말한다", async () => {
    render(
      <OrderTicket
        lastPrice={10000}
        currency="KRW"
        priceScale={0}
        cash={10_000_000}
        isLoggedIn
        tradingOpen={false}
      />
    );

    await typeQuantity("1");
    expect(screen.getByRole("button", BUY)).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent(
      "지금은 거래 시간이 아닙니다"
    );
  });

  it("거래 시간이 아니면 예약 주문도 함께 잠근다", async () => {
    render(
      <OrderTicket
        lastPrice={10000}
        currency="KRW"
        priceScale={0}
        cash={10_000_000}
        holdingQuantity={10}
        isLoggedIn
        tradingOpen={false}
      />
    );

    // 휴장(`marketOpen`)과 다른 점이다 — 그쪽은 예약이 열려 있다.
    await userEvent.click(screen.getByRole("tab", { name: "예약 주문" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "지금은 거래 시간이 아닙니다"
    );
  });
});

describe("OrderTicket 매수 잠금", () => {
  it("환전으로 채울 수 있으면 매수가 열려 있다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={0}
        // 보낼 원화가 넉넉하다.
        exchangeableCash={10_000_000}
        isLoggedIn
        preview={previewWithShortfall("1400000")}
      />
    );

    await typeQuantity("10");
    expect(screen.getByRole("button", BUY)).toBeEnabled();
  });

  it("환전해도 모자라면 매수를 잠그고 얼마가 모자란지 말한다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={0}
        // 보낼 원화가 400,000원 모자란다.
        exchangeableCash={1_000_000}
        isLoggedIn
        preview={previewWithShortfall("1400000")}
      />
    );

    await typeQuantity("10");
    expect(screen.getByRole("button", BUY)).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("$1,000 부족");
  });

  it("국내 종목의 부족액은 원화로 말한다", async () => {
    render(
      <OrderTicket
        lastPrice={55_800}
        currency="KRW"
        priceScale={0}
        cash={47_291_253}
        exchangeableCash={0}
        usdKrwRate={1460.8}
        isLoggedIn
        preview={{
          currency: "KRW",
          referencePrice: "55800",
          estimatedAmount: "47318400",
          appliedFeeRate: 0,
          estimatedFee: "0",
          totalAmount: "47318400",
          availableCash: "47291253",
          shortfall: "27147",
          shortfallExchange: {
            direction: "USD_TO_KRW",
            sendAmount: "18.60",
          },
          feeFree: false,
        }}
      />
    );

    await typeQuantity("848");
    expect(screen.getByRole("status")).toHaveTextContent("55,800원 부족");
    expect(screen.getByRole("status")).not.toHaveTextContent("$");
  });

  it("부족액은 반대 통화로 환전 가능한 금액까지 뺀 나머지만 말한다", async () => {
    render(
      <OrderTicket
        lastPrice={14_616}
        currency="KRW"
        priceScale={0}
        cash={35_000_000}
        exchangeableCash={7_907.34}
        usdKrwRate={1460.8}
        isLoggedIn
        preview={{
          currency: "KRW",
          referencePrice: "14616",
          estimatedAmount: "46551960",
          appliedFeeRate: 0,
          estimatedFee: "0",
          totalAmount: "46551960",
          availableCash: "35000000",
          shortfall: "11551960",
          shortfallExchange: {
            direction: "USD_TO_KRW",
            sendAmount: "7907.35",
          },
          feeFree: false,
        }}
      />
    );

    await typeQuantity("3185");
    expect(screen.getByRole("status")).toHaveTextContent("14,616원 부족");
    expect(screen.getByRole("status")).not.toHaveTextContent("11,551,960원");
  });

  it("부족액은 화면에 표시한 최대 수량과 입력 수량의 주문 금액 차이다", async () => {
    render(
      <OrderTicket
        lastPrice={0.0029}
        currency="USD"
        priceScale={4}
        cash={35_918.5}
        exchangeableCash={0}
        isLoggedIn
      />
    );

    expect(screen.getByText("최대 12,385,691주")).toBeInTheDocument();
    await typeQuantity("12442353");

    expect(screen.getByRole("status")).toHaveTextContent("$164.32 부족");
  });

  it("보유보다 많이 팔려 하면 매도를 잠그고 그 까닭을 말한다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={10_000}
        exchangeableCash={10_000_000}
        holdingQuantity={6}
        isLoggedIn
      />
    );

    await selectSide("매도");
    await typeQuantity("7");
    expect(screen.getByRole("button", SELL)).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("보유 수량 부족");
    // 몇 주를 가졌는지는 수량 칸 위가 말한다 — 잠금 문구가 되풀이하지 않는다.
    // 매도의 한도는 곧 보유 수량이라 그 한 줄이 둘을 겸한다.
    expect(screen.getByText("보유 6주")).toBeInTheDocument();
  });

  /**
   * 매수의 한도는 가진 돈이다. 가진 주식보다 많이 적는 것이 정상인 자리라, 여기서
   * "보유 수량 부족" 이 뜨면 **살 수 있는 수량을 적어 두고 못 산다고 읽는다.**
   */
  it("매수에는 보유 수량 부족을 말하지 않는다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1_000_000}
        exchangeableCash={10_000_000}
        holdingQuantity={6}
        isLoggedIn
      />
    );

    await typeQuantity("500");

    expect(screen.getByRole("status")).toHaveTextContent("");
    expect(screen.getByRole("button", BUY)).toBeEnabled();
  });

  it("보유 수량 안에서는 매도가 열려 있고 문구도 없다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={10_000}
        exchangeableCash={10_000_000}
        holdingQuantity={6}
        isLoggedIn
      />
    );

    await selectSide("매도");
    await typeQuantity("6");
    expect(screen.getByRole("button", SELL)).toBeEnabled();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });

  it("미리보기를 못 받았으면 어림치로 잠그지 않는다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={0}
        exchangeableCash={0}
        isLoggedIn
        preview={null}
      />
    );

    await typeQuantity("10");
    expect(screen.getByRole("button", BUY)).toBeEnabled();
  });

  it("부족분이 없으면 잠그지도, 문구를 내지도 않는다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={10_000}
        exchangeableCash={0}
        isLoggedIn
        preview={{
          ...previewWithShortfall("0"),
          availableCash: "10000",
          shortfall: "0",
          shortfallExchange: null,
        }}
      />
    );

    await typeQuantity("10");
    expect(screen.getByRole("button", BUY)).toBeEnabled();
    // 자리는 늘 서 있다 (버튼이 밀리지 않도록). 말할 것이 없으면 비어 있을 뿐이다.
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });
});

describe("OrderTicket 수량 한도", () => {
  function renderMax(props = {}) {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1050}
        holdingQuantity={8}
        isLoggedIn
        {...props}
      />
    );
  }

  /** 매수의 한도는 가진 돈이다. 매수 수수료가 없어 나눗셈 하나로 끝난다 (`OrderQuote`). */
  it("매수는 가진 돈으로 살 수 있는 수량을 한도로 말한다", () => {
    renderMax();
    // 가진 것과 낼 수 있는 것을 한 줄에 함께 적는다. 단위는 뒤에 한 번만 붙는다 —
    // 둘 다 주식 수이고, 두 번 붙이면 일곱 자리 수 둘이 칸을 넘는다.
    expect(screen.getByText("보유 8 · 최대 10주")).toBeInTheDocument();
  });

  /** 한 주도 없으면 적을 것이 하나다. */
  it("보유가 없으면 한도만 적는다", () => {
    renderMax({ holdingQuantity: 0 });
    expect(screen.getByText("최대 10주")).toBeInTheDocument();
  });

  /** 매도의 한도는 가진 주식이다. */
  it("매도로 바꾸면 한도가 보유 수량이 된다", async () => {
    renderMax();
    await selectSide("매도");
    // 매도에서는 `보유` 로 적는다 — 한도와 보유가 같은 수라 둘을 나란히 두면 같은 것을
    // 두 번 말하고, 일곱 자리 수 둘은 칸을 넘어 잘린다.
    expect(screen.getByText("보유 8주")).toBeInTheDocument();
    // `· 최대` 가 붙지 않는다 — 비율 칩의 `최대` 버튼과 헷갈리지 않게 구분자까지 본다.
    expect(screen.queryByText(/· 최대/)).not.toBeInTheDocument();
  });

  it("최대를 누르면 한도가 그대로 들어간다", async () => {
    renderMax();
    await userEvent.click(screen.getByRole("button", { name: "최대" }));
    expect(screen.getByLabelText("수량")).toHaveValue("10");
  });

  /**
   * 한도는 틱마다 움직인다. `최대` 로 채운 수량이 옛 한도에 머물면 그 주문은 서버에서
   * `부족` 으로 막힌다 — 사용자가 본 것은 "최대" 였는데 낼 수 없는 수량이 된다.
   */
  it("최대로 채운 수량은 한도가 줄면 따라 줄어든다", async () => {
    const { rerender } = render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1050}
        holdingQuantity={0}
        isLoggedIn
      />
    );

    await userEvent.click(screen.getByRole("button", { name: "최대" }));
    expect(screen.getByLabelText("수량")).toHaveValue("10");

    // 시세가 올랐다. 같은 돈으로 살 수 있는 수량이 줄어든다.
    rerender(
      <OrderTicket
        lastPrice={150}
        currency="USD"
        priceScale={2}
        cash={1050}
        holdingQuantity={0}
        isLoggedIn
      />
    );

    expect(screen.getByLabelText("수량")).toHaveValue("7");
  });

  it("소액 코인도 종가 틱이 바뀌면 최대 표시와 최대 입력값이 함께 바뀐다", async () => {
    const props = {
      currency: "USD" as const,
      priceScale: 4,
      cash: 35_918.5,
      exchangeableCash: 0,
      holdingQuantity: 0,
      isLoggedIn: true,
    };
    const { rerender } = render(<OrderTicket {...props} lastPrice={0.0029} />);

    expect(screen.getByText("최대 12,385,691주")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "최대" }));
    expect(screen.getByLabelText("수량")).toHaveValue("12,385,691");

    rerender(<OrderTicket {...props} lastPrice={0.003} />);

    expect(screen.getByText("최대 11,972,835주")).toBeInTheDocument();
    expect(screen.getByLabelText("수량")).toHaveValue("11,972,835");
  });

  /**
   * 반대 통화 잔고는 계좌 스냅샷과 서버의 실제 주문 가능액이 잠깐 어긋날 수 있다. 서버가
   * `최대` 수량에 부족분을 답했다면 그 차이를 한 주 가격으로 환산해 한도를 낮춘다.
   */
  it("서버가 최대 수량의 부족분을 답하면 체결 가능한 수량으로 낮춘다", async () => {
    const props = {
      lastPrice: 19.18,
      currency: "USD" as const,
      priceScale: 2,
      cash: 0,
      exchangeableCash: 47_291_253,
      usdKrwRate: 1460.8,
      holdingQuantity: 0,
      isLoggedIn: true,
    };
    const { rerender } = render(<OrderTicket {...props} />);

    await userEvent.click(screen.getByRole("button", { name: "최대" }));
    expect(screen.getByLabelText("수량")).toHaveValue("1,687");

    rerender(
      <OrderTicket
        {...props}
        preview={{
          currency: "USD",
          referencePrice: "19.18",
          estimatedAmount: "32360.66",
          appliedFeeRate: 0,
          estimatedFee: "0.00",
          totalAmount: "32360.66",
          availableCash: "0.00",
          shortfall: "32360.66",
          shortfallExchange: {
            direction: "KRW_TO_USD",
            sendAmount: "47295925",
          },
          feeFree: false,
        }}
      />
    );

    expect(screen.getByText("최대 1,686주")).toBeInTheDocument();
    expect(screen.getByLabelText("수량")).toHaveValue("1,686");
  });

  it("서버 기준가가 더 낮으면 실제로 살 수 있는 수량까지 최대를 올린다", async () => {
    const { rerender } = render(
      <OrderTicket
        lastPrice={0.3118}
        currency="USD"
        priceScale={4}
        cash={0}
        exchangeableCash={47_291_253}
        usdKrwRate={1460.8}
        isLoggedIn
      />
    );

    expect(screen.getByText("최대 103,775주")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "최대" }));

    rerender(
      <OrderTicket
        lastPrice={0.3118}
        currency="USD"
        priceScale={4}
        cash={0}
        exchangeableCash={47_291_253}
        usdKrwRate={1460.8}
        isLoggedIn
        preview={{
          currency: "USD",
          referencePrice: "0.3117",
          estimatedAmount: "32348.02",
          appliedFeeRate: 0,
          estimatedFee: "0.00",
          totalAmount: "32348.02",
          availableCash: "0.00",
          shortfall: "32348.02",
          shortfallExchange: {
            direction: "KRW_TO_USD",
            sendAmount: "47277547",
          },
          feeFree: false,
        }}
      />
    );

    expect(screen.getByText("최대 103,809주")).toBeInTheDocument();
    expect(screen.getByLabelText("수량")).toHaveValue("103,809");
  });

  it("1센트보다 싼 코인은 센트 반올림 뒤 실제로 살 수 있는 마지막 주까지 센다", async () => {
    render(
      <OrderTicket
        lastPrice={0.0027}
        currency="USD"
        priceScale={4}
        cash={36_011.62}
        exchangeableCash={0}
        isLoggedIn
      />
    );

    expect(screen.getByText("최대 13,337,638주")).toBeInTheDocument();

    await typeQuantity("13337638");
    expect(screen.getByRole("button", BUY)).toBeEnabled();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();

    await userEvent.clear(screen.getByLabelText("수량"));
    await typeQuantity("13337639");
    expect(screen.getByRole("button", BUY)).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("$0.01 부족");
  });

  /** 손으로 고친 수량은 사용자의 것이다. 한도가 움직여도 건드리지 않는다. */
  it("손으로 적은 수량은 한도를 따라가지 않는다", async () => {
    const { rerender } = render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1050}
        holdingQuantity={0}
        isLoggedIn
      />
    );

    await typeQuantity("9");

    rerender(
      <OrderTicket
        lastPrice={150}
        currency="USD"
        priceScale={2}
        cash={1050}
        holdingQuantity={0}
        isLoggedIn
      />
    );

    expect(screen.getByLabelText("수량")).toHaveValue("9");
  });

  it("비율 칩은 한도에서 잘라 쓴다", async () => {
    renderMax();
    await userEvent.click(screen.getByRole("button", { name: "25%" }));
    // 10 주의 25% = 2.5 → 2 주. 살 수 없는 수량을 제안하지 않는다.
    expect(screen.getByLabelText("수량")).toHaveValue("2");
  });

  /** 환전까지 끌어 쓴다. 환전 수수료 0.05% 를 떼고 내림해야 실제로 체결된다. */
  it("반대쪽 통화를 환전한 몫까지 한도에 넣는다", () => {
    renderMax({ cash: 100, exchangeableCash: 1_190_000, usdKrwRate: 1190 });
    // 1,190,000원 − 0.05% = 1,189,405원 → $999.50. 현금 $100 을 더해 $1,099.50.
    expect(screen.getByText("보유 8 · 최대 10주")).toBeInTheDocument();
  });

  it("환율을 모르면 환전 몫 없이 결제 통화 잔고만 본다", () => {
    renderMax({ cash: 300, exchangeableCash: 1_190_000 });
    expect(screen.getByText("보유 8 · 최대 3주")).toBeInTheDocument();
  });

  /** 잔고가 옛 값이면 그것으로 계산한 한도도 옛 값이다. 제출 버튼과 같은 이유로 잠근다. */
  it("서버와 어긋나 있으면 수량 제안을 잠근다", () => {
    renderMax({ synced: false });
    expect(screen.getByRole("button", { name: "최대" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "25%" })).toBeDisabled();
  });
});

describe("OrderTicket 수수료 표시", () => {
  function renderFee() {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={10_000}
        holdingQuantity={8}
        isLoggedIn
      />
    );
  }

  /** 매수는 수수료가 없다 (`OrderQuote`). 없는 것은 적지 않는다. */
  it("매수에는 수수료 줄이 아예 없다", async () => {
    renderFee();
    await typeQuantity("2");

    expect(screen.queryByText(/예상 수수료/)).not.toBeInTheDocument();
    // 낼 돈은 곱셈 그대로다 — 수수료가 붙지 않는다.
    expect(screen.getByText("$200")).toBeInTheDocument();
  });

  /**
   * 수수료는 **적지 않는다.** 받을 돈에서 이미 빠져 있으므로(2 주 × $100 의 0.15% =
   * $0.30 → $199.70), 요율까지 나란히 두면 어느 쪽이 들어오는 돈인지 한 번 더 셈해야
   * 한다. 요율은 도구줄의 배지가 늘 말한다.
   */
  it("매도는 수수료를 뺀 금액만 적는다", async () => {
    renderFee();
    await selectSide("매도");
    await typeQuantity("2");

    expect(screen.getByText("$199.70")).toBeInTheDocument();
    expect(screen.queryByText(/수수료 \$/)).not.toBeInTheDocument();
  });

  /**
   * 끝자리가 전부 0 인 소수점은 읽을 것이 없다. **일부만 0 인 끝자리는 남는다** —
   * `$199.70` 의 0 은 센트 자릿수를 말하므로 지우면 다른 금액이 된다.
   */
  it("달러 소수점이 전부 0 이면 적지 않는다", async () => {
    renderFee();

    expect(screen.getByText("$100")).toBeInTheDocument();
    expect(screen.queryByText("$100.00")).not.toBeInTheDocument();

    await selectSide("매도");
    await typeQuantity("2");
    expect(screen.getByText("$199.70")).toBeInTheDocument();
  });
});

describe("OrderTicket 주문 접수", () => {
  /**
   * 낸 수량이 칸에 남아 있으면 같은 주문을 한 번 더 낸 것으로 오해하기 쉽고, 체결된 뒤의
   * 한도·보유는 이미 달라져 있다.
   */
  it("주문이 접수되면 수량 칸을 비운다", async () => {
    const { rerender } = render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1_000_000}
        holdingQuantity={0}
        isLoggedIn
        placedRevision={0}
      />
    );

    await typeQuantity("170");
    expect(screen.getByLabelText("수량")).toHaveValue("170");

    rerender(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1_000_000}
        holdingQuantity={0}
        isLoggedIn
        placedRevision={1}
      />
    );

    expect(screen.getByLabelText("수량")).toHaveValue("");
  });

  /** 실패한 주문은 고쳐서 다시 낼 값이다 — 부르는 쪽이 올리지 않으면 그대로 남는다. */
  it("접수되지 않으면 적어 둔 수량이 남는다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={1_000_000}
        holdingQuantity={0}
        isLoggedIn
        placedRevision={3}
      />
    );

    await typeQuantity("170");
    expect(screen.getByLabelText("수량")).toHaveValue("170");
  });
});

describe("OrderTicket 방향 전환", () => {
  /**
   * 매수의 수량은 가진 돈에서 나온 값이다. 매도로 넘어가면 한도가 보유 주식으로 바뀌므로
   * 그 수량은 뜻을 잃는다 — 들고 가면 대개 "보유 수량 부족" 인 채로 서 있다.
   */
  it("방향을 바꾸면 적어 둔 수량을 비운다", async () => {
    render(
      <OrderTicket
        lastPrice={100}
        currency="USD"
        priceScale={2}
        cash={100_000}
        holdingQuantity={6}
        isLoggedIn
      />
    );

    await typeQuantity("170");
    expect(screen.getByLabelText("수량")).toHaveValue("170");

    await selectSide("매도");
    expect(screen.getByLabelText("수량")).toHaveValue("");

    // 되돌아와도 비어 있다. 한 번 뜻을 잃은 값을 되살릴 이유가 없다.
    await selectSide("매수");
    expect(screen.getByLabelText("수량")).toHaveValue("");
  });
});
