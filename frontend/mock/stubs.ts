import { accountCash } from "./account.ts";
import type { PriceSnapshot } from "./prices.ts";

/**
 * 개인 큐·히트맵 스텁. **값이 아니라 리듬을 검증하기 위한 것이다** — 한 Tick 에 여러 조각이
 * 여러 destination 으로 흩어져 도착해야 프론트의 조립 버퍼가 실제로 운동한다.
 *
 * 계좌·주문·랭킹 상태는 각 슬라이스 이슈가 목에 붙인다 (`market-replay-frontend-foundation.md`
 * Out of Scope). 그때 이 파일의 함수를 진짜 상태에서 파생하는 것으로 바꾼다.
 */

/** 시드 1억 원 (기획 v0.7). */
const SEED_KRW = 100_000_000;

/**
 * 계좌 스텁의 고정 몫. **내 계좌의 `자산 현황` 탭이 쓰는 보유 종목 목과 맞춰 둔 값이다**
 * (`src/app/(app)/mypage/mock.ts`) — 어긋나면 헤더의 총자산과 그 탭의 표가 서로 다른
 * 계좌를 말한다.
 *
 * 전액 현금으로 두지 않는 이유가 그것이다. 계좌·주문 슬라이스가 붙으면 둘 다 지운다.
 */
const HOLDINGS_KRW = 48_521_520;

/** 참가자 수 스텁. 순위가 이 안에서 결정론적으로 오간다. */
const PARTICIPANTS = 542;

export function valuationStub(prices: PriceSnapshot) {
  // 환율은 틱마다 오는 값을 그대로 쓴다 — 여기서 고정하면 주문 패널이 보는 환율과 갈린다.
  // 그래서 총평가액도 매 틱 조금씩 움직인다 (`acc.md` 의 파생 규칙 그대로다).
  // 현금은 고정값이 아니라 계좌 상태에서 읽는다 — 환전하면 두 지갑이 옮겨 담기고 그것이
  // 총자산·주문 가능 현금에 그대로 비쳐야 한다 (`account.ts`).
  const cash = accountCash();
  const total =
    cash.krwCash + cash.usdCash * Number(prices.usdKrwRate) + HOLDINGS_KRW;

  return {
    asOfGameDate: prices.asOfGameDate,
    asOfTickSeq: prices.asOfTickSeq,
    usdKrwRate: prices.usdKrwRate,
    totalValuationKrw: String(Math.round(total)),
    // 잠긴 몫을 **포함한** 총 현금이다 (`acc.md`).
    krwCash: String(cash.krwCash),
    usdCash: cash.usdCash.toFixed(2),
    lockedKrw: String(cash.lockedKrw),
    lockedUsd: cash.lockedUsd.toFixed(2),
    holdingsValuationKrw: String(HOLDINGS_KRW),
    startingBalanceKrw: String(SEED_KRW),
    cumulativeReturn: Math.round(((total - SEED_KRW) / SEED_KRW) * 10000) / 100,
    dailyReturn: 0,
  };
}

/**
 * `GET /api/v1/rankings/me` 스텁 (`rank.md`).
 *
 * **암전 중에는 순위와 등락이 `null` 이고 수익률은 그대로다.** 가려지는 것은 순위뿐이고
 * 본인 수익률은 `acc.md` 에서 이미 볼 수 있어 가릴 이유가 없다 (기획 11.3.2).
 */
export function rankingStub(tickSeq: number, blackout = false) {
  // 전액 현금은 시장이 오르면 밀리고 내리면 올라온다. 그 모양만 흉내 낸다.
  const rank = 200 + Math.round(120 * Math.sin(tickSeq / 25));
  const previous = 200 + Math.round(120 * Math.sin((tickSeq - 1) / 25));
  // 평가액 순위는 시즌 순위와 같이 둔다 — 전원 같은 시드면 두 정렬이 일치한다 (`rank.md`).
  return {
    asOfTickSeq: tickSeq,
    seasonRank: blackout ? null : rank,
    seasonRankDelta: blackout ? null : previous - rank,
    seasonReturnRate: 0,
    dailyRank: blackout ? null : rank,
    dailyRankDelta: blackout ? null : previous - rank,
    dailyReturnRate: 0,
    valuationRank: blackout ? null : rank,
    valuationRankDelta: blackout ? null : previous - rank,
    // 수익률이 0 인 스텁이라 평가액도 시드 그대로다. 둘을 따로 움직이면 화면의 두 숫자가
    // 서로를 설명하지 못한다.
    totalValuationKrw: String(SEED_KRW),
    totalParticipants: PARTICIPANTS,
    blackout,
  };
}

