"use client";

import { useState } from "react";
import { ChevronDown } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { gameDay, krw } from "@/lib/format";
import type {
  InstrumentInfo,
  InstrumentMetricItem,
  InstrumentSignal,
  SignalCriterion,
  SignalGrade,
  SignalKind,
} from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { type SignalLevel, SignalLight } from "./signal-light";
import { Stat } from "./stat";

/**
 * 종목 정보 카드 (기획 9장, `info.md`). 마켓 상세 패널의 한 구획이다.
 *
 * **종목명·가격·관심 별을 그리지 않는다.** 그것들은 상세 패널 헤더가 이미 가졌고, 여기서
 * 또 그리면 같은 사실이 한 화면에 두 번 나온다. 그래서 헤더까지 품는 독립 카드가 아니라
 * 패널의 한 구획으로 둔다.
 *
 * **`cardType` 으로 분기한다.** `assetType` 으로 가르지 않는 이유는 계약과 같다 — 딥링크로
 * 들어오면 종목 메타가 없을 수 있고, `cardType` 은 어느 카드 필드가 채워지는지 결정론적으로
 * 말해 준다 (`info.md`).
 */

/** 세 질문의 문구 (기획 9.2.1). 서버의 `SignalKind` 상수와 같은 문장이다. */
const SIGNAL_QUESTION: Record<SignalKind, string> = {
  GROWTH: "이 회사는 크고 있나요?",
  SAFETY: "빚이 무겁지 않나요?",
  VALUATION: "지금 비싼 편인가요?",
};

/**
 * 계약의 등급을 신호등의 표기 등급으로 옮긴다. **`NORMAL` 이 `fair` 다** — 컴포넌트의
 * 어휘는 표시용이고 계약의 어휘는 서버 enum 이라, 경계에서 한 번 맞춘다 (`labels.ts` 가
 * 서버 enum 을 문구로 옮기는 것과 같은 자리다).
 */
const SIGNAL_GRADE: Record<SignalGrade, SignalLevel> = {
  GOOD: "good",
  NORMAL: "fair",
  CAUTION: "caution",
};

/** 기준 목록의 등급 문구·점 색. `signal-light` 의 표기와 같은 어휘다. */
const GRADE_LABEL: Record<SignalGrade, string> = {
  GOOD: "좋음",
  NORMAL: "보통",
  CAUTION: "주의",
};
const LEVEL_DOT: Record<SignalGrade, string> = {
  GOOD: "bg-signal-good",
  NORMAL: "bg-signal-fair",
  CAUTION: "bg-signal-caution",
};

/** 추종 방향 문구. */
const DIRECTION_LABEL = { LONG: "정방향", INVERSE: "역방향" } as const;

/**
 * 비율·배수의 표기 자릿수. 둘째 자리까지 반올림하되 **끝자리 0 은 붙이지 않는다** —
 * `80.5%` 를 `80.50%` 로 늘리면 없는 정밀도를 있는 것처럼 말한다.
 */
const ratio = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 2 });

/**
 * 시가총액의 달러 표기. **센트를 버린다** — 공용 `usd()` 와 달리 이 자리의 값은 조 단위라
 * 끝의 `.50` 이 읽는 데 보태는 것이 없다. 원화 쪽(`krw`)이 이미 정수로 반올림해 두 통화의
 * 표기가 여기서 맞는다.
 */
const usdWhole = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
});

/**
 * 지표 한 줄의 값 문구. **단위가 렌더링을 정한다** (`info.md` 의 `unit`).
 *
 * 산출 불가는 값을 만들지 않고 그대로 말한다 (기획 9.1.3) — `0` 이나 `-` 로 채우면
 * "산출할 수 없음"이 "0 이다"로 바뀐다.
 *
 * **자릿수는 여기서 자른다.** 계약이 주는 것은 `NUMERIC(24,6)` 의 원값이고 서버는 끝자리
 * 0 만 뗀다(`stripTrailingZeros`) — 화면이 자르지 않으면 `80.537107%` 가 그대로 나온다.
 * 판정 기준이 "30배 이상" 처럼 말하는 옆에 `132.933432배` 가 붙으면 어느 쪽이 기준인지
 * 읽히지 않는다. 원값은 서버에 그대로 두고 표기만 줄인다.
 */
