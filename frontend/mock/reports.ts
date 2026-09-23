import { SEASON_DAY } from "./clock.ts";
import { instrumentById } from "./instruments.ts";
import { closesUpTo } from "./prices.ts";

/**
 * 복기 리포트 스텁 (`rpt.md`).
 *
 * **계좌·체결이 아직 목에 없으므로 체결 기록을 지어낸다** — 값이 아니라 화면이 다뤄야 하는
 * 모양을 검증하기 위한 것이다: 매수·매도 마커가 종가 선 위에 얹히고, 상·하위 거래가 갈리고,
 * 패턴 태그가 붙는다. 주문 슬라이스가 목에 붙으면 이 파일이 실제 체결에서 파생된다
 * (`stubs.ts` 와 같은 자리다).
 *
 * **1일차만 재생하므로 2·3일차는 영원히 `available: false` 다**(`clock.ts` 의 `SEASON_DAY`).
 *
 * 시즌 리포트는 계약상 **3일차** 정산 발표 이후에 열린다(`rpt.md`). 목에는 3일차가 없으므로
 * 그대로 두면 화면을 영영 못 본다 — 그래서 **목의 시즌은 그것이 재생하는 1일차 하나**로
 * 보고, 그 일차의 정산 발표를 시즌 종료로 삼는다. 실제 백엔드는 3일차를 기다린다.
 */

/** 목이 리포트를 만들어 두는 일차 수. 기획의 시즌 길이다. */
const SEASON_DAYS = 3;

/** 지어낸 체결이 붙는 종목. 재생 전 구간에 종가가 있는 것들로 골랐다. */
const TRADED_INSTRUMENT_IDS = [1, 3, 13];

/** 비교 기준선이 쓰는 지수 추종 ETF (기획 13.3.3). `instruments.ts` 의 id 다. */
const QQQ_INSTRUMENT_ID = 1;
const KODEX_INSTRUMENT_ID = 13;

/** 내 수익률 고정값. 일일 요약·what-if·시즌 지표가 같은 값을 봐야 한다. */
const MY_RETURN_RATE = 4.21;

/**
 * 그 일차의 복기를 열 수 있는가. 폐장(16:40)에 만들어지지만 정산 발표(17:00)에 열린다 —
 * `REVIEW_ONLY` 가 정산 발표 이후 구간이다 (`clock.ts` 의 `DAY_MINUTES`).
 */
export function reportAvailable(seasonDay: number, phase: string): boolean {
  return seasonDay === SEASON_DAY.day && phase === "REVIEW_ONLY";
}

export function dailyReportList(phase: string) {
  return {
    items: Array.from({ length: SEASON_DAYS }, (_, index) => {
      const seasonDay = index + 1;
      const available = reportAvailable(seasonDay, phase);
      return {
        seasonDay,
        // 아직 오지 않은 일차의 구간은 null 이다 — 여기서 내보내면 미래 차단이 뚫린다.
        fromGameMonth: available ? SEASON_DAY.fromGameMonth : null,
        toGameMonth: available ? SEASON_DAY.toGameMonth : null,
        dailyReturn: available ? MY_RETURN_RATE : null,
        available,
      };
    }),
  };
}

/** 지어낸 체결. 구간의 종가에서 뽑으므로 마커가 항상 선 위에 놓인다. */
function tradedInstrument(instrumentId: number, toGameDate: string) {
  const instrument = instrumentById(instrumentId);
  if (!instrument) return null;

  const points = closesUpTo(instrument, toGameDate).map((point) => ({
    gameDate: point.gameDate,
    close: point.close.toFixed(instrument.priceScale),
  }));
  if (points.length < 10) return null;

  // 구간의 1/4 지점에 매수, 3/4 지점에 매도. 일부러 고정 위치다 — 스냅샷이라 매 조회에
  // 같은 값이 나와야 하고, 랜덤이면 새로고침마다 리포트가 달라진다.
  const buy = points[Math.floor(points.length / 4)]!;
  const sell = points[Math.floor((points.length * 3) / 4)]!;

  return {
    instrument,
    entry: {
      instrumentId,
      currency: instrument.currency,
      priceScale: instrument.priceScale,
      points,
      markers: [
        {
          executionId: instrumentId * 100 + 1,
          gameDate: buy.gameDate,
          side: "BUY" as const,
          quantity: 40,
          price: buy.close,
        },
        {
          executionId: instrumentId * 100 + 2,
          gameDate: sell.gameDate,
          side: "SELL" as const,
          quantity: 40,
          price: sell.close,
        },
      ],
    },
    returnRate:
      ((Number(sell.close) - Number(buy.close)) / Number(buy.close)) * 100,
  };
}

