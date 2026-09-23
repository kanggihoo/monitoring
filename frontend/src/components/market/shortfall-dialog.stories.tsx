import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { OrderPreview } from "@/lib/order-api";

import { ShortfallDialog } from "./shortfall-dialog";

/** 나스닥 종목 100주 매수. 달러가 모자라 원화를 보낸다. */
const PREVIEW: OrderPreview = {
  currency: "USD",
  referencePrice: "76.30",
  estimatedAmount: "7630.00",
  appliedFeeRate: 0,
  estimatedFee: "0.00",
  totalAmount: "7630.00",
  availableCash: "523.00",
  shortfall: "7107.00",
  shortfallExchange: { direction: "KRW_TO_USD", sendAmount: "9144033" },
  feeFree: false,
};

const meta = {
  title: "Common/ShortfallDialog",
  component: ShortfallDialog,
  parameters: { layout: "centered" },
  args: {
    preview: PREVIEW,
    open: true,
    onOpenChange: () => {},
    rate: 1286,
    sendAvailable: 35_000_000,
    onConfirm: () => {},
  },
} satisfies Meta<typeof ShortfallDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 환전 요청을 보내는 동안. 확인 버튼만 잠기고 `나중에` 는 열려 있다. */
export const Pending: Story = { args: { pending: true } };

/**
 * 보낼 원화까지 모자란 경우. 확인 버튼을 잠그고 왜 못 하는지 적는다 — 눌러서 409 를
 * 받아 보게 두면 사용자가 이유를 알 방법이 없다.
 */
export const NotEnoughToSend: Story = { args: { sendAvailable: 2_000_000 } };

/** 국내 종목에서 원화가 모자란 경우. 계약이 방향을 대칭으로 정의한다 (`ord.md`). */
export const KrwShortfall: Story = {
  args: {
    rate: 1286,
    sendAvailable: 8_940,
    preview: {
      ...PREVIEW,
      currency: "KRW",
      availableCash: "35000000",
      shortfall: "1924300",
      shortfallExchange: { direction: "USD_TO_KRW", sendAmount: "1497.10" },
    },
  },
};

export const Dark: Story = { globals: { theme: "dark" } };