function metricValue(metric: InstrumentMetricItem): string {
  if (metric.na || metric.value === null) return "산출 불가";
  const number = Number(metric.value);
  switch (metric.unit) {
    case "KRW":
      return krw(metric.value);
    case "USD":
      return usdWhole.format(number);
    case "PERCENT":
      return `${ratio.format(number)}%`;
    case "TIMES":
      return `${ratio.format(number)}배`;
  }
}

/**
 * 신호등 줄. 판정 불가는 등급 색 없이 그대로 말한다.
 *
 * **누르면 근거가 펼쳐진다** (기획 9.2.3) — 판정에 쓴 지표 원값과 등급별 기준. 어느 지표를
 * 펼칠지는 `basisKeys` 가 말하고 기준은 `/signal-criteria` 가 말한다. 프론트는 둘을 잇기만
 * 하고 추측하지 않는다 (`info.md`). 한 번에 하나만 펼친다 — 세 개가 다 펼쳐지면 카드가
 * 표가 된다.
 */
function Signals({
  signals,
  metrics,
  criteria,
}: {
  signals: InstrumentSignal[];
  metrics: InstrumentMetricItem[];
  criteria: SignalCriterion[] | null | undefined;
}) {
  const [openKind, setOpenKind] = useState<SignalKind | null>(null);
  const open = signals.find((signal) => signal.kind === openKind);
  const basis = open
    ? metrics.filter((metric) => open.basisKeys.includes(metric.key))
    : [];
  const criterion = open
    ? criteria?.find((item) => item.kind === open.kind)
    : undefined;

  return (
    <div className="flex flex-col gap-2">
      <div className="grid gap-2 sm:grid-cols-3">
        {signals.map((signal) => (
          <button
            key={signal.kind}
            type="button"
            aria-expanded={signal.kind === openKind}
            onClick={() =>
              setOpenKind((current) =>
                current === signal.kind ? null : signal.kind
              )
            }
            className={cn(
              "flex items-center gap-1.5 rounded-md bg-muted px-3 py-2 text-left hover:bg-muted/70",
              signal.kind === openKind && "ring-1 ring-border"
            )}
          >
            <SignalLight
              question={SIGNAL_QUESTION[signal.kind]}
              level={signal.level === null ? null : SIGNAL_GRADE[signal.level]}
              className="min-w-0 flex-1"
            />
            <ChevronDown
              aria-hidden
              className={cn(
                "size-3 shrink-0 text-muted-foreground transition-transform",
                signal.kind === openKind && "rotate-180"
              )}
            />
          </button>
        ))}
      </div>

      {open && (
        <div className="flex flex-col gap-3 rounded-md border border-border px-3 py-2">
          {basis.length > 0 && (
            <div className="grid gap-x-4 gap-y-2 sm:grid-cols-3">
              {basis.map((metric) => (
                <Stat
                  key={metric.key}
                  label={metric.label}
                  value={metricValue(metric)}
                />
              ))}
            </div>
          )}
          {criterion && (
            <ul className="flex flex-col gap-1">
              {criterion.thresholds.map((threshold) => {
                const current = threshold.level === open.level;
                return (
                  <li
                    key={threshold.level}
                    className={cn(
                      "flex items-center gap-2 text-xs",
                      current ? "text-foreground" : "text-muted-foreground"
                    )}
                  >
                    <span
                      aria-hidden
                      className={cn(
                        "size-2 shrink-0 rounded-full",
                        LEVEL_DOT[threshold.level]
                      )}
                    />
                    <span className="w-8 shrink-0">
                      {GRADE_LABEL[threshold.level]}
                    </span>
                    <span>{threshold.description}</span>
                    {/* 현재 등급은 색이 아니라 글자로 표시한다 (색상 단독 의존 금지). */}
                    {current && <span className="font-medium">· 현재</span>}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

/** 주식·공모주 (기획 9.1·9.2). */
function StockSection({
  stock,
  criteria,
}: {
  stock: InstrumentInfo["stock"];
  criteria: SignalCriterion[] | null | undefined;
}) {
  // 카드 종류는 정해졌는데 내용이 아직 없는 상태. 계약이 이것을 빈 배열로 말한다.
  if (!stock || stock.metrics.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        아직 공시된 실적이 없습니다. 분기 실적은 공시일(분기 종료 +45일)이 지난
        게임 시각부터 보입니다.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-1.5">
        {stock.asOfFiscalQuarter && (
          <Badge variant="secondary">{stock.asOfFiscalQuarter} 기준</Badge>
        )}
        {stock.disclosedGameDate && (
          <span className="text-2xs text-muted-foreground">
            {gameDay(stock.disclosedGameDate)} 공시
          </span>
        )}
      </div>

      <div className="grid gap-x-4 gap-y-3 sm:grid-cols-3">
        {stock.metrics.map((metric) => (
          <Stat
            key={metric.key}
            label={metric.label}
            value={metricValue(metric)}
          />
        ))}
      </div>

      {stock.signals.length > 0 && (
        <Signals
          signals={stock.signals}
          metrics={stock.metrics}
          criteria={criteria}
        />
      )}
    </div>
  );
}

/** ETF·인버스 (기획 9.3). */
function ProductSection({ product }: { product: InstrumentInfo["product"] }) {
  if (!product) {
    return (
      <p className="text-xs text-muted-foreground">
        상품 구조 정보가 아직 없습니다.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-x-4 gap-y-3 sm:grid-cols-4">
        <Stat label="추종 지수" value={product.trackingIndex} />
        <Stat label="방향" value={DIRECTION_LABEL[product.direction]} />
        <Stat label="배수" value={`${product.leverage}배`} />
        <Stat label="총보수" value={`${product.expenseRatio}%`} />
      </div>

      {/* 기획 9.3.2 는 이 안내를 **상시 표시**하도록 요구한다. 접거나 흐리지 않는다. */}
      {product.riskNotice && (
        <p
          role="note"
          className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-foreground"
        >
          {product.riskNotice}
        </p>
      )}
    </div>
  );
}

/** 코인 (기획 9.4). */
function ProjectSection({ project }: { project: InstrumentInfo["project"] }) {
  if (!project) {
    return (
      <p className="text-xs text-muted-foreground">
        프로젝트 정보가 아직 없습니다.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <Stat label="용도" value={project.purpose} />
      <Stat label="발행 구조" value={project.supplyModel} />
      <Stat label="생태계 역할" value={project.ecosystemRole} />
    </div>
  );
}

export function InstrumentInfoCard({
  info,
  /** 조회 중. 종목을 바꿀 때마다 다시 읽으므로 자리를 유지한다. */
  loading = false,
  /**
   * 로그인하지 않았다. **이 경로는 미인증에 열려 있지 않다** (ADR-0005) — 부르지 않고
   * 안내만 한다. 부르면 401 이고, 그 401 은 경계 오류가 아니라 그냥 로그인이 필요한 것이다.
   */
  isLoggedIn = true,
  /** 조회가 실패했다. 상세 패널의 나머지(차트·뉴스)는 그대로 보여야 한다. */
  failed = false,
  /**
   * 신호등 판정 기준. 없으면(아직 못 읽었거나 실패) 토글에 지표 원값만 펼치고 기준은 비운다 —
   * 기준을 못 읽었다고 신호등까지 감추지 않는다.
   */
  criteria,
  className,
  ...props
}: Omit<React.ComponentProps<"div">, "children"> & {
  info: InstrumentInfo | null;
  loading?: boolean;
  isLoggedIn?: boolean;
  failed?: boolean;
  criteria?: SignalCriterion[] | null;
}) {
  return (
    <div className={cn("flex flex-col gap-2", className)} {...props}>
      {!isLoggedIn ? (
        <p className="text-xs text-muted-foreground">
          로그인하면 종목 정보와 신호등을 볼 수 있습니다.
        </p>
      ) : failed ? (
        <p className="text-xs text-muted-foreground">
          종목 정보를 불러오지 못했습니다.
        </p>
      ) : loading || !info ? (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-3 w-2/3" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : (
        <>
          <p className="text-sm text-foreground">{info.summary}</p>
          {info.cardType === "STOCK" && (
            <StockSection stock={info.stock} criteria={criteria} />
          )}
          {info.cardType === "PRODUCT" && (
            <ProductSection product={info.product} />
          )}
          {info.cardType === "PROJECT" && (
            <ProjectSection project={info.project} />
          )}
        </>
      )}
    </div>
  );
}