export function dailyReport(seasonDay: number, toGameDate: string) {
  const traded = TRADED_INSTRUMENT_IDS.map((id) =>
    tradedInstrument(id, toGameDate)
  ).filter((item) => item !== null);

  const sorted = [...traded].sort((a, b) => b.returnRate - a.returnRate);
  const best = sorted.at(0);
  const worst = sorted.at(-1);
  const highlight = (item: NonNullable<typeof best>) => ({
    executionId: item.entry.markers[1]!.executionId,
    instrumentId: item.entry.instrumentId,
    realizedPnlKrw: String(Math.round(item.returnRate * 40_000)),
    returnRate: Number(item.returnRate.toFixed(2)),
  });

  return {
    seasonDay,
    fromGameMonth: SEASON_DAY.fromGameMonth,
    toGameMonth: SEASON_DAY.toGameMonth,
    finalizedAt: new Date().toISOString(),
    summary: {
      dailyReturn: MY_RETURN_RATE,
      executionCount: traded.length * 2,
      totalFeeKrw: "182400",
      realizedPnlKrw: "3120000",
    },
    // 매도 체결이 없으면 null 이다 — 여기서는 항상 있지만 화면이 null 을 다뤄야 한다.
    bestTrade: best ? highlight(best) : null,
    worstTrade: worst && worst !== best ? highlight(worst) : null,
    instruments: traded.map((item) => item.entry),
    patternTags: worst
      ? [
          {
            kind: "PANIC_BUY_HIGH" as const,
            executionId: worst.entry.markers[0]!.executionId,
            instrumentId: worst.entry.instrumentId,
            changeRate: -13.2,
          },
        ]
      : [],
    // AI 가 만든 그날의 문구 (`rpt.md`). 생성 전·실패면 null 이지만 목은 늘 채운다 —
    // 빈 쪽은 백엔드가 실패했을 때 나온다.
    commentary: {
      headline: "폭락장을 정면으로 통과한 하루",
      sceneAliases: ["3월의 바닥", "반등 첫 주", "늦은 추격"],
    },
    // 복기 결과지. AI 가 매일 내는 성향 판정이다 (`ai/docs/specs/AI_투자자유형_분석.md`).
    // **백엔드가 아직 이 자리를 채우지 않는다** — 목이 먼저 채워 화면을 볼 수 있게 한다.
    // 값은 AI 응답의 `review` 를 그대로 옮긴 모양이다.
    review: {
      investorType: "LEVEL_4",
      typeEmoji: "⚡",
      typeName: "변동성을 즐기는 트레이더",
      typeDescription:
        "흔들리는 자리에서 기회를 찾습니다. 현금을 오래 쥐고 있지 않습니다.",
      verdict:
        "지수가 꾸준히 오른 구간이라 들어간 자리가 그대로 회복됐습니다. +4.2%로 상위 18%에 올랐습니다.",
      profileLevel: 4,
      profileTotal: 16,
      profileName: "적극투자형",
      profilePersona: "변동성을 즐기는 트레이더",
      // 다섯 항목. `point` 가 null 인 자리와 `provisional` 이 켜진 자리를 일부러 섞어 둔다 —
      // 화면이 그 둘을 다르게 그리는지 목에서 먼저 드러나야 한다.
      profileItems: [
        {
          item: "INVESTED" as const,
          label: "투입 비중",
          point: 4,
          provisional: false,
          selfPoint: 3,
        },
        {
          item: "DRAWDOWN" as const,
          label: "손실 감내",
          point: 4,
          provisional: false,
          selfPoint: 4,
        },
        {
          item: "ENTRY" as const,
          label: "진입 타이밍",
          point: 3,
          provisional: false,
          selfPoint: 2,
        },
        {
          item: "DIP" as const,
          label: "급락 대응",
          point: 2,
          provisional: true,
          selfPoint: 3,
        },
        {
          item: "STOP" as const,
          label: "손실 정리",
          point: null,
          provisional: true,
          selfPoint: 2,
        },
      ],
      selfLevel: 3,
      selfTotal: 14,
      selfPersona: "유연한 자산 조율사",
      profileMatch: "NEAR" as const,
      narrative: {
        oneLine: "흔들리는 자리에서 기회를 찾습니다",
        selfView:
          "스스로는 현금을 조금 남기고, 큰 하락도 견디는 쪽이라 보셨습니다.",
        actual:
          "지금까지의 거래를 보면 현금을 거의 남기지 않고 움직이셨습니다. 급락 자리에서도 물러서지 않았습니다.",
        traits: [
          {
            axis: "INVESTED",
            title: "현금을 거의 남기지 않으셨습니다",
            body: "현금은 8.4%를 남기셨습니다.",
          },
          {
            axis: "DRAWDOWN",
            title: "큰 하락을 안고 가신 편입니다",
            body: "최대 낙폭이 31.4%였습니다.",
          },
          {
            axis: "ENTRY",
            title: "오르는 흐름을 따라가셨습니다",
            body: "매수 40건 중 22건이 오른 자리였습니다.",
          },
        ],
        nextStep:
          "지금까지는 많이 넣는 쪽이었습니다. 앞으로는 조금 더 남겨 두고 가 보세요.",
        profileGap:
          "큰 하락도 견디겠다고 하셨는데, 실제로는 깊이 빠지기 전에 움직이셨습니다.",
        lossTolerance: null,
      },
    },
    // 후회 연출(13.2)은 계약이 스키마를 비워 둔 선택 기능이다 — 시안이 그리는 모양을
    // 목이 먼저 채운다. 백엔드가 계약에 올릴 때 이 형태를 놓고 이야기하면 된다.
    regret: traded.map((item, index) => ({
      kind: index === traded.length - 1 ? ("HELD" as const) : ("SOLD" as const),
      instrumentId: item.entry.instrumentId,
      detail:
        index === traded.length - 1
          ? "하락 구간에서 보유를 유지했다"
          : `${item.entry.markers[1]!.gameDate}에 전량 매도 · 이후 재진입 없음`,
      sinceRate: Number((item.returnRate / 2).toFixed(1)),
    })),
  };
}

