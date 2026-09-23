"use client";

import { useState } from "react";

import { PATTERN_TAG_LABEL } from "@/components/market/labels";
import { PriceChange } from "@/components/market/price-change";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { gameDay, krw, localPrice } from "@/lib/format";
import type {
  Closing,
  ClosingHolding,
  PatternTag,
  Regret,
  ReportInstrument,
  ReportMarker,
} from "@/lib/market-types";
import { cn } from "@/lib/utils";

/**
 * 복기 화면의 조각들. 시안이 두 탭(오늘의 복기·시즌 리포트)에 **같은 3열 골격**을 쓰므로
 * 조각을 여기 모으고 두 패널이 골라 쓴다. 화면 전용이라 스토리를 두지 않는다
 * (`docs/conventions/code.md` 4절).
 */

/**
 * 그 일차를 끝낸 자산. <b>요약 카드에서 눌러 여는 상세</b>다.
 *
 * <p><b>합계가 아니라 구성을 보여주는 자리다.</b> 합계는 일일 수익률과 같은 사실이라
 * (일차마다 모두 시드에서 출발한다) 타일이 이미 말하고 있다. 여기서만 알 수 있는 것은
 * 그 수익률을 <b>현금을 잔뜩 들고 냈는지 전부 밀어 넣어 냈는지</b>다.
 *
 * <p><b>상시로 자리를 차지하지 않는다.</b> 매번 읽는 값이 아니라 확인할 때만 보는 값이라,
 * 열 하나를 내주면 늘 보는 것들이 그만큼 밀린다.
 *
 * <p>구성이 없는 일차({@code closing} 이 없는 옛 기록)는 <b>버튼째 그리지 않는다</b> —
 * 눌렀더니 빈 상자가 나오는 것보다 없는 편이 낫다.
 *
 * <p>{@code DialogTrigger} 는 내보내지 않는 프리미티브라 열림 상태를 직접 쥔다.
 */
