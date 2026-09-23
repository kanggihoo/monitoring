import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { OrderTicket } from "./order-ticket";

const meta = {
  title: "Screens/Market/OrderTicket",
  component: OrderTicket,
  tags: ["autodocs"],
  parameters: { layout: "padded" },
  args: {
    lastPrice: 58.48,
    currency: "USD",
    priceScale: 2,
    cash: 8_940,
    holdingQuantity: 300,
    // 기본값은 미인증이라 주문 버튼 자리가 로그인 안내로 바뀐다. 이 스토리들이 보여 주려는
    // 것은 버튼의 상태이므로 로그인한 쪽을 기본으로 둔다.
    isLoggedIn: true,
  },
} satisfies Meta<typeof OrderTicket>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Market: Story = {};

/** 12:00~12:10 무료 타임. 배지가 남은 시간을 센다. */
export const FeeFree: Story = { args: { feeRate: 0, feeFreeSeconds: 372 } };

/** 휴장 중에는 시장가 탭이 잠기고 예약만 받는다 (기획 3장 부재 중 플레이). */
export const MarketClosed: Story = { args: { marketOpen: false } };

/**
 * 거래 구간이 아니다 (브리핑·정산·열람 전용). **휴장과 다르다** — 휴장은 예약이 열려
 * 있지만 이쪽은 서버가 둘 다 거절하므로 예약까지 잠그고, 왜 막혔는지 한 줄로 말한다.
 */
export const OutsideTrading: Story = {
  args: { isLoggedIn: true, marketOpen: false, tradingOpen: false },
};

/**
 * 연결이 끊겼거나 재동기화가 끝나지 않았다. **휴장과 다르다** — 휴장은 정상 상태라 예약이
 * 열려 있지만, 이쪽은 화면에 남은 시세·잔고를 믿을 수 없어 예약까지 함께 막는다.
 */
export const Desynced: Story = { args: { isLoggedIn: true, synced: false } };

export const NoCash: Story = { args: { cash: 0, holdingQuantity: 0 } };

/**
 * 서버가 답한 미리보기가 붙은 모습. 금액에 `≈` 가 없다 — 어림치가 아니라 확정된 값이다.
 * 매수 수수료는 계약상 언제나 0 이라 수수료 문구가 나오지 않는다 (`ord.md`).
 */
export const WithPreview: Story = {
  args: {
    preview: {
      currency: "USD",
      referencePrice: "58.48",
      estimatedAmount: "2924.00",
      appliedFeeRate: 0,
      estimatedFee: "0.00",
      totalAmount: "2924.00",
      availableCash: "8940.00",
      shortfall: "0",
      shortfallExchange: null,
      feeFree: false,
    },
  },
};

/**
 * 달러가 모자라지만 **원화로 채울 수 있는** 매수. 매수 버튼이 잠기지 않는다 — 눌러서
 * 알게 되는 편이 낫다. 부족분 안내는 이 패널이 아니라 누른 뒤 뜨는 `ShortfallDialog` 가 한다.
 *
 * 수량을 입력해야 버튼이 열린다.
 */
export const Shortfall: Story = {
  args: {
    cash: 1_500,
    // 보낼 원화가 넉넉하다 (필요 1,771,674원).
    exchangeableCash: 10_000_000,
    preview: {
      currency: "USD",
      referencePrice: "58.48",
      estimatedAmount: "2924.00",
      appliedFeeRate: 0,
      estimatedFee: "0.00",
      totalAmount: "2924.00",
      availableCash: "1500.00",
      shortfall: "1424.00",
      shortfallExchange: { direction: "KRW_TO_USD", sendAmount: "1771674" },
      feeFree: false,
    },
  },
};

/**
 * 달러가 모자라고 **원화로도 못 채우는** 매수. 이때만 매수가 잠긴다.
 *
 * `shortfallExchange` 가 있다고 채울 수 있는 것은 아니다 — 그 값은 "부족분이 있다" 는
 * 뜻이고, 보낼 돈이 있는지는 서버가 보지 않는다. 그래서 보내는 통화 잔고
 * (`exchangeableCash`)와 견주는 일을 패널이 한다.
 *
 * **수량을 입력하면** 버튼이 잠기고 얼마가 모자란지가 버튼 위에 뜬다.
 */
export const ShortfallUnfillable: Story = {
  args: {
    cash: 1_500,
    // 1,771,674원을 보내야 하는데 1,000,000원뿐이다 — 771,674원이 모자란다.
    exchangeableCash: 1_000_000,
    preview: {
      currency: "USD",
      referencePrice: "58.48",
      estimatedAmount: "2924.00",
      appliedFeeRate: 0,
      estimatedFee: "0.00",
      totalAmount: "2924.00",
      availableCash: "1500.00",
      shortfall: "1424.00",
      shortfallExchange: { direction: "KRW_TO_USD", sendAmount: "1771674" },
      feeFree: false,
    },
  },
};

/** 미인증. 주문 버튼 자리가 로그인 안내로 바뀐다 (ADR-0005). */
export const LoggedOut: Story = { args: { isLoggedIn: false } };

export const Dark: Story = { globals: { theme: "dark" } };
