import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import type { InstrumentInfo, SignalCriterion } from "@/lib/market-types";

import { InstrumentInfoCard } from "./instrument-info-card";

/**
 * 종목 정보 카드 (`info.md`). `cardType` 이 주식·상품·프로젝트 중 어느 구획을 그릴지 정하고,
 * 그 밖의 상태(미로그인·조회 중·실패·공시 전)는 카드 자리에 문장으로 말한다.
 */
/** 서버 시드(`V26`)와 같은 문장. 기준은 숫자가 아니라 완성된 문장으로 온다. */
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
  {
    kind: "SAFETY",
    question: "빚이 무겁지 않나요?",
    metricKey: "DEBT_RATIO",
    thresholds: [
      { level: "GOOD", description: "부채비율 100% 미만" },
      { level: "NORMAL", description: "부채비율 100~200%" },
      { level: "CAUTION", description: "부채비율 200% 이상" },
    ],
  },
  {
    kind: "VALUATION",
    question: "지금 비싼 편인가요?",
    metricKey: "PER",
    thresholds: [
      { level: "GOOD", description: "PER 15배 미만" },
      { level: "NORMAL", description: "PER 15~30배" },
      { level: "CAUTION", description: "PER 30배 이상" },
    ],
  },
];

const meta = {
  title: "Screens/Market/InstrumentInfoCard",
  component: InstrumentInfoCard,
  tags: ["autodocs"],
  args: { info: null, criteria: CRITERIA },
  decorators: [
    (Story) => (
      <div className="w-[40rem] max-w-full rounded-lg border border-border bg-card p-4">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof InstrumentInfoCard>;

export default meta;

type Story = StoryObj<typeof meta>;

const STOCK: InstrumentInfo = {
  instrumentId: 1,
  cardType: "STOCK",
  summary: "메모리 반도체와 스마트폰을 만드는 국내 최대 전자 기업.",
  stock: {
    asOfFiscalQuarter: "2022Q1",
    disclosedGameDate: "2022-05-15",
    metrics: [
      {
        key: "REVENUE",
        label: "매출",
        value: "77780000000000",
        unit: "KRW",
        na: false,
      },
      {
        key: "OPERATING_MARGIN",
        label: "영업이익률",
        value: "18.1",
        unit: "PERCENT",
        na: false,
      },
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
      { key: "PER", label: "PER", value: "9.8", unit: "TIMES", na: false },
      { key: "PBR", label: "PBR", value: "1.4", unit: "TIMES", na: false },
    ],
    signals: [
      { kind: "GROWTH", level: "GOOD", basisKeys: ["REVENUE_GROWTH"] },
      { kind: "SAFETY", level: "GOOD", basisKeys: ["DEBT_RATIO"] },
      { kind: "VALUATION", level: "NORMAL", basisKeys: ["PER", "PBR"] },
    ],
  },
  product: null,
  project: null,
};

/** 신호등을 누르면 근거 지표 원값과 등급별 기준이 펼쳐진다 (기획 9.2.3). */
export const Stock: Story = { args: { info: STOCK } };

/** 기준을 못 읽은 상태 — 토글에 지표 원값만 펼치고 기준 줄은 비운다. */
export const StockWithoutCriteria: Story = {
  args: { info: STOCK, criteria: null },
};

/** 상장 초기 종목 — PER 를 산출할 수 없어 값도 신호등도 "판정 불가"로 남는다 (기획 9.1.3). */
export const StockUnjudged: Story = {
  args: {
    info: {
      ...STOCK,
      stock: {
        ...STOCK.stock!,
        metrics: STOCK.stock!.metrics.map((metric) =>
          metric.key === "PER" ? { ...metric, value: null, na: true } : metric
        ),
        signals: STOCK.stock!.signals.map((signal) =>
          signal.kind === "VALUATION" ? { ...signal, level: null } : signal
        ),
      },
    },
  },
};

/** 아직 어떤 분기도 공시되지 않았다. 카드 종류는 정해졌고 내용만 없다. */
export const StockBeforeDisclosure: Story = {
  args: {
    info: {
      ...STOCK,
      stock: {
        asOfFiscalQuarter: null,
        disclosedGameDate: null,
        metrics: [],
        signals: [],
      },
    },
  },
};

/** 인버스 ETF — 위험 안내는 접지 않고 상시 표시한다 (기획 9.3.2). */
export const Product: Story = {
  args: {
    info: {
      instrumentId: 2,
      cardType: "PRODUCT",
      summary: "코스피200 일간 수익률의 -2배를 추종하는 인버스 ETF.",
      stock: null,
      product: {
        trackingIndex: "KOSPI 200",
        direction: "INVERSE",
        leverage: 2,
        expenseRatio: 0.64,
        riskNotice:
          "일간 수익률의 배수를 추종하므로 이틀 이상 보유하면 지수 등락의 배수와 어긋납니다.",
      },
      project: null,
    },
  },
};

export const Project: Story = {
  args: {
    info: {
      instrumentId: 3,
      cardType: "PROJECT",
      summary: "스마트 컨트랙트 플랫폼의 기축 자산.",
      stock: null,
      product: null,
      project: {
        purpose: "네트워크 수수료 지불과 스테이킹 담보",
        supplyModel: "발행 상한 없음 · 수수료 일부 소각",
        ecosystemRole: "탈중앙 앱과 토큰 발행의 기반 계층",
      },
    },
  },
};

export const Loading: Story = { args: { loading: true } };
export const Failed: Story = { args: { failed: true } };

/** 이 경로는 미인증에 열려 있지 않다 — 부르지 않고 안내만 한다 (ADR-0005). */
export const LoggedOut: Story = { args: { isLoggedIn: false } };

export const Dark: Story = { ...Stock, globals: { theme: "dark" } };