export function ClosingAssetsDialog({
  seasonDay,
  closing,
  holdings,
  nameOf,
}: {
  seasonDay: number;
  closing: Closing | null;
  holdings: ClosingHolding[];
  nameOf: (instrumentId: number) => string;
}) {
  const [open, setOpen] = useState(false);
  const [showHoldings, setShowHoldings] = useState(false);
  if (!closing) {
    return null;
  }
  const total = Number(closing.totalValuationKrw);
  const usdInKrw = Number(closing.usdCash) * Number(closing.usdKrw);
  // 비중의 분모는 확정 합계다. 구성의 합이 아니라서 세 몫이 100 % 를 살짝 벗어날 수
  // 있는데, 그 차이가 곧 "마지막 틱 이후에 거래했다" 는 사실이라 감추지 않는다.
  const share = (value: number) => (total === 0 ? 0 : (value / total) * 100);

  return (
    <>
      <Button
        type="button"
        variant="outline"
        size="xs"
        onClick={() => setOpen(true)}
      >
        마감 자산
      </Button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogTitle>마감 자산 · {seasonDay}일차</DialogTitle>
          <DialogDescription>
            {gameDay(closing.asOfGameDate)} 종가 기준 · 환율 {closing.usdKrw}원
          </DialogDescription>

          <dl className="flex flex-col divide-y divide-border">
            <AssetRow
              label="원화 현금"
              krw={closing.krwCash}
              share={share(Number(closing.krwCash))}
            />
            <AssetRow
              label="달러 현금"
              note={`$${Number(closing.usdCash).toLocaleString("ko-KR", { minimumFractionDigits: 2 })}`}
              krw={usdInKrw}
              share={share(usdInKrw)}
            />
            <AssetRow
              label="보유"
              note={holdings.length > 0 ? `${holdings.length}종` : "없음"}
              krw={closing.holdingsValuationKrw}
              share={share(Number(closing.holdingsValuationKrw))}
              onToggle={
                holdings.length > 0
                  ? () => setShowHoldings((it) => !it)
                  : undefined
              }
              expanded={showHoldings}
            />
          </dl>

          {showHoldings && (
            <ul className="flex max-h-56 flex-col divide-y divide-border overflow-y-auto rounded-md bg-muted/40 px-3">
              {holdings.map((holding) => (
                <li
                  key={holding.instrumentId}
                  className="flex flex-col gap-0.5 py-2"
                >
                  <span className="flex items-baseline justify-between gap-3">
                    <span className="truncate text-sm">
                      {nameOf(holding.instrumentId)}
                    </span>
                    <span className="shrink-0 text-sm tabular-nums">
                      {krw(holding.valuationKrw)}
                    </span>
                  </span>
                  <span className="text-xs text-muted-foreground tabular-nums">
                    {holding.quantity.toLocaleString("ko-KR")}주 · 평단{" "}
                    {localPrice(
                      holding.avgPrice,
                      holding.currency,
                      holding.priceScale
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}

          <div className="flex items-baseline justify-between gap-3 border-t border-border pt-3">
            <span className="text-sm font-medium">합계</span>
            <span className="text-base font-semibold tabular-nums">
              {krw(total)}
            </span>
          </div>

          <dl className="grid grid-cols-2 gap-2">
            <Tile
              label="일간 순위"
              value={`${closing.dailyRank}위 / ${closing.participantCount}명`}
            />
            {/* 낙폭은 스냅샷이 없는 일차가 있다. 0 으로 채우면 "내려온 적 없다" 가 된다. */}
            <Tile
              label="최대 낙폭"
              value={
                closing.maxDrawdown === null ? (
                  <span className="text-muted-foreground">기록 없음</span>
                ) : (
                  <PriceChange value={closing.maxDrawdown} />
                )
              }
            />
          </dl>

          {/* 이 화면이 존재하는 이유다 — 여기가 초기화 직전의 마지막 모습이라는 것을
              말해야 공지와 화면이 같은 이야기를 한다. */}
          <p className="text-xs text-muted-foreground">
            {closing.resetsNextDay
              ? "다음 일차 개장에 모두 1억원으로 다시 시작합니다. 이 기록은 그대로 남습니다."
              : "시즌의 마지막 일차입니다. 이 자산으로 시즌을 끝냈습니다."}
          </p>
        </DialogContent>
      </Dialog>
    </>
  );
}

/** 마감 자산의 한 줄. 금액과 비중을 오른쪽에 붙이고, 보유만 펼칠 수 있다. */
function AssetRow({
  label,
  note,
  krw: amount,
  share,
  onToggle,
  expanded,
}: {
  label: string;
  note?: string;
  krw: string | number;
  share: number;
  onToggle?: () => void;
  expanded?: boolean;
}) {
  const body = (
    <>
      <dt className="flex min-w-0 items-baseline gap-2">
        <span className="text-sm">{label}</span>
        {note && (
          <span className="text-xs text-muted-foreground tabular-nums">
            {note}
          </span>
        )}
        {onToggle && (
          <span aria-hidden className="text-xs text-muted-foreground">
            {expanded ? "▴" : "▾"}
          </span>
        )}
      </dt>
      <dd className="flex shrink-0 items-baseline gap-3 tabular-nums">
        <span className="text-sm">{krw(amount)}</span>
        <span className="w-9 text-right text-xs text-muted-foreground">
          {share.toFixed(0)}%
        </span>
      </dd>
    </>
  );
  return onToggle ? (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={expanded}
      className="flex items-baseline justify-between gap-3 py-2 text-left hover:bg-muted/50"
    >
      {body}
    </button>
  ) : (
    <div className="flex items-baseline justify-between gap-3 py-2">{body}</div>
  );
}

/** 시안의 구역 제목 — 대문자 자간 넓은 작은 라벨. */
export function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <span className="text-2xs tracking-wider text-muted-foreground">
      {children}
    </span>
  );
}

/** 카드 안의 눌린 타일 한 칸. 시안의 회색 지표 상자다. */
export function Tile({
  label,
  value,
  hint,
  className,
}: {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("rounded-lg bg-muted px-3.5 py-3", className)}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <div className="mt-1 text-lg font-medium tabular-nums">{value}</div>
      {hint != null && (
        <div className="mt-0.5 text-2xs text-muted-foreground">{hint}</div>
      )}
    </div>
  );
}

/** 3열 골격의 한 칸. 시안은 열마다 흰 카드가 세로로 쌓인다. */
export function Column({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex min-h-0 flex-col gap-3", className)}>
      {children}
    </div>
  );
}

/** 후회 연출 (기획 13.2). 계약이 선택으로 남긴 기능이라 없으면 안내만 남긴다. */
export function RegretList({
  regret,
  nameOf,
}: {
  regret: Regret[] | null;
  nameOf: (instrumentId: number) => string;
}) {
  return (
    <Card size="sm" className="min-h-0">
      <CardContent className="flex min-h-0 flex-col gap-3">
        <SectionLabel>후회 연출 · 팔았던 종목과 버틴 종목</SectionLabel>
        {regret && regret.length > 0 ? (
          <div className="flex flex-col divide-y divide-border">
            {regret.map((item) => (
              <div
                key={`${item.kind}-${item.instrumentId}`}
                className="flex flex-col gap-1 py-2.5"
              >
                <div className="flex items-center gap-2">
                  {/* 팔았다/버텼다는 주문 의도지 가격 방향이 아니다 (docs/CONTEXT.md). */}
                  <span
                    className={cn(
                      "rounded-sm px-1.5 py-0.5 text-2xs",
                      item.kind === "SOLD"
                        ? "bg-price-down-surface text-order-sell"
                        : "bg-muted text-signal-good"
                    )}
                  >
                    {item.kind === "SOLD" ? "팔았다" : "버텼다"}
                  </span>
                  <span className="text-sm font-medium">
                    {nameOf(item.instrumentId)}
                  </span>
                </div>
                <span className="text-xs text-muted-foreground">
                  {item.detail}
                </span>
                <PriceChange value={item.sinceRate} className="text-xs" />
              </div>
            ))}
          </div>
        ) : (
          <p className="py-6 text-center text-xs text-muted-foreground">
            되돌아볼 결정이 아직 없다.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * 투자자 유형 (기획 13.4). **AI 판정이라 없을 수 있다** — 실패하면 자리를 비운다.
 *
 * 이름·설명·총평 셋을 다 낸다. 총평은 그 사람의 시즌에 대한 문장이라 유형 설명(유형 전체에
 * 대한 문장)과 다른 것을 말한다 — 하나만 그리면 나머지가 화면에 도달하지 못한다.
 */
export function InvestorTypeCard({
  investorType,
}: {
  investorType: {
    name: string;
    description: string;
    verdict: string;
  } | null;
}) {
  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-2">
        <SectionLabel>투자자 유형</SectionLabel>
        {investorType ? (
          <>
            <span className="text-xl font-semibold tracking-tight">
              {investorType.name}
            </span>
            <p className="text-xs leading-relaxed text-muted-foreground">
              {investorType.description}
            </p>
            {/* 총평은 유형 설명과 갈라 둔다 — 이어 붙이면 어디까지가 유형 일반론이고
                어디부터가 내 이야기인지 읽는 사람이 구분하지 못한다. */}
            <p className="mt-1 border-t pt-2 text-xs leading-relaxed">
              {investorType.verdict}
            </p>
          </>
        ) : (
          <p className="text-xs text-muted-foreground">
            시즌이 끝나면 판정된다.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * 결과 카드 공유 (기획 13.5).
 *
 * 카드는 **화면이 그린다** — 서버는 문구만 준다. 이미지 저장은 캔버스로 만들어 내려받는다.
 */
export function ShareCard({
  card,
  nickname = "",
  returnRate = 0,
  rank = 0,
}: {
  /** 없으면(시즌 종료 전) 자리만 지키고 저장 버튼이 잠긴다. */
  card: { headline: string; caption: string } | null;
  nickname?: string;
  returnRate?: number;
  rank?: number;
}) {
  const download = () => {
    const canvas = document.createElement("canvas");
    canvas.width = 1080;
    canvas.height = 1350;
    const context = canvas.getContext("2d");
    if (!context) return;

    // 캔버스는 CSS 를 모른다. 카드 색은 어느 테마에서도 같은 한 벌로 고정한다.
    context.fillStyle = "#14171A";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "#8B929B";
    context.font = "500 26px sans-serif";
    context.fillText(card?.caption ?? "FLASHBACK · SEASON 01", 90, 140);
    context.fillStyle = "#ffffff";
    context.font = "600 84px sans-serif";
    context.fillText(nickname, 90, 300);
    context.fillStyle = returnRate >= 0 ? "#FF5C68" : "#5B9BFF";
    context.font = "600 190px sans-serif";
    context.fillText(
      `${returnRate >= 0 ? "+" : "−"}${Math.abs(returnRate).toFixed(1)}%`,
      90,
      560
    );
    context.fillStyle = "#9AA1A9";
    context.font = "400 34px sans-serif";
    context.fillText("일간 수익률 합", 90, 630);
    context.fillText(`순위 #${rank}`, 90, 720);

    const link = document.createElement("a");
    link.href = canvas.toDataURL("image/png");
    link.download = "flashback-season.png";
    link.click();
  };

  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-3">
        <SectionLabel>결과 카드 공유</SectionLabel>
        <div className="flex flex-col gap-3 rounded-lg bg-surface-feature px-4 py-5 text-surface-feature-foreground">
          <span className="text-2xs tracking-wider opacity-70">
            {card?.caption ?? "FLASHBACK · SEASON 01"}
          </span>
          <span className="text-xl leading-snug font-semibold">
            {card?.headline ?? "시즌이 끝나면 카드가 만들어진다"}
          </span>
          {card && (
            <div className="flex gap-4 text-xs opacity-80">
              <span className="tabular-nums">
                수익률 {returnRate >= 0 ? "+" : "−"}
                {Math.abs(returnRate).toFixed(1)}%
              </span>
              <span className="tabular-nums">순위 #{rank}</span>
            </div>
          )}
        </div>
        <button
          type="button"
          onClick={download}
          disabled={!card}
          className="rounded-lg bg-secondary py-2.5 text-xs text-secondary-foreground hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
        >
          결과 카드 이미지 생성
        </button>
      </CardContent>
    </Card>
  );
}

/** 시즌 지표 타일. '오늘의 정산 요약' 과 같은 자리라 상단에 한 줄로 편다. */
export function MetricTiles({
  metrics,
  finalRank,
  totalParticipants,
}: {
  metrics: {
    finalReturn: number;
    maxDrawdown: number;
    winRate: number;
    turnover: number;
    totalFeeKrw: string;
  };
  finalRank: number;
  totalParticipants: number;
}) {
  return (
    <div className="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-5">
      {/* **'누적' 이라고 적지 않는다.** 일차 경계마다 자산이 시드로 돌아가 복리 누적이
          성립하지 않으므로, 이 값은 참가한 일차의 일간 수익률을 더한 것이다. */}
      <Tile
        label="최종 수익률"
        value={<PriceChange value={metrics.finalReturn} />}
        hint="참가한 일차의 일간 수익률 합"
      />
      {/* 계약의 다섯 지표 중 하나다 (기획 13.3.2). 시즌 누적 실현 손익은 계약에 없다 —
          일일 리포트의 `summary.realizedPnlKrw` 와 달리 시즌 쪽은 집계되지 않는다. */}
      <Tile
        label="승률"
        value={`${metrics.winRate.toFixed(1)}%`}
        hint="실현 손익이 양수인 매도 비율"
      />
      <Tile
        label="MDD"
        value={<PriceChange value={metrics.maxDrawdown} />}
        hint="종가 시계열 기준"
      />
      <Tile
        label="총 수수료"
        value={krw(metrics.totalFeeKrw)}
        hint="무료 타임 체결은 0원으로 집계된다"
      />
      <Tile
        label="최종 순위"
        value={`#${finalRank.toLocaleString("ko-KR")}`}
        hint={`${totalParticipants.toLocaleString("ko-KR")}명 중`}
      />
    </div>
  );
}

/** 곡선을 이루는 막대 수. 시안과 같은 굵기다. */
const BARS = 40;

/** 목록에 펴는 체결 한 건. 종목을 가로질러 시간 순으로 늘어선다. */
type TradePoint = ReportMarker & {
  instrumentId: number;
  currency: ReportInstrument["currency"];
  priceScale: number;
};

/**
 * 거래한 종목들을 같은 출발선(첫 종가 = 1)에 놓고 평균한 흐름.
 *
 * **계좌 평가액이 아니다.** 계약에는 리포트용 자산 곡선이 없고(`rpt.md`), 종목마다 가격대가
 * 달라(₩56,400 과 $5.95) 그냥 겹치면 비싼 종목 하나가 그림을 삼킨다. 그래서 각 종목을
 * 제 첫 종가로 나눠 배수로 바꾼 뒤 평균한다 — 축이 없는 대신 모양이 정직하다.
 */
function compositeCurve(instruments: ReportInstrument[]) {
  const dates = [
    ...new Set(
      instruments.flatMap((item) => item.points.map((point) => point.gameDate))
    ),
  ].sort();

  const closesOf = instruments.map(
    (item) =>
      new Map(item.points.map((point) => [point.gameDate, Number(point.close)]))
  );
  const bases = instruments.map((item) => Number(item.points[0]?.close ?? 0));

  return dates.map((gameDate) => {
    let sum = 0;
    let count = 0;
    closesOf.forEach((closes, index) => {
      const close = closes.get(gameDate);
      const base = bases[index] ?? 0;
      if (close !== undefined && base > 0) {
        sum += close / base;
        count += 1;
      }
    });
    return { gameDate, value: count > 0 ? sum / count : 0 };
  });
}

/**
 * 매매 지점 리플레이 (기획 13.1.2, 시안 복기 탭).
 *
 * **종목을 갈아 끼우지 않는다.** 흐름은 하나로 그리고 그 위에 체결을 점으로 찍는다 —
 * 거래한 종목이 28종(유니버스 전체)까지 갈 수 있어서, 종목마다 차트를 두면 그만큼을 눌러
 * 봐야 하루가 어떻게 흘렀는지 알 수 있다. 아래 목록이 곧 그날의 이야기다.
 *
 * 점 색은 가격 방향이 아니라 **주문 의도**다 — 매수가 `order-buy`, 매도가 `order-sell`
 * (`docs/CONTEXT.md`).
 */
export function TradeReplayCard({
  instruments,
  patternTags,
  nameOf,
  emptyNote,
}: {
  instruments: ReportInstrument[];
  /** 체결에 붙은 판정. 목록 오른쪽에 메모로 단다. */
  patternTags: PatternTag[];
  nameOf: (instrumentId: number) => string;
  emptyNote: string;
}) {
  const curve = compositeCurve(instruments);
  const points: TradePoint[] = instruments
    .flatMap((item) =>
      item.markers.map((marker) => ({
        ...marker,
        instrumentId: item.instrumentId,
        currency: item.currency,
        priceScale: item.priceScale,
      }))
    )
    .sort((a, b) => a.gameDate.localeCompare(b.gameDate));

  // 거래일 수는 수백 개다. 시안처럼 막대 40개로 줄여 모양만 남긴다.
  const step = Math.max(1, Math.ceil(curve.length / BARS));
  const bars = curve.filter((_, index) => index % step === 0);
  const values = bars.map((bar) => bar.value);
  const low = Math.min(...values);
  const high = Math.max(...values);

  /** 막대 높이(0~1). 최저 막대도 보이도록 바닥을 남긴다. */
  const heightOf = (value: number) =>
    high === low ? 1 : 0.12 + ((value - low) / (high - low)) * 0.88;

  /** 그 체결이 놓이는 막대 번호. 같은 날이 없으면 그 뒤 첫 거래일에 붙는다. */
  const barIndexOf = (gameDate: string) => {
    const found = curve.findIndex((item) => item.gameDate >= gameDate);
    const index = found < 0 ? curve.length - 1 : found;
    return Math.min(Math.floor(index / step), bars.length - 1);
  };

  const memoOf = (executionId: number) => {
    const tag = patternTags.find((item) => item.executionId === executionId);
    return tag ? PATTERN_TAG_LABEL[tag.kind] : null;
  };

  return (
    <Card size="sm" className="min-h-0 flex-1">
      <CardContent className="flex items-center justify-between gap-3">
        <SectionLabel>매매 지점 리플레이</SectionLabel>
        <span className="text-2xs text-muted-foreground">
          체결 {points.length}건 · 종목 {instruments.length}
        </span>
      </CardContent>

      {bars.length > 0 ? (
        <>
          <CardContent>
            <div
              className="flex h-28 items-end gap-1"
              role="img"
              aria-label={`거래한 종목 ${instruments.length}개의 평균 흐름과 체결 ${points.length}건`}
            >
              {bars.map((bar, index) => (
                <div key={bar.gameDate} className="relative h-full flex-1">
                  <div
                    className="absolute inset-x-0 bottom-0 rounded-xs bg-muted"
                    style={{ height: `${heightOf(bar.value) * 100}%` }}
                  />
                  {points
                    .filter((point) => barIndexOf(point.gameDate) === index)
                    .map((point) => (
                      <span
                        key={point.executionId}
                        className={cn(
                          "absolute left-1/2 size-2 -translate-x-1/2 translate-y-1/2 rounded-full",
                          point.side === "BUY"
                            ? "bg-order-buy"
                            : "bg-order-sell"
                        )}
                        style={{ bottom: `${heightOf(bar.value) * 100}%` }}
                      />
                    ))}
                </div>
              ))}
            </div>
            <p className="mt-2 text-2xs text-muted-foreground">
              거래한 종목을 같은 출발선에 놓고 평균한 흐름이다. 계좌 평가액이
              아니다.
            </p>
          </CardContent>

          <CardContent className="flex max-h-64 flex-col divide-y divide-border overflow-y-auto">
            {points.map((point) => (
              <div
                key={point.executionId}
                className="flex items-center gap-2.5 py-2 text-sm"
              >
                <span
                  className={cn(
                    "shrink-0 rounded-sm px-1.5 py-0.5 text-2xs",
                    point.side === "BUY"
                      ? "bg-price-up-surface text-order-buy"
                      : "bg-price-down-surface text-order-sell"
                  )}
                >
                  {point.side === "BUY" ? "매수" : "매도"}
                </span>
                <span className="min-w-0 flex-1 truncate">
                  {nameOf(point.instrumentId)}
                </span>
                <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                  {gameDay(point.gameDate)}
                </span>
                {/* 수량이 없으면 "그날 얼마어치 샀나" 를 목록에서 셀 수 없다 — 마커가
                    이미 들고 오는 값이라 그리기만 하면 된다. */}
                <span className="shrink-0 text-xs tabular-nums">
                  {point.quantity.toLocaleString("ko-KR")}주 ·{" "}
                  {localPrice(point.price, point.currency, point.priceScale)}
                </span>
                <span
                  className={cn(
                    "w-14 shrink-0 text-right text-xs",
                    point.side === "BUY" ? "text-order-buy" : "text-order-sell"
                  )}
                >
                  {memoOf(point.executionId)}
                </span>
              </div>
            ))}
          </CardContent>
        </>
      ) : (
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          {emptyNote}
        </CardContent>
      )}
    </Card>
  );
}
