import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import type {
  InstrumentInfo,
  InstrumentMetricItem,
  SignalCriterion,
} from "@/lib/market-types";

import { InstrumentInfoCard } from "./instrument-info-card";

const INFO: InstrumentInfo = {
  instrumentId: 1,
  cardType: "STOCK",
  summary: "요약",
  stock: {
    asOfFiscalQuarter: "2022Q1",
    disclosedGameDate: "2022-05-15",
    metrics: [
      {
        key: "REVENUE_GROWTH",
        label: "매출 성장률",
        value: "18.9",
        unit: "PERCENT",
        na: false,
      },
      {
        key: "DEBT_RATIO",
        label: "부채비율",
        value: "39.2",
        unit: "PERCENT",
        na: false,
      },
    ],
    signals: [
      { kind: "GROWTH", level: "NORMAL", basisKeys: ["REVENUE_GROWTH"] },
      { kind: "SAFETY", level: "GOOD", basisKeys: ["DEBT_RATIO"] },
    ],
  },
  product: null,
  project: null,
};

const CRITERIA: SignalCriterion[] = [
  {
    kind: "GROWTH",
    question: "이 회사는 크고 있나요?",
    metricKey: "REVENUE_GROWTH",
    thresholds: [
      { level: "GOOD", description: "매출 성장률 20% 이상" },
      { level: "NORMAL", description: "매출 성장률 0~20%" },
      { level: "CAUTION", description: "매출이 줄었습니다 (역성장)" },
    ],
  },
];

afterEach(cleanup);

describe("InstrumentInfoCard 신호등 토글", () => {
  it("누르면 basisKeys 의 지표 원값과 그 질문의 기준이 펼쳐지고, 현재 등급이 글자로 표시된다", async () => {
    const user = userEvent.setup();
    render(<InstrumentInfoCard info={INFO} criteria={CRITERIA} />);

    // 펼치기 전에는 기준 문장이 없다. 지표 원값은 위 지표 격자에 이미 한 번 있다.
    expect(screen.queryByText("매출 성장률 20% 이상")).toBeNull();

    const growth = screen.getByRole("button", {
      name: /이 회사는 크고 있나요/,
    });
    expect(growth).toHaveAttribute("aria-expanded", "false");
    await user.click(growth);
    expect(growth).toHaveAttribute("aria-expanded", "true");

    expect(
      screen.getByText("매출 성장률 0~20%").parentElement
    ).toHaveTextContent("현재");
    expect(
      screen.getByText("매출 성장률 20% 이상").parentElement
    ).not.toHaveTextContent("현재");
    // 근거 지표만 펼친다 — 부채비율은 이 질문의 근거가 아니다.
    expect(screen.getAllByText("매출 성장률")).toHaveLength(2);
    expect(screen.getAllByText("부채비율")).toHaveLength(1);

    // 다시 누르면 접힌다.
    await user.click(growth);
    expect(screen.queryByText("매출 성장률 0~20%")).toBeNull();
  });

  it("기준을 못 읽었으면 지표 원값만 펼친다", async () => {
    const user = userEvent.setup();
    render(<InstrumentInfoCard info={INFO} criteria={null} />);
    await user.click(
      screen.getByRole("button", { name: /빚이 무겁지 않나요/ })
    );
    expect(screen.getAllByText("부채비율")).toHaveLength(2);
    expect(screen.queryByText(/부채비율 100% 미만/)).toBeNull();
  });
});

/**
 * 계약이 주는 값은 `NUMERIC(24,6)` 의 원값이고 서버는 끝자리 0 만 뗀다. 화면이 자르지
 * 않으면 `80.537107%` 가 그대로 나온다 — 판정 기준이 "30배 이상" 처럼 말하는 옆에서
 * 읽히지 않는다.
 */
describe("InstrumentInfoCard 지표 표기", () => {
  const withMetric = (
    value: string,
    unit: InstrumentMetricItem["unit"]
  ): InstrumentInfo => ({
    ...INFO,
    stock: {
      ...INFO.stock!,
      metrics: [{ key: "X", label: "지표", value, unit, na: false }],
      signals: [],
    },
  });

  const shown = (value: string, unit: InstrumentMetricItem["unit"]) => {
    cleanup();
    render(<InstrumentInfoCard info={withMetric(value, unit)} />);
    return screen.getByText("지표").parentElement?.textContent ?? "";
  };

  it("비율과 배수는 둘째 자리까지 반올림한다", () => {
    expect(shown("80.537107", "PERCENT")).toContain("80.54%");
    expect(shown("132.933432", "TIMES")).toContain("132.93배");
  });

  // 없는 정밀도를 있는 것처럼 말하지 않는다 — 자리를 채우지 않고 끝낸다.
  it("끝자리 0 을 붙이지 않는다", () => {
    expect(shown("80.5", "PERCENT")).toContain("80.5%");
    expect(shown("12", "TIMES")).toContain("12배");
  });

  // 조 단위 시가총액에서 센트는 읽는 데 보태는 것이 없다. 원화 쪽과 표기가 맞는다.
  it("시가총액의 달러는 센트를 버리고 자릿수만 끊는다", () => {
    expect(shown("1116507896172.50", "USD")).toContain("$1,116,507,896,173");
  });

  // 값을 만들지 않고 그대로 말한다 — `0` 으로 채우면 "산출 불가" 가 "0 이다" 가 된다.
  it("산출 불가는 포맷하지 않는다", () => {
    cleanup();
    render(
      <InstrumentInfoCard
        info={{
          ...INFO,
          stock: {
            ...INFO.stock!,
            metrics: [
              { key: "X", label: "지표", value: null, unit: "TIMES", na: true },
            ],
            signals: [],
          },
        }}
      />
    );
    expect(screen.getByText("산출 불가")).toBeInTheDocument();
  });
});
