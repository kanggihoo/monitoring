import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { INSTRUMENTS } from "@/components/market/fixtures";

import { DAILY_REPORT, REPORT_INSTRUMENTS, SEASON_REPORT } from "./fixtures";
import {
  Column,
  InvestorTypeCard,
  MetricTiles,
  RegretList,
  SectionLabel,
  ShareCard,
  Tile,
  TradeReplayCard,
} from "./sections";

const nameOf = (instrumentId: number) =>
  INSTRUMENTS.find((item) => item.instrumentId === instrumentId)?.name ??
  `#${instrumentId}`;

/** 일일·시즌 두 탭이 같은 3열 골격을 쓰므로 조각이 여기 모여 있다. */
const meta = {
  title: "Screens/Review/Sections",
  component: Tile,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="w-[26rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { label: "일간 수익률", value: "-3.1%", hint: "2일차" },
} satisfies Meta<typeof Tile>;

export default meta;

type Story = StoryObj<typeof meta>;

export const OneTile: Story = {};

export const LabelAndColumn: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <Column>
      <SectionLabel>오늘의 요약</SectionLabel>
      <Tile label="체결" value="14건" />
      <Tile label="수수료" value="18,420원" hint="매도에만 붙는다" />
    </Column>
  ),
};

/** 시즌 지표 타일. 최종 순위가 함께 붙는다. */
export const Metrics: Story = {
  parameters: { controls: { disable: true } },
  decorators: [
    (Story) => (
      <div className="w-[52rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  render: () => (
    <MetricTiles
      metrics={SEASON_REPORT.metrics}
      finalRank={SEASON_REPORT.finalRank}
      totalParticipants={SEASON_REPORT.totalParticipants}
    />
  ),
};

/** 팔았던 종목과 버틴 종목을 `kind` 로 가른다. */
export const Regret: Story = {
  parameters: { controls: { disable: true } },
  render: () => <RegretList regret={DAILY_REPORT.regret} nameOf={nameOf} />,
};

/** 후회 연출은 선택 기능이다 — 값이 없으면 그렇다고 적는다. */
export const RegretEmpty: Story = {
  parameters: { controls: { disable: true } },
  render: () => <RegretList regret={null} nameOf={nameOf} />,
};

/** AI 판정이라 없을 수 있다. 없을 때는 자리를 비운다. */
export const InvestorType: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex flex-col gap-3">
      <InvestorTypeCard investorType={SEASON_REPORT.investorType} />
      <InvestorTypeCard investorType={null} />
    </div>
  ),
};

/** 이미지는 화면이 그린다 — 서버는 문구만 준다. */
export const Share: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <ShareCard
      card={SEASON_REPORT.shareCard}
      nickname="홍길동"
      returnRate={SEASON_REPORT.metrics.finalReturn}
      rank={SEASON_REPORT.finalRank}
    />
  ),
};

/** 매매 지점. 종가 위에 체결을 얹고, 자동 태깅된 자리를 따로 표시한다. */
export const TradeReplay: Story = {
  parameters: { controls: { disable: true } },
  decorators: [
    (Story) => (
      <div className="w-[52rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  render: () => (
    <TradeReplayCard
      instruments={REPORT_INSTRUMENTS}
      patternTags={DAILY_REPORT.patternTags}
      nameOf={nameOf}
      emptyNote="이 일차에는 거래가 없었습니다."
    />
  ),
};

/** 거래가 없던 날. 빈 칸 대신 그 사실을 적는다. */
export const TradeReplayEmpty: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <TradeReplayCard
      instruments={[]}
      patternTags={[]}
      nameOf={nameOf}
      emptyNote="이 일차에는 거래가 없었습니다."
    />
  ),
};

export const Dark: Story = { ...Metrics, globals: { theme: "dark" } };
