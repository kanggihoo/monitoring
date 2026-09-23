import type {
  InstrumentMeta,
  IpoOffering,
  IpoOfferings,
} from "@/lib/market-types";
import type {
  Exchange,
  ExchangePage,
  Execution,
  ExecutionPage,
  OpenOrder,
  OpenOrders,
} from "@/lib/order-api";
import type { Envelope } from "@/lib/ws";

/** 공개 목록 교체 때 개인 관심 여부는 유지한다. */
export function updateInstruments(
  data: { instruments: InstrumentMeta[] },
  event: Envelope
) {
  if (event.type !== "market.instruments") return data;
  const fresh = event.payload as typeof data;
  return {
    ...fresh,
    instruments: fresh.instruments.map((item) => ({
      ...item,
      watched:
        data.instruments.find((old) => old.instrumentId === item.instrumentId)
          ?.watched ?? false,
    })),
  };
}

/** 실제 서버가 알려 준 주문 ID만 제거한다. 같은 종목의 다른 예약은 유지한다. */
export function updateOpenOrders(
  data: OpenOrders,
  event: Envelope
): OpenOrders {
  if (event.type === "order.changed") {
    const order = event.payload as OpenOrder;
    return {
      ...data,
      orders: [
        ...data.orders.filter((item) => item.orderId !== order.orderId),
        order,
      ].sort((a, b) => a.orderId - b.orderId),
    };
  }
  const ids =
    event.type === "order.filled"
      ? [(event.payload as Execution).orderId]
      : event.type === "order.expired"
        ? (event.payload as { orders: { orderId: number }[] }).orders.map(
            (order) => order.orderId
          )
        : event.type === "order.cancelled"
          ? [(event.payload as { orderId: number }).orderId]
          : [];
  if (!ids.length) return data;
  return {
    ...data,
    orders: data.orders.filter((order) => !ids.includes(order.orderId)),
  };
}

/** 환전 이력도 현재 페이지 크기를 넘겨 저장하지 않는다. */
export function updateExchanges(
  data: ExchangePage,
  event: Envelope
): ExchangePage {
  if (event.type !== "account.exchanged" || data.page !== 0) return data;
  const exchange = event.payload as Exchange;
  if (data.content.some((item) => item.exchangeId === exchange.exchangeId))
    return data;
  const totalElements = data.totalElements + 1;
  return {
    ...data,
    content: [exchange, ...data.content]
      .sort((a, b) => b.exchangeId - a.exchangeId)
      .slice(0, data.size),
    totalElements,
    totalPages: Math.ceil(totalElements / data.size),
    hasNext: totalElements > data.size,
  };
}

/** 최신 페이지에만 체결을 삽입한다. 이전 페이지를 읽는 동안 행 위치를 바꾸지 않는다. */
export function updateExecutions(
  data: ExecutionPage,
  event: Envelope
): ExecutionPage {
  if (event.type !== "order.filled" || data.page !== 0) return data;
  const execution = event.payload as Execution;
  if (data.content.some((item) => item.executionId === execution.executionId))
    return data;
  const totalElements = data.totalElements + 1;
  return {
    ...data,
    content: [execution, ...data.content]
      .sort(
        (a, b) =>
          b.executedAt.localeCompare(a.executedAt) ||
          b.executionId - a.executionId
      )
      .slice(0, data.size),
    totalElements,
    totalPages: Math.ceil(totalElements / data.size),
    hasNext: totalElements > data.size,
  };
}

/** 공시·경쟁률·추첨·상장 본문으로 최대 시즌 공모 수만큼의 목록을 갱신한다. */
export function updateOfferings(
  data: IpoOfferings,
  event: Envelope
): IpoOfferings {
  if (!event.type.startsWith("ipo.")) return data;
  const payload = event.payload as Partial<IpoOffering> & {
    offeringId: number;
    winnerCount?: number;
  };
  if (event.type === "ipo.disclosed") {
    const old = data.offerings.find(
      (item) => item.offeringId === payload.offeringId
    );
    return {
      ...data,
      offerings: [
        ...data.offerings.filter(
          (item) => item.offeringId !== payload.offeringId
        ),
        {
          ...(payload as IpoOffering),
          mySubscription: old?.mySubscription ?? null,
        },
      ],
    };
  }
  return {
    ...data,
    offerings: data.offerings.map((item) => {
      if (item.offeringId !== payload.offeringId) return item;
      switch (event.type) {
        case "ipo.competition":
          return { ...item, ...payload };
        case "ipo.announced":
          return {
            ...item,
            status: "ANNOUNCED",
            expectedWinnerCount:
              payload.winnerCount ?? item.expectedWinnerCount,
          };
        case "ipo.listed":
          return { ...item, status: "LISTED" };
        case "ipo.result":
          return item.mySubscription
            ? {
                ...item,
                mySubscription: {
                  ...item.mySubscription,
                  result: event.payload as NonNullable<
                    NonNullable<IpoOffering["mySubscription"]>["result"]
                  >,
                },
              }
            : item;
        default:
          return item;
      }
    }),
  };
}
