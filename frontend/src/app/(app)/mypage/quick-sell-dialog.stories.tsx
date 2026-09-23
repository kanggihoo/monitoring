import { useEffect } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  clockSnapshot,
  INSTRUMENTS,
  POSITIONS,
  PRICES,
} from "@/components/market/fixtures";
import type { Phase, PositionItem } from "@/lib/market-types";
import type { OrderPreview } from "@/lib/order-api";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";
import { mockFetch } from "@/stories/mock-fetch";

import { QuickSellDialog } from "./quick-sell-dialog";

const [KRW_POSITION, USD_POSITION] = POSITIONS as [PositionItem, PositionItem];

const metaOf = (instrumentId: number) =>
  INSTRUMENTS.find((item) => item.instrumentId === instrumentId);

/** 매도 미리보기. 매도는 부족분이 언제나 `"0"` 이다 (`ord.md`). */
const PREVIEW: OrderPreview = {
  currency: "KRW",
  referencePrice: "68500",
  estimatedAmount: "685000",
  appliedFeeRate: 0.15,
  estimatedFee: "1027",
  totalAmount: "683973",
  availableCash: "12500000",
  shortfall: "0",
  shortfallExchange: null,
  feeFree: false,
};

/** 시세와 시계가 서야 미리보기를 묻는다 — 둘 다 스토어에서 온다. */
function Seed({ phase = "TRADING" }: { phase?: Phase }) {
  useEffect(() => {
    sessionStore.getState().applyClock(clockSnapshot({ phase }));
    sessionStore.getState().setConnected(true);
    tickStore.setState({ tickSeq: 137, prices: PRICES });
  }, [phase]);
  return null;
}

// 포털로 붙으므로 autodocs 는 걸지 않는다 (`ui/dialog.stories.tsx` 와 같은 이유).
const meta = {
  title: "Screens/MyPage/QuickSellDialog",
  component: QuickSellDialog,
  parameters: { layout: "centered" },
  beforeEach: () => mockFetch({ "/api/v1/orders/preview": PREVIEW }),
  args: {
    position: KRW_POSITION,
    meta: metaOf(KRW_POSITION.instrumentId),
    onOpenChange: () => {},
  },
  decorators: [
    (Story) => (
      <>
        <Seed />
        <Story />
      </>
    ),
  ],
} satisfies Meta<typeof QuickSellDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * **수량 0 에서 시작한다** — 여는 순간 전량이 적혀 있으면 확인 버튼이 곧 `전량 매도` 가
 * 되어, 한 번 잘못 누르는 것과 다 파는 것이 같아진다.
 */
export const Default: Story = {};

/** 달러 종목. 금액 자릿수가 결제 통화(2자리)를 따른다. */
export const UsdPosition: Story = {
  args: {
    position: USD_POSITION,
    meta: metaOf(USD_POSITION.instrumentId),
  },
};

/** 예약 매도로 일부가 잠긴 종목. 팔 수 있는 수량은 잠긴 몫을 뺀 값이다. */
export const PartiallyLocked: Story = {
  args: {
    position: USD_POSITION,
    meta: metaOf(USD_POSITION.instrumentId),
  },
};

/** 종목 메타가 없으면 이름 자리에 `#id` 를 두고 자릿수는 2 로 둔다. */
export const UnknownInstrument: Story = {
  args: { meta: undefined },
};

/**
 * `TRADING` 이 아닐 때. 미리보기가 답하지 않으므로 화면이 스스로 어림한 값을 쓴다
 * (`ord.md`).
 */
export const Closed: Story = {
  decorators: [
    (Story) => (
      <>
        <Seed phase="REVIEW_ONLY" />
        <Story />
      </>
    ),
  ],
};

export const Dark: Story = { globals: { theme: "dark" } };