/**
 * 시즌 리포트 (기획 13.3).
 *
 * 벤치마크는 **실제 종가에서 계산한다** — 지수 추종 ETF 두 종(QQQ·KODEX 200)을 구간 처음부터
 * 끝까지 들고 있었을 때의 수익률이라, `what-if` 와 같은 계산이다. 지어내면 "가만히 있었다면
 * vs 나" 의 비교가 거짓이 된다.
 *
 * 지표(MDD·승률·회전율)와 최종 순위는 계좌·체결이 목에 없어 고정값이다 (`stubs.ts` 와 같다).
 */
export function seasonReport(toGameDate: string) {
  const benchmarkOf = (instrumentId: number, label: string) => {
    const result = whatIf(instrumentId, toGameDate);
    return { label, returnRate: result ? result.returnRate : 0 };
  };

  return {
    finalizedAt: new Date().toISOString(),
    metrics: {
      finalReturn: MY_RETURN_RATE,
      maxDrawdown: -18.4,
      winRate: 47.0,
      turnover: 1.86,
      totalFeeKrw: "412600",
    },
    // "나" 를 같은 배열에 넣는다 — 화면이 한 번에 정렬·색칠하고 두 값이 어긋날 길이 없다.
    benchmarks: [
      benchmarkOf(QQQ_INSTRUMENT_ID, "QQQ 단순 보유"),
      benchmarkOf(KODEX_INSTRUMENT_ID, "KODEX 200 단순 보유"),
      { label: "나", returnRate: MY_RETURN_RATE },
    ],
    finalRank: 18,
    totalParticipants: 542,
    // 투자자 유형(13.4)은 AI 판정이라 없을 수 있다 — 목은 늘 채워 화면의 채워진 쪽을
    // 보이고, 빈 쪽은 백엔드가 실패했을 때 나온다. 유형 **코드**는 계약에 없다 (`rpt.md`).
    investorType: {
      name: "존버형",
      description:
        "하락 구간에서 포지션을 유지하는 경향이 강합니다. 다만 손절 기준이 없으면 회복하지 못하는 종목도 함께 안고 갑니다.",
      verdict:
        "2020년 3월의 낙폭을 그대로 통과했고, 그 뒤 회복 구간을 온전히 받았습니다. 대신 회복하지 못한 종목 하나를 시즌 끝까지 안고 갔습니다.",
    },
    // 결과 카드(13.5)는 계약이 스키마를 비워 둔 선택 기능이다.
    shareCard: {
      headline: "나는 2020년 3월에 팔지 않았다",
      caption: "FLASHBACK · SEASON 01",
    },
  };
}

/**
 * '만약에' — 1일차 개장에 전액 매수해 구간 끝까지 보유했다면 (13.3.4, 단순 계산).
 *
 * **시즌과 일차를 함께 낸다** (`rpt.md`, S15P21A605-90). 목은 1일차만 재생하므로 `days` 가
 * 한 건이고 시즌 값과 같은 구간을 본다 — 실제로는 일차마다 구간이 갈린다.
 */
export function whatIf(instrumentId: number, toGameDate: string) {
  const instrument = instrumentById(instrumentId);
  if (!instrument) return null;

  const points = closesUpTo(instrument, toGameDate);
  const first = points.at(0)?.close;
  const last = points.at(-1)?.close;
  const returnRate =
    first && last ? Number((((last - first) / first) * 100).toFixed(2)) : 0;

  return {
    instrumentId,
    returnRate,
    myReturnRate: MY_RETURN_RATE,
    days: [
      { seasonDay: SEASON_DAY.day, returnRate, myReturnRate: MY_RETURN_RATE },
    ],
  };
}