export function heatmapStub(tickSeq: number, gameDate: string) {
  // 현금 비중이 공포·탐욕에 따라 오가는 모양. 넷의 합은 항상 100 이다.
  const cash = 30 + 10 * Math.sin(tickSeq / 40);
  const nasdaq = 36 - 4 * Math.sin(tickSeq / 40);
  const krx = 24 - 3 * Math.sin(tickSeq / 40);
  const crypto = 100 - cash - nasdaq - krx;
  const prevCash = 30 + 10 * Math.sin((tickSeq - 1) / 40);
  const delta = cash - prevCash;
  const total = 412_000_000_000;

  const bucket = (
    category: "NASDAQ" | "KRX" | "CRYPTO" | "CASH",
    weight: number,
    weightDelta: number
  ) => ({
    category,
    valuationKrw: String(Math.round((total * weight) / 100)),
    weight: Math.round(weight * 100) / 100,
    weightDelta: Math.round(weightDelta * 100) / 100,
  });

  return {
    asOfTickSeq: tickSeq,
    asOfGameDate: gameDate,
    totalValuationKrw: String(total),
    buckets: [
      bucket("NASDAQ", nasdaq, -delta * 0.4),
      bucket("KRX", krx, -delta * 0.3),
      bucket("CRYPTO", crypto, -delta * 0.3),
      bucket("CASH", cash, delta),
    ],
  };
}

/** 경쟁 상대 닉네임. 시드에서 유도되는 포트폴리오만 갖고 매매하지 않으므로 가변 상태가 없다. */
const RIVALS = [
  "느린손",
  "리플레이어",
  "2011생존자",
  "코스피지킴이",
  "달러곰",
  "물타기장인",
  "장외대기",
  "브리핑정독",
  "환전러",
  "인버스만",
  "공모주헌터",
  "현금100",
];

/**
 * `GET /api/v1/rankings` 스텁 (`rank.md`).
 *
 * **참가자 전원을 절대 순위로 놓고 요청한 페이지만 잘라 준다.** 앞서 페이지를 무시하고
 * 같은 12명을 돌려주던 때는 화면이 아무리 스크롤해도 같은 줄만 다시 받았다.
 *
 * **암전 중에는 `content` 가 빈 배열이다** — 화면이 가리는 것이 아니라 서버가 주지 않는다.
 * 개발자 도구로도 보이지 않아야 한다는 요구다 (기획 11.3.1).
 *
 * 네 트랙은 **응답 형태가 같고 줄 세우는 기준만 다르다** (`rank.md`). 일간은 오늘 개장을
 * 0% 로 두므로 누적보다 진폭이 작고, 평가액은 금액 내림차순이다.
 *
 * **`POINT` 만 다른 규칙을 탄다** (`S15P21A605-250`). 폐장에 확정된 일차의 순위를 점수로
 * 바꿔 더한 값이라 틱과 거래일이 없고(`null`), 암전을 보지 않으며, `rankDelta` 가 언제나
 * 0 이다. 목이 이 넷을 형태로만 흉내 내면 화면이 그 차이를 만나 보지 못하고 배포에서
 * 처음 만난다.
 */
export function leaderboardStub(
  tickSeq: number,
  gameDate: string,
  track: "SEASON" | "DAILY" | "VALUATION" | "POINT" = "SEASON",
  blackout = false,
  size = RIVALS.length,
  page = 0
) {
  const me = rankingStub(tickSeq, blackout);
  const daily = track === "DAILY";
  const point = track === "POINT";
  const total = me.totalParticipants;

  /**
   * 참가자 한 명. **순위를 매기기 전 값이다** — 자리(index)는 아직 순위가 아니다.
   */
  const participantAt = (index: number) => {
    const wave = Math.sin(tickSeq / (30 + (index % 40) * 3) + index);
    const seasonReturn =
      Math.round((14 - index * 0.4 + wave * 1.5) * 100) / 100;
    return {
      // 이름 붙은 경쟁자 12명 뒤는 가입 시 자동 배정되는 닉네임이다 (기획 2장).
      nickname: RIVALS[index] ?? `투자자${1000 + ((index * 137) % 9000)}`,
      avatarId: (index % 8) + 1,
      returnRate: daily
        ? Math.round((3 - index * 0.12 + wave * 0.6) * 100) / 100
        : seasonReturn,
      /**
       * **누적 수익률에서 유도한다.** 전원 같은 시드이므로 평가액과 누적 수익률은 같은
       * 사실의 두 단위다 — 따로 흔들면 화면에서 둘이 서로를 설명하지 못한다. 그래서
       * `VALUATION` 트랙의 순서가 `SEASON` 과 같아지는데, 그것이 계약이 말하는 정상이다
       * (`rank.md`: 갈리는 것은 시작 잔고가 다른 중간 합류자뿐이고 목에는 그런 계좌가 없다).
       *
       * 어느 트랙으로 조회해도 같은 금액이다 — 평가액은 계좌의 상태지 트랙의 기준값이 아니다.
       */
      totalValuationKrw: String(
        Math.round(SEED_KRW * (1 + seasonReturn / 100))
      ),
      holdingCount: 2 + (index % 5),
      tradeCount: 5 + (index % 30) * 3,
      /**
       * 1일차 하루치 점수다 — 목은 1일차만 재생하므로 확정된 일차가 하나뿐이다. 점수표는
       * 확정 규칙 그대로다: 1등 100 · 2등 80 · 3등 65 · 4~10등 50 에서 5 씩 · 11등부터 10.
       * **자리(index)가 곧 그날 등수라고 본다** — 목의 참가자는 하루만 살기 때문이다.
       */
      points: point ? dailyPoints(index + 1) : null,
      wins: point ? (index === 0 ? 1 : 0) : null,
      isMe: false,
    };
  };

  /**
   * **전원을 만들어 수익률 내림차순으로 세운 뒤 순위를 붙인다.**
   *
   * 페이지마다 자리에서 값을 뽑던 때는 순위와 수익률이 어긋났다 — 흔들림(`wave`)이
   * 자리마다 달라서 3등이 2등보다 높은 수익률을 갖는 줄이 나왔다. 순위는 수익률을
   * 줄 세운 결과이지 자리의 성질이 아니다.
   */
  const ranked = Array.from({ length: total }, (_, index) =>
    participantAt(index)
  )
    .sort((a, b) =>
      track === "VALUATION"
        ? Number(b.totalValuationKrw) - Number(a.totalValuationKrw)
        : point
          ? (b.points ?? 0) - (a.points ?? 0)
          : b.returnRate - a.returnRate
    )
    .map((participant, index) => ({
      rank: index + 1,
      // 포인트는 하루 한 번만 바뀌어 비교할 직전 틱이 없다 — 서버가 언제나 0 을 준다.
      rankDelta: point ? 0 : ((tickSeq + index) % 3) - 1,
      ...participant,
      // 내 순위와 같은 줄을 표시한다. 화면이 목록 안의 나를 알아보는 유일한 근거다.
      isMe:
        index + 1 ===
        (daily
          ? me.dailyRank
          : track === "VALUATION"
            ? me.valuationRank
            : me.seasonRank),
    }));

  const from = page * size;
  // 포인트는 이미 확정·공개된 지난 일차의 값이라 암전이 가릴 것이 없다.
  const hidden = blackout && !point;
  const content = hidden ? [] : ranked.slice(from, from + size);

  return {
    track,
    // 점수는 틱이 아니라 일차에서 나온다. 가리킬 틱이 없으므로 비운다.
    asOfTickSeq: point ? null : tickSeq,
    asOfGameDate: point ? null : gameDate,
    blackout: hidden,
    // 이 트랙의 값은 전부 확정된 것뿐이라는 뜻이다.
    finalized: point && content.length > 0,
    totalParticipants: total,
    content,
    page,
    size,
    totalElements: total,
    totalPages: Math.ceil(total / size),
    hasNext: !hidden && from + size < total,
  };
}

/**
 * 그날 등수 하나의 점수 (`S15P21A605-250` 확정 규칙). 목은 하루치라 합산이 곧 이 값이다.
 *
 * 실제 규칙에는 "그날 체결 0 건이면 0 점" 이 있지만 목의 참가자는 전원 거래한 것으로
 * 둔다 — 체결 건수를 따로 흉내 내지 않으므로 그 조건을 여기서 지어내면 화면이 목에만
 * 있는 규칙을 보게 된다.
 */
function dailyPoints(dailyRank: number): number {
  if (dailyRank === 1) return 100;
  if (dailyRank === 2) return 80;
  if (dailyRank === 3) return 65;
  if (dailyRank <= 10) return 50 - (dailyRank - 4) * 5;
  return 10;
}

/**
 * `GET /api/v1/rankings/awards` 스텁 (`rank.md`, 기획 11.4·11.5).
 *
 * 폐장(16:40)에 순위가 확정되지만 공개는 정산 발표(17:00)다 — 그 전에는 그날 항목이 없다.
 * 목은 1일차만 재생하므로 `final`(3일차 우승자)은 항상 `null` 이다.
 */
export function awardsStub(
  tickSeq: number,
  seasonDay: number,
  announced: boolean
) {
  if (!announced) {
    return { daily: [], final: null };
  }

  const top = leaderboardStub(tickSeq, "", "DAILY").content[0];
  return {
    daily: [
      {
        seasonDay,
        nickname: top?.nickname ?? RIVALS[0],
        avatarId: top?.avatarId ?? 1,
        returnRate: top?.returnRate ?? 0,
        title: "오늘의 투자자",
        announcedAt: new Date().toISOString(),
      },
    ],
    final: null,
  };
}
