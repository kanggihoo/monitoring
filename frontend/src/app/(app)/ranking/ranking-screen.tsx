"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Crown, Info } from "lucide-react";
import { useStore } from "zustand";

import { LoginPrompt } from "@/components/login-prompt";
import { PriceChange } from "@/components/market/price-change";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardTitle } from "@/components/ui/card";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useGameClock } from "@/hooks/use-game-clock";
import { trackEvent } from "@/lib/analytics";
import { krw } from "@/lib/format";
import {
  fetchAwards,
  fetchMyRank,
  fetchRankings,
  SessionExpiredError,
} from "@/lib/market-api";
import type {
  Award,
  Awards,
  Leaderboard,
  LeaderboardEntry,
} from "@/lib/market-types";
import { cn, FOCUS_RING, TAB_LIST, TAB_TRIGGER } from "@/lib/utils";
import { liveQueryStore } from "@/stores/live-query-store";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

type Track = Leaderboard["track"];

/** 한 번에 받아 오는 인원. 바닥에 닿으면 이만큼씩 이어 붙인다. */
const PAGE_SIZE = 30;

/**
 * 바닥까지 남은 거리가 이만큼이면 다음 페이지를 미리 부른다 — 닿고 나서 부르면 빈 자리를
 * 잠깐 보게 된다. 뉴스 피드(`news-feed.tsx`)와 같은 값이다.
 */
const PREFETCH_PX = 240;

/**
 * 열 구성. **머리글·목록·고정 줄이 이 하나를 공유한다** — 세 곳에 따로 적으면 열이 어긋나
 * 머리글이 다른 칸을 가리킨다.
 *
 * **수익률 칸은 아홉 자리 수익률을 기준으로 잡았다** (7.5rem = 120px). 축약은 헤더에서만
 * 하고 여기서는 있는 그대로 보여주기로 했으므로(정확한 값을 확인하는 자리다) 칸이 그 폭을
 * 가져야 한다 — 모자라면 `justify-self-end` 가 넘친 만큼을 왼쪽으로 밀어 옆 칸 위에 겹쳐
 * 그린다.
 *
 * 실측한 폭은 이렇다 (`▲` 와 간격 포함).
 *
 * <pre>
 *   ▲ +48250.78%       89px   베타 1일차 최고
 *   ▲ +6589883.61%    104px   시즌 2 1일차 최고 — 6rem(96px) 을 넘겨 점수 칸을 침범했다
 *   ▲ +659888361.61%  119px   아홉 자리
 * </pre>
 *
 * 압축 재생이라 하루 수익률이 만 단위를 우습게 넘고, 포인트 탭의 값은 그것을 <b>더한</b>
 * 값이다 — 여섯 자리 가정이 이미 두 번 깨졌으므로 한 자리 더 여유를 둔다.
 *
 * 평가액 탭만 칸이 하나 많다. 그 탭에서는 금액이 정렬 기준이라 없으면 순서를 설명할 수 없고,
 * 나머지 둘에서는 겨루는 값이 아니라 줄만 길어진다. 늘어난 칸은 **닉네임 칸(`1fr`)에서**
 * 가져오므로 순위·등락·수익률은 세 탭에서 같은 자리다 — 나머지 둘에 빈 칸을 예약해 자리를
 * 맞추는 쪽이 더 나쁘다.
 */
const COLUMNS: Record<Track, string> = {
  SEASON:
    "grid-cols-[2.5rem_minmax(0,1fr)_7.5rem] md:grid-cols-[3rem_2.5rem_minmax(0,1fr)_7.5rem]",
  DAILY:
    "grid-cols-[2.5rem_minmax(0,1fr)_7.5rem] md:grid-cols-[3rem_2.5rem_minmax(0,1fr)_7.5rem]",
  // **폰에는 평가액 칸이 없다.** 한 줄에 금액과 수익률을 나란히 놓으려면 `48,250,780,000원`
  // 에 105px, `▲+48250.78%` 에 88px 이 드는데, 360px 에서 그만큼을 떼면 닉네임이 바닥까지
  // 눌려 정작 누구인지가 안 보인다. 그래서 금액은 닉네임 아래 줄로 내린다 (`Row`) — 칸을
  // 좁혀 숫자를 자르거나 글씨를 줄이는 대신 세로로 자리를 만든다. `md` 위로는 폭이 남아
  // 제 칸을 갖는다.
  //
  // **닉네임 칸에 바닥(2.5rem)을 준다.** 320px 아래(폴드 커버 같은 예외 폭)에서는 고정
  // 칸들의 합이 줄 폭을 넘는데, `minmax(0,…)` 면 닉네임만 0 이 되고 그 안의 `나` 배지가
  // (`shrink-0`) 옆 칸 금액 위로 겹쳐 그려진다. 바닥이 있으면 겹치는 대신 오른쪽 끝이
  // 잘린다 — 설계 하한은 360px 이고, 그 아래는 "덜 보이되 망가지지는 않는다" 가 답이다.
  VALUATION:
    "grid-cols-[2rem_minmax(2.5rem,1fr)_7.5rem] md:grid-cols-[3rem_2.5rem_minmax(0,1fr)_8rem_7.5rem]",
  // **등락 칸이 없다.** 이 트랙의 `rankDelta` 는 서버가 언제나 0 으로 준다 — 점수는 하루
  // 한 번 폐장 정산에만 바뀌어 비교할 직전 틱이 없다 (`S15P21A605-254`). 칸을 두면 전원이
  // `—` 인 열이 서서, 값을 못 받은 것처럼 읽힌다. 그 자리를 점수 칸이 쓴다.
  //
  // 점수 칸은 폰에도 있다. 이 트랙을 줄 세우는 값이라 없으면 순서를 설명할 수 없고,
  // 세 자리(3일 최대 300점)면 3.5rem 에 들어간다 — 평가액과 달리 자리를 많이 먹지 않는다.
  POINT:
    "grid-cols-[2.5rem_minmax(2.5rem,1fr)_3.5rem_7.5rem] md:grid-cols-[3rem_minmax(0,1fr)_4rem_7.5rem]",
};

/**
 * 시즌 포인트 점수표 ({@code S15P21A605-250} 확정 규칙). **그날의 일간 순위**로 점수를 주고
 * 시즌 내내 더한다.
 *
 * *문구는 공지문 확정 전의 임시안이다.* 규칙 자체는 확정이지만 참가자에게 나가는 표현은
 * 아직 정해지지 않았다 — 공지문이 확정되면 그 문장을 여기로 옮긴다.
 */
const POINT_TABLE = [
  { rank: "1등", score: "100" },
  { rank: "2등", score: "80" },
  { rank: "3등", score: "65" },
  // 표에 일곱 줄을 세우는 대신 구간을 한 줄로 접는다 — `50 · 45 · 40 …` 을 늘어놓으면
  // 읽는 사람이 세어야 하고, 정작 기억할 것은 "4등이 50, 10등이 20" 이다.
  { rank: "4~10등", score: "50~20" },
  { rank: "11등부터", score: "10" },
];

/**
 * 동점 처리 순서 (같은 확정 규칙). **끝까지 같으면 공동 수상**이고 다음 순위를 건너뛴다
 * (1, 1, 3).
 */
const TIEBREAKERS = ["일간 1등 횟수", "일간 수익률 합계", "공동 수상"];

/**
 * 표에 담기지 않는 단서들. **세 줄 다 사후 이의가 날 만한 자리다** — 미거래 0점은
 * "몰랐다" 가 나오는 조건이고, 일간 수익률 합계는 누적과 혼동되며, 오늘 점수가 아직
 * 없는 이유는 폐장 전이기 때문이다 (`S15P21A605-250`).
 */
const POINT_NOTES = [
  <>
    그날{" "}
    <b className="font-medium text-foreground">체결이 한 건도 없으면 0점</b>
    입니다. 사서 들고만 있어도 마찬가지입니다.
  </>,
  <>
    <b className="font-medium text-foreground">일간 수익률 합계</b>는 날마다의
    수익률을 그대로 더한 값이며, 복리로 계산되는 누적 수익률과 다릅니다.
  </>,
  <>폐장 정산(16:40)에 확정된 일차만 반영합니다.</>,
];

/**
 * 시즌 포인트 산정 기준. **공개 스케줄 카드의 제목 줄에 아이콘으로 붙는다** — 카드로
 * 세우면 그 아래 일간 1등 자리를 밀어내는데, 규칙은 한 번 읽으면 되는 값이고 수상자는
 * 매일 바뀌는 값이다. 늘 펴 둘 이유가 없는 쪽을 접는다.
 *
 * **호버가 아니라 클릭이다.** 터치에서는 호버가 없어, 호버로만 열면 폰에서 이 내용을
 * 볼 길이 사라진다.
 */
function PointRules() {
  return (
    <Popover>
      <PopoverTrigger
        aria-label="시즌 포인트 산정 기준"
        className={cn(
          FOCUS_RING,
          "rounded-full p-0.5 text-muted-foreground transition-colors hover:text-foreground"
        )}
      >
        <Info className="size-4" aria-hidden />
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[19rem] p-3">
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium">시즌 포인트 산정 기준</span>
            <p className="text-xs leading-relaxed text-muted-foreground">
              매일의 <b className="font-medium text-foreground">일간 순위</b>를
              점수로 바꿔 시즌 내내 더합니다.
            </p>
          </div>

          {/* 표는 점수를 오른쪽 끝에 모아 세로로 읽히게 한다 — 등수와 점수가 한 줄에
              흩어져 있으면 눈이 줄마다 가로로 왕복한다. */}
          <dl className="flex flex-col divide-y divide-border rounded-md border border-border">
            {POINT_TABLE.map((item) => (
              <div
                key={item.rank}
                className="flex items-baseline justify-between gap-2 px-2.5 py-1.5 text-xs"
              >
                <dt className="text-muted-foreground">{item.rank}</dt>
                <dd className="font-medium tabular-nums">{item.score}점</dd>
              </div>
            ))}
          </dl>

          {/* 동점은 세 줄짜리 목록이 아니라 한 줄이다. 순서만 알면 되는 값이라 화살표가
              번호 매김보다 짧고 정확하다. */}
          <div className="flex flex-col gap-1">
            <SectionLabel>동점이면</SectionLabel>
            <p className="text-xs text-foreground">{TIEBREAKERS.join(" → ")}</p>
          </div>

          <ul className="flex flex-col gap-1.5">
            {POINT_NOTES.map((note, index) => (
              <li
                key={index}
                className="flex gap-1.5 text-2xs leading-relaxed text-muted-foreground"
              >
                <span aria-hidden>·</span>
                <span>{note}</span>
              </li>
            ))}
          </ul>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** 하루의 공개 리듬 (기획 11.3·11.4). 시간표는 게임 시각이다. */
const SCHEDULE = [
  { time: "11:00~16:00", state: "실시간 전면 공개 · 순위 등락 표시" },
  { time: "16:00~16:40", state: "랭킹 암전 · 변동 사실만 공지" },
  { time: "16:40~17:00", state: "정산 처리 · 17:00 발표와 데일리 시상" },
];

/** 큰 카드에 세울 등수. 그 아래는 지난 일차 줄과 무게가 같아 카드가 목록이 된다. */
const FEATURED_RANKS = 3;

/**
 * 동점을 한 줄로 접는다. 같은 (일차, 등수)가 여럿이면 첫 줄만 남기고 나머지를 센다.
 *
 * <b>펴서 그리면 카드가 무너진다.</b> 동점 판정이 표시 자릿수(소수 둘째 자리)에서 나므로
 * (`rank.md` §동점 처리) 아무도 거래하지 않은 일차에는 0.00% 로 수백 명이 함께 1위가
 * 된다 — 그날 우측 열 전체가 같은 얼굴의 목록이 됐다. 전원 목록은 리더보드가 이미 하는
 * 일이고 이 카드는 발표를 말하는 자리라, 수만 남기고 접는다.
 *
 * @param awards 등수 오름차순으로 온 시상. 서버 정렬을 그대로 쓴다
 * @returns 각 그룹의 첫 사람과 함께 받은 나머지 인원. 입력 순서를 지킨다
 */
export function collapseTies(awards: Award[]): { head: Award; tied: number }[] {
  const groups = new Map<string, { head: Award; tied: number }>();
  for (const award of awards) {
    const key = `${award.seasonDay}-${award.rank}`;
    const group = groups.get(key);
    if (group) group.tied += 1;
    else groups.set(key, { head: award, tied: 0 });
  }
  return [...groups.values()];
}

/** 구역 제목 — 복기 화면과 같은 작은 라벨이다. */
/**
 * 시상 한 줄. **오늘의 1위와 지난 일차가 같은 줄을 쓴다** — 따로 적으면 한쪽만 손보다
 * 모양이 어긋난다. 무게만 `compact` 로 가른다.
 *
 * 왕관은 아바타에 걸쳐 그 위로 올라온다 (`z-10`). 아바타를 키우지 않고 등수를 말하는
 * 자리라 크기 대신 표식으로 푼다. 위로 빼는 값이 줄 사이 간격을 넘으면 위 라벨을
 * 침범하므로 크기에 맞춰 함께 줄인다. 윤곽은 두지 않는다 — lucide 는 stroke 로 그리므로
 * `strokeWidth` 를 0 으로 두고 채움만 남긴다.
 *
 * @param caption 닉네임 아래 한 줄. 오늘은 무엇으로 받았는지, 지난 일차는 며칠차인가다
 * @param crown   왕관을 씌울지. **1 위 줄만이다** — 2·3 위까지 씌우면 표식이 등수를
 *                말하지 못하고 칭호도 일간끼리 같은 말이라 줄을 가를 것이 없어진다
 * @param tied    함께 받은 나머지 인원. 0 이면 적지 않는다
 * @param compact 지난 일차용. 오늘의 1위보다 한 단계 낮게 선다
 */
function AwardRow({
  nickname,
  caption,
  returnRate,
  crown,
  tied = 0,
  compact = false,
}: {
  nickname: string;
  caption: string;
  returnRate: number;
  crown: boolean;
  tied?: number;
  compact?: boolean;
}) {
  return (
    <div className={cn("flex items-center", compact ? "gap-2.5" : "gap-3")}>
      <span className="relative shrink-0">
        {crown && (
          <Crown
            aria-hidden
            strokeWidth={0}
            className={cn(
              "absolute left-1/2 z-10 -translate-x-1/2 fill-award-mark",
              compact ? "-top-1.5 size-4" : "-top-2 size-5"
            )}
          />
        )}
        <Avatar className={compact ? "size-8" : "size-9"}>
          <AvatarFallback>{nickname.slice(0, 1)}</AvatarFallback>
        </Avatar>
      </span>
      <div className="flex min-w-0 flex-1 flex-col">
        <span
          className={cn(
            "truncate font-medium",
            compact ? "text-sm" : "text-base"
          )}
        >
          {nickname}
          {tied > 0 && (
            <span className="font-normal text-muted-foreground">
              {" "}
              외 {tied}명
            </span>
          )}
        </span>
        <span
          className={cn(
            "text-muted-foreground",
            compact ? "text-2xs" : "text-xs"
          )}
        >
          {caption}
        </span>
      </div>
      <PriceChange
        value={returnRate}
        className={compact ? "text-xs" : "text-sm"}
      />
    </div>
  );
}

function SectionLabel({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <span
      className={cn("text-2xs tracking-wider text-muted-foreground", className)}
    >
      {children}
    </span>
  );
}

/**
 * 조회가 깨졌을 때. **원인과 다시 할 방법을 함께 낸다** — 스켈레톤만 돌려 두면 느린 것과
 * 깨진 것을 구별할 수 없다 (`docs/conventions/code.md` 8절).
 */
function FailureNotice({
  message,
  onRetry,
  /**
   * 목록 꼬리에 붙는 배치. **화면을 대체하는 안내와 같은 덩치면 안 된다** — 위에 순위가
   * 멀쩡히 서 있는데 그 아래가 빈 영역이면 목록이 끊긴 것처럼 읽힌다.
   */
  compact = false,
}: {
  message: string;
  onRetry: () => void;
  compact?: boolean;
}) {
  return (
    <div
      className={cn(
        "flex items-center text-center",
        compact
          ? "justify-center gap-2 py-3"
          : "flex-col justify-center gap-2 py-8"
      )}
    >
      <p className="text-sm text-muted-foreground">{message}</p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        다시 시도
      </Button>
    </div>
  );
}

/** 순위 등락. **무채색이다** — 빨강·파랑은 가격 방향 전용이다 (DESIGN.md). */
function RankDelta({ value }: { value: number | null }) {
  if (value === null || value === 0) {
    return (
      <span
        aria-label={value === null ? "변동 없음" : "변동 없음"}
        className="text-xs text-muted-foreground"
      >
        —
      </span>
    );
  }
  const up = value > 0;
  return (
    <span
      className="text-xs text-muted-foreground tabular-nums"
      aria-label={`${Math.abs(value)}계단 ${up ? "상승" : "하락"}`}
    >
      <span aria-hidden>
        {up ? "▲" : "▼"}
        {Math.abs(value)}
      </span>
    </span>
  );
}

/** 리더보드 한 줄. 내 줄은 목록 밖에 같은 격자로 고정된다. */
function Row({
  track,
  rank,
  nickname,
  returnRate,
  valuationKrw,
  points,
  wins,
  delta,
  highlight = false,
}: {
  /** 열 구성을 정한다. 평가액 칸은 평가액 탭에만, 점수 칸은 포인트 탭에만 있다. */
  track: Track;
  rank: number | null;
  nickname: string;
  returnRate: number;
  /** 원화 총평가액. 아직 못 받았으면 `null` 이고 칸은 `—` 다. */
  valuationKrw: string | null;
  /** 일간 순위 포인트 합. 포인트 탭 밖에서는 서버가 `null` 로 준다. */
  points: number | null;
  /** 일간 1 등 횟수. 같은 조건이다. */
  wins: number | null;
  delta: number | null;
  highlight?: boolean;
}) {
  return (
    <div
      className={cn(
        "grid items-center gap-2 px-2 py-2.5 text-sm",
        COLUMNS[track],
        highlight ? "rounded-lg bg-accent" : "border-b border-border"
      )}
    >
      {/* 칸 안에서 가운데로 모은다 — 왼쪽에 붙이면 한 자리 순위가 세 자리 순위보다
          안쪽으로 들어가 열이 들쭉날쭉해 보인다. */}
      <span className="text-center font-medium tabular-nums">
        {rank === null ? "—" : rank}
      </span>
      {/* 포인트 탭에는 이 칸이 없다 (`COLUMNS`) — 전원이 `—` 인 열을 세우지 않는다. */}
      {track !== "POINT" && (
        <span className="hidden md:block">
          <RankDelta value={delta} />
        </span>
      )}
      <span className="flex min-w-0 flex-col justify-center gap-0.5">
        <span className="flex min-w-0 items-center gap-2">
          {/* 폰에서는 뺀다. 머리글자 한 자를 위해 32px(아바타+간격)을 쓰는데, 그만큼
              닉네임이 잘려 정작 누구인지가 안 보인다. */}
          <Avatar className="size-6 max-md:hidden">
            <AvatarFallback className="text-2xs">
              {nickname.slice(0, 1)}
            </AvatarFallback>
          </Avatar>
          <span className="truncate">{nickname}</span>
        </span>
        {/* 폰의 평가액. 칸이 아니라 닉네임 아래 줄이다 (`COLUMNS`) — 금액은 자리가 모자라도
            자르면 안 되는 값이라 가로로 밀어 넣는 대신 세로로 내린다. */}
        {track === "VALUATION" && (
          <span className="text-xs text-muted-foreground tabular-nums md:hidden">
            {valuationKrw === null ? "—" : krw(valuationKrw)}
          </span>
        )}
        {/* 1 등 횟수는 칸을 따로 갖지 않는다. 동점을 가르는 둘째 기준일 뿐이라 늘 보일
            값이 아니고(`S15P21A605-250`), 폰에서 칸을 하나 더 떼면 닉네임이 그만큼
            잘린다. 0 회는 적지 않는다 — 대부분이 0 이라 줄마다 붙으면 잡음이다. */}
        {track === "POINT" && wins !== null && wins > 0 && (
          <span className="text-xs text-muted-foreground tabular-nums">
            일간 1등 {wins}회
          </span>
        )}
      </span>
      {/* 평가액 탭에서만, 그리고 `md` 위에서만 제 칸을 갖는다 (`COLUMNS`). 값이 없으면
          `—` 다 — 0 원으로 채우면 "전부 잃었다" 로 읽힌다. */}
      {track === "VALUATION" && (
        <span className="justify-self-end whitespace-nowrap tabular-nums max-md:hidden">
          {valuationKrw === null ? "—" : krw(valuationKrw)}
        </span>
      )}
      {/* 이 트랙을 줄 세우는 값이다. 확정된 일차가 없으면 목록 자체가 비므로 `null` 이
          오는 경우는 다른 트랙의 행뿐이고, 그때는 이 칸이 그려지지 않는다. */}
      {track === "POINT" && (
        <span className="justify-self-end font-medium whitespace-nowrap tabular-nums">
          {points === null ? "—" : points.toLocaleString("ko-KR")}
        </span>
      )}
      <PriceChange value={returnRate} className="justify-self-end" />
    </div>
  );
}

/**
 * 랭킹 (기획 11장, 시안 랭킹 탭).
 *
 * **암전은 화면이 가리는 것이 아니다.** 16:00~16:40 동안 서버가 `content` 를 빈 배열로
 * 주고 내 순위도 `null` 로 준다 — 개발자 도구로도 보이지 않아야 한다는 요구다(11.3.1).
 * 그래서 여기서 흐리게 덮는 처리를 하지 않는다. 반대로 **내 수익률은 암전 중에도 그대로**
 * 보인다. 가려지는 것은 타인과의 비교뿐이다(11.3.2).
 */
export function RankingScreen({
  isLoggedIn = false,
}: {
  isLoggedIn?: boolean;
}) {
  // 하루가 도는 게임이라 처음 열 때는 오늘 겨루는 판을 보여준다.
  const [track, setTrack] = useState<Track>("DAILY");
  const [board, setBoard] = useState<Leaderboard | null>(null);
  const [rows, setRows] = useState<LeaderboardEntry[]>([]);
  /**
   * 조회를 한 번 걸라는 신호. **펼 페이지 수는 여기 담지 않는다** (`pagesRef`) — 상태로
   * 들면 응답을 반영하며 그 수를 되돌릴 때마다 effect 가 한 번 더 돌아, 방금 받은 페이지를
   * 곧바로 다시 부른다(실측: `[0, 0, 1]` 이어야 할 자리에 `[0, 0, 1, 0]`). 수는 ref 가
   * 들고 이 값은 오르기만 하므로, 되돌림은 조용하고 요청은 원할 때만 나간다.
   */
  const [loadTick, setLoadTick] = useState(0);
  const [boardFailed, setBoardFailed] = useState(false);
  /**
   * **목록은 남았는데 갱신에 실패한 상태.** `boardFailed` 와 나눈다 — 그쪽은 보여 줄 것이
   * 하나도 없어 화면을 통째로 안내로 바꾸는 경우이고, 이쪽은 이미 선 순위를 그대로 두고
   * 꼬리에만 알린다. 콘솔에만 남기면 사용자는 목록이 낡았다는 사실을 알 길이 없다
   * (`docs/conventions/code.md` §8 — 원인·재시도 안내).
   */
  const [refreshFailed, setRefreshFailed] = useState(false);
  const [awards, setAwards] = useState<Awards | null>(null);
  /**
   * 목록이 말하는 내 줄. **고정 줄은 이 값으로 그린다** — `/rankings/me` 는 닉네임도
   * 보유 종목 수도 매매 횟수도 주지 않으므로, 그 응답으로 그리면 같은 사람이 한 화면에서
   * 서로 다른 값으로 두 번 선다. 내 순위가 아직 펴지 않은 페이지에 있으면 그 페이지만
   * 따로 읽어 채운다.
   */
  const [myRow, setMyRow] = useState<LeaderboardEntry | null>(null);
  const [meFailed, setMeFailed] = useState(false);
  /**
   * 개인 조회가 401 을 준 상태. **공개 조회 실패와 구별한다** — 401 을 무조건 "불러오지 못했다"
   * 로 보이면 다시 시도해도 같은 결과이고, 필요한 안내는 "로그인하세요" 다 (ADR-0005).
   */
  const [meNeedsLogin, setMeNeedsLogin] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  /** '다시 시도' 를 누르면 올라간다. 조회 effect 들이 이 값을 보고 다시 돈다. */
  const [retry, setRetry] = useState(0);

  /* ── 조회 잠금. 아래 두 effect 가 함께 쓴다 ────────────────────────────────
     전부 `ref` 인 이유는 하나다: **스크롤 이벤트는 리렌더를 기다려 주지 않는다.** 한
     프레임에 여러 번 들어오고 그 사이에는 렌더가 없으므로, state 로 잠그면 두 번째
     이벤트가 아직 풀린 값을 보고 같은 자리를 또 통과한다. 참가자 82명(3페이지)인데
     `page=11` 까지 불렀던 것이 그 결과다. */

  /** 다음 페이지를 이미 걸었거나 묶음이 도는 중. **스크롤이 보는 잠금이다.** */
  const busyRef = useRef(false);
  /** 묶음이 실제로 날아간 상태. 조회 effect 의 재진입을 막는다. */
  const inFlightRef = useRef(false);
  /** 조회 중에 온 Tick 갱신을 하나로 모은다. 끝난 뒤 최신 상태로 한 번만 다시 읽는다. */
  const pendingRef = useRef(false);
  /** 모아 둔 갱신을 흘려보내는 신호. 잠금이 풀릴 때 오른다. */
  const [flush, setFlush] = useState(0);
  /** 지금 펼 페이지 수. **조회 범위의 단일 출처다** — 스크롤도 응답도 이 값을 고친다. */
  const pagesRef = useRef(1);
  /** 마지막으로 성공한 페이지 수. 추가 조회가 실패하면 여기로 되돌린다. */
  const loadedPagesRef = useRef(1);
  /**
   * 이번 조회를 **이어 붙이기로** 시작할 페이지. 0 이면 처음부터 다시 읽는다.
   *
   * 스크롤은 새로 펴는 페이지만 부른다 — 예전에는 스크롤 한 번마다 0쪽부터 전부를 다시
   * 불러, 9쪽까지 내려가는 동안 같은 목록을 45번 읽었다(실측: `0..6`, `0..7`, `0..8` …).
   * 전 범위를 다시 읽는 이유였던 '틱이 섞이지 않게' 는 **Tick 갱신 쪽에 그대로 남는다** —
   * 그쪽은 31~36초에 한 번이라 값이 비싸지 않고, 스크롤은 초당 여러 번이라 비싸다.
   */
  const appendFromRef = useRef(0);
  /** 서버가 말한 마지막 페이지 수. **요청 범위의 상한이다** (0 은 아직 모름). */
  const totalPagesRef = useRef(0);
  /** 이미 그린 목록. 실패했을 때 '지울 것이 있는가' 를 렌더 없이 본다. */
  const rowsRef = useRef<LeaderboardEntry[]>([]);
  /**
   * 응답이 왔을 때 아직 같은 탭인가. **아래 조회 effect 보다 먼저 선언한다** — 같은 커밋에서
   * 선언 순서대로 돌므로, 탭이 바뀐 렌더에서는 조회가 시작되기 전에 이 값이 맞춰진다.
   */
  const trackRef = useRef(track);
  useEffect(() => {
    trackRef.current = track;
  }, [track]);
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  /**
   * 페이지 수를 `next` 로 잡고 조회를 건다. **수는 ref 에, 신호는 state 에** — 스크롤은
   * 리렌더를 기다리지 않고 다음 값을 계산하므로 수가 state 이면 같은 페이지를 두 번 세고,
   * 신호가 ref 이면 effect 가 돌지 않는다.
   */
  const requestPages = useCallback((next: number) => {
    // 범위를 늘리는 요청만 이어 붙인다. 같은 범위를 다시 거는 '다시 시도' 는 갱신이다.
    appendFromRef.current =
      next > loadedPagesRef.current ? loadedPagesRef.current : 0;
    pagesRef.current = next;
    setLoadTick((current) => current + 1);
  }, []);

  /**
   * 트랙을 바꿀 때 목록을 **비운다.** 페이지 수만 되돌리고 행을 남기면, 새 탭의 첫 조회가
   * 실패했을 때 그 화면이 "기존 목록이 있는 추가 조회 실패" 로 처리돼 **일간 순위가 시즌
   * 누적 자리에 그대로 선다** — 라벨만 바뀐 틀린 화면이고, 사용자는 구별할 수 없다.
   * 잠깐의 스켈레톤이 다른 트랙의 숫자보다 정직하다.
   */
  const resetBoard = useCallback(() => {
    pagesRef.current = 1;
    appendFromRef.current = 0;
    loadedPagesRef.current = 1;
    totalPagesRef.current = 0;
    rowsRef.current = [];
    setLoadTick((current) => current + 1);
    setBoard(null);
    setRows([]);
    setMyRow(null);
    setBoardFailed(false);
    setRefreshFailed(false);
  }, []);
  const clock = useGameClock();
  /**
   * 폐장 확정·정산 발표가 도착한 시각. **조회를 다시 거는 신호다.**
   *
   * 목록은 `ranking.me` 가 올 때 함께 읽는데, 암전이 풀리는 폐장(16:40)에는 그날 틱이 이미
   * 끝나 그 신호가 오지 않는다. 그대로 두면 암전 중에 받은 빈 목록이 새로고침 전까지 남는다.
   * `game.phase` 대신 이 둘을 듣는 이유는 재생이 phase 를 먼저 보내고 정산을 그 뒤에 커밋하기
   * 때문이다 — phase 로 읽으면 확정 전의 값을 받을 수 있다.
   */
  /**
   * 연결 세대. **재연결마다 오른다** — 암전 중에 끊겼다가 폐장 뒤에 돌아오면 확정 신호를
   * 받지 못한 채로 복귀한다. 로그인 사용자는 `myRank` 가 다시 세워지며 목록도 따라 읽히지만,
   * 미인증 관람자는 그 값이 없어 암전 때 받은 빈 목록에 그대로 머문다. 채팅·라이브 조회가
   * 재연결을 다루는 것과 같은 신호를 여기서도 쓴다.
   */
  const generation = useStore(liveQueryStore, (state) => state.generation);
  const settledAt = useStore(
    sessionStore,
    (state) =>
      state.events.find(
        (event) =>
          event.type === "ranking.finalized" || event.type === "ranking.awarded"
      )?.receivedAt ?? 0
  );

  // 내 순위는 Tick 마다 개인 큐로 온다 (`rank.md`). 아직 안 왔거나 낡았으면 여기서 읽는다.
  const me = useStore(tickStore, (state) => state.myRank);
  const asOf = me?.asOfTickSeq;
  /**
   * 들고 있는 내 순위가 **암전 때 받은 껍데기인가.** 그 응답은 순위가 `null` 이고, 암전이
   * 풀리는 폐장에는 그날 틱이 끝나 `ranking.me` 가 더 오지 않는다 — 실시간 갱신에 기대면
   * 밤새 그 값이다. 제자리를 찾아 주는 조회(`market-live-provider`)가 실패했을 때 이 화면이
   * 그것을 알아차리는 자리이기도 하다.
   *
   * **`seasonRank === null` 로 판정하지 않는다** — 아직 순위에 들지 못한 계좌도 같은 모양이라
   * 그 사람에게 영원한 재조회를 걸게 된다. 봉투에 실린 `blackout` 이 정확한 근거다.
   */
  const staleFromBlackout = me?.blackout === true && !clock.leaderboardBlackout;
  /** 개인 순위를 (다시) 읽어야 하는가. 실패 안내와 '다시 시도' 가 이 값을 함께 본다. */
  const needsMyRank = isLoggedIn && (me == null || staleFromBlackout);

  /**
   * 트랙이 내 값을 가른다. 아래 조회 effect 가 내 순위로 페이지를 계산하므로 여기서 먼저 푼다.
   *
   * **평가액 탭은 시즌 수익률을 쓴다** — 서버가 그 트랙의 행에 시즌 기준 수익률을 싣는 것과
   * 같은 규칙이라(`rank.md`), 고정 줄과 목록 안의 내 줄이 서로 다른 숫자를 말하지 않는다.
   */
  const daily = track === "DAILY";
  const valuation = track === "VALUATION";
  /**
   * **포인트 트랙은 개인 조회가 모른다.** `/rankings/me` 는 시즌·일간·평가액 셋만 주고
   * 포인트는 넣지 않기로 했다 (`S15P21A605-254` §하지 않는 것). 그래서 이 트랙에서는
   * 내 순위를 목록에서 찾은 줄(`myRow`)로만 안다.
   *
   * 셋의 값을 그대로 흘리면 **시즌 수익률이 일간 수익률 합 칸에 서고** 시즌 등수가 포인트
   * 등수 자리에 선다 — 라벨만 바뀐 틀린 화면이라 사용자가 구별할 수 없다. 그래서 여기서
   * 끊는다: 조회 effect 도 이 값으로 '내 페이지' 를 계산하므로 남겨 두면 엉뚱한 페이지를
   * 한 장 더 읽는다.
   */
  const point = track === "POINT";
  const rate = daily ? me?.dailyReturnRate : me?.seasonReturnRate;
  const myRank = point
    ? undefined
    : daily
      ? me?.dailyRank
      : valuation
        ? me?.valuationRank
        : me?.seasonRank;
  const myDelta = point
    ? undefined
    : daily
      ? me?.dailyRankDelta
      : valuation
        ? me?.valuationRankDelta
        : me?.seasonRankDelta;

  useEffect(() => {
    // 미인증은 개인 조회를 아예 하지 않는다 — 401 을 받아 놓고 안내하는 것보다 부르지 않는 쪽이
    // 정상 경로다. 서버 거부는 방어선이다.
    if (!needsMyRank) return;
    let cancelled = false;
    fetchMyRank()
      .then((fresh) => {
        if (cancelled) return;
        // **공용 스토어에 넣는다.** 여기만 고치면 헤더는 여전히 암전 때의 값을 말한다 —
        // 같은 사실을 두 곳이 따로 들고 있으면 한쪽만 낫는다.
        tickStore.getState().setMyRank(fresh);
        setMeFailed(false);
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        // 세션이 그 사이 끝났다. 서버 재조회는 WS 의 종료 사유가 트리거하므로 여기서는
        // 이 영역만 로그인 안내로 바꾼다.
        if (error instanceof SessionExpiredError) {
          setMeNeedsLogin(true);
          return;
        }
        console.error("내 순위를 불러오지 못했습니다", error);
        setMeFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [needsMyRank, retry]);

  /**
   * 목록은 브로드캐스트되지 않는다 — 내 순위가 갱신될 때 함께 다시 읽는다 (`rank.md`).
   *
   * **지금까지 편 페이지를 전부 다시 읽는다.** 첫 페이지만 새로 읽고 나머지를 남기면 서로
   * 다른 Tick 의 순위가 한 목록에 섞인다 — 같은 사람이 두 번 나오거나 아예 사라진다.
   *
   * **묶음은 한 번에 하나만 돈다.** 예전에는 도는 중에도 새 묶음을 시작해, 스크롤과 Tick 이
   * 겹치면 요청이 배로 늘고 먼저 떠난 응답은 전부 버려졌다. 도는 중에 온 신호는
   * `pendingRef` 에 모았다가 끝난 뒤 **최신 상태로 한 번만** 다시 읽는다.
   *
   * **`allSettled` 여야 그 보장이 성립한다.** `all` 은 하나가 깨지는 즉시 실패하므로, 아직
   * 날아가 있는 요청을 두고 잠금이 풀린다 — 실패가 되돌린 페이지 수가 곧바로 다음 묶음을
   * 부르고, 그 묶음이 살아 있는 이전 요청과 겹친다(실측: `[0, 0, 1]` 이어야 할 자리에
   * `[0, 0, 1, 0]`). 모두 끝난 뒤에 성패를 가른다.
   *
   * **`totalPages` 가 요청 범위의 상한이다.** 그 너머는 서버가 빈 배열을 주므로 부를 이유가
   * 없고, 한 번 넘어간 페이지 수는 응답을 받은 자리에서 **조용히**(`pagesRef` 만) 되돌린다 —
   * 그대로 두면 이후 Tick 마다 없는 페이지를 다시 훑고, 상태로 되돌리면 그 되돌림 자체가
   * 방금 받은 페이지를 한 번 더 부른다.
   */
  useEffect(() => {
    if (inFlightRef.current) {
      // 이미 도는 묶음이 있다. 새로 시작하지 않고 '끝나면 다시' 만 남긴다.
      pendingRef.current = true;
      return;
    }
    inFlightRef.current = true;
    busyRef.current = true;

    const requestedTrack = track;
    const limit = totalPagesRef.current;
    /** 실제로 부를 페이지 수. 서버가 말한 마지막 페이지를 넘지 않는다. */
    const wanted = pagesRef.current;
    const count = Math.max(1, limit > 0 ? Math.min(wanted, limit) : wanted);
    /**
     * 이어 붙이기의 시작 쪽. **범위 밖이면 처음부터 읽는다** — `count` 가 상한에 걸려 줄면
     * 부를 것이 하나도 없는 빈 묶음이 되고, 그것은 '전부 실패' 와 구별되지 않는다.
     */
    const from =
      appendFromRef.current > 0 && appendFromRef.current < count
        ? appendFromRef.current
        : 0;
    appendFromRef.current = 0;
    // 내 순위가 든 페이지. 펴 놓은 범위 밖이면 그 한 페이지만 더 읽는다 — 목록에 붙이지
    // 않고 고정 줄에만 쓴다.
    const myPage = myRank != null ? Math.floor((myRank - 1) / PAGE_SIZE) : null;
    const needsMyPage =
      myPage !== null && myPage >= count && (limit === 0 || myPage < limit);

    Promise.allSettled([
      ...Array.from({ length: count - from }, (_, index) =>
        fetchRankings(track, from + index, PAGE_SIZE)
      ),
      ...(needsMyPage ? [fetchRankings(track, myPage, PAGE_SIZE)] : []),
    ])
      .then((settled) => {
        for (const result of settled)
          if (result.status === "rejected")
            console.error("리더보드를 불러오지 못했습니다", result.reason);
        // 탭이 그 사이에 바뀌었으면 다른 기준의 순위다. 화면에 올리지 않는다.
        if (!mountedRef.current || trackRef.current !== requestedTrack) return;
        const listed = needsMyPage ? settled.slice(0, -1) : settled;
        const mine = needsMyPage ? settled.at(-1) : undefined;

        // **성공한 앞쪽 연속 구간만 쓴다.** 가운데가 빠진 채로 이어 붙이면 순위가 조용히
        // 건너뛰어, 없는 사람이 사라진 것처럼 보인다.
        const ok: Leaderboard[] = [];
        for (const result of listed) {
          if (result.status !== "fulfilled") break;
          ok.push(result.value);
        }
        const last = ok.at(-1) ?? null;

        if (last === null) {
          // 첫 페이지부터 실패했다. 보여 줄 것이 없으면 화면을 안내로 바꾸고, 이미 선
          // 순위가 있으면 그것을 남긴 채 꼬리에서만 알린다.
          if (rowsRef.current.length === 0) setBoardFailed(true);
          else setRefreshFailed(true);
          // 늘렸던 만큼만 되돌린다. 조용히 — 다음 조회는 '다시 시도' 나 스크롤이 건다.
          pagesRef.current = loadedPagesRef.current;
          return;
        }

        // 이어 붙이기면 이미 그린 앞쪽은 그대로 두고 꼬리만 잇는다.
        const nextRows = [
          ...(from > 0 ? rowsRef.current : []),
          ...ok.flatMap((response) => response.content),
        ];
        setBoard(last);
        rowsRef.current = nextRows;
        setRows(nextRows);
        // 내 줄은 **그 조회가 성공했을 때만** 갈아 끼운다 — 실패로 `null` 을 덮으면 고정
        // 줄이 이유 없이 닉네임을 잃는다.
        if (!needsMyPage || mine?.status === "fulfilled")
          setMyRow(
            [
              ...nextRows,
              ...(mine?.status === "fulfilled" ? mine.value.content : []),
            ].find((entry) => entry.isMe) ?? null
          );
        setBoardFailed(false);
        // 뒤쪽 페이지가 깨졌으면 목록은 남기되 그 사실을 꼬리에 남긴다.
        setRefreshFailed(ok.length < listed.length);

        totalPagesRef.current = last.totalPages;
        // 성공한 만큼이 곧 편 페이지 수다. 서버가 말한 마지막 페이지도 함께 상한이 된다 —
        // 여기서 줄이지 않으면 다음 Tick 이 빈 페이지를 다시 훑는다.
        const loaded = from + ok.length;
        const capped =
          totalPagesRef.current > 0
            ? Math.min(loaded, totalPagesRef.current)
            : loaded;
        loadedPagesRef.current = Math.max(capped, 1);
        pagesRef.current = loadedPagesRef.current;
      })
      .catch((error: unknown) => {
        // `allSettled` 는 거부하지 않는다 — 여기 오는 것은 위 반영 중의 예외뿐이다.
        console.error("리더보드를 반영하지 못했습니다", error);
      })
      .finally(() => {
        inFlightRef.current = false;
        busyRef.current = false;
        if (!mountedRef.current) return;
        if (pendingRef.current) {
          pendingRef.current = false;
          setFlush((current) => current + 1);
        }
      });
  }, [track, asOf, loadTick, retry, myRank, settledAt, generation, flush]);

  /**
   * 무한 스크롤. 목록이 바닥 근처에 닿으면 다음 페이지를 잇는다.
   *
   * 듣는 대상은 브라우저 창이 아니라 **목록이 실제로 스크롤하는 상자**다 — 창은 움직이지
   * 않으므로 창에 붙이면 영원히 불리지 않는다.
   */
  useEffect(() => {
    const viewport = bottomRef.current?.closest(
      "[data-slot='scroll-area-viewport']"
    );
    // 갱신에 실패한 동안에는 자동으로 더 부르지 않는다 — 안내를 띄워 놓고 뒤에서 같은
    // 요청을 반복하면 문구가 깜빡일 뿐 사용자가 손 쓸 자리가 없다. '다시 시도' 가 연다.
    if (!viewport || !board?.hasNext || boardFailed || refreshFailed) return;

    const onScroll = () => {
      // **조회가 도는 동안에는 세지 않는다.** 이 잠금이 없으면 연속 스크롤이 응답을 받기도
      // 전에 페이지 수를 2·3·4… 로 올리고, 그때마다 첫 페이지부터 전부 다시 불린다.
      if (busyRef.current) return;
      const remaining =
        viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
      // 바닥에 닿기 전에 미리 부른다 — 닿고 나서 부르면 빈 화면을 잠깐 보게 된다.
      if (remaining >= PREFETCH_PX) return;
      const next = pagesRef.current + 1;
      // 서버가 말한 마지막 페이지를 넘지 않는다 — 82명·30개씩이면 3페이지가 끝이다.
      if (board.totalPages > 0 && next > board.totalPages) return;
      // 잠금은 요청이 아니라 **여기서** 건다. 다음 렌더까지 기다리면 그 사이의 이벤트가
      // 같은 자리를 또 통과한다.
      busyRef.current = true;
      requestPages(next);
    };

    viewport.addEventListener("scroll", onScroll, { passive: true });
    return () => viewport.removeEventListener("scroll", onScroll);
  }, [board, boardFailed, refreshFailed, requestPages]);

  // 시상은 정산 발표(17:00)에 채워진다. phase 가 바뀌는 그 순간 다시 읽는다.
  // 못 받아도 화면의 나머지는 멀쩡하므로 카드를 감추는 것으로 끝낸다.
  useEffect(() => {
    let cancelled = false;
    fetchAwards()
      .then((fresh) => {
        if (!cancelled) setAwards(fresh);
      })
      .catch((error: unknown) =>
        console.warn("시상 결과를 불러오지 못했습니다", error)
      );
    return () => {
      cancelled = true;
    };
  }, [clock.phase, settledAt, generation]);

  /**
   * **시계가 정한다.** 응답에 실린 `blackout` 은 그 응답을 받은 순간의 사실이라, 폐장 뒤
   * 다시 읽기 전까지 암전 때의 참이 남는다. 시계는 `game.phase` 로 갱신되므로 같은 서버
   * 판정을 더 이르게 말한다 — 가리는 기준을 두 곳에 두지 않는다.
   */
  const blackout = clock.leaderboardBlackout && !point;
  /**
   * 꼬리의 '다시 시도'. **실패로 되돌린 페이지를 다시 편다** — 안내가 선 자리가 곧 못 읽은
   * 페이지이므로, 같은 범위만 새로 읽으면 사용자가 보기에는 아무 일도 일어나지 않는다.
   * 더 펼 페이지가 없으면(갱신 자체가 실패한 경우) 지금 범위를 다시 읽는다.
   */
  const retryMore = () => {
    setRefreshFailed(false);
    const next = pagesRef.current + 1;
    const capped = board?.totalPages ?? 0;
    if (board?.hasNext && (capped === 0 || next <= capped)) requestPages(next);
    else requestPages(pagesRef.current);
  };
  /**
   * 시즌 우승자. **빈 배열이 "아직 발표 전" 이다** — `?? ` 로 걸러지지 않으므로 길이를
   * 본다. 동점이면 공동 우승이라 여럿일 수 있다 (`rank.md`).
   */
  const champions = awards?.final ?? [];
  /** 가장 최근에 발표된 일차. `daily` 가 일차 오름차순이라 마지막 줄이 그 일차다. */
  const latestDay = awards?.daily.at(-1)?.seasonDay ?? 0;
  /**
   * 큰 카드에 세울 사람. 우승자가 나왔으면 그들, 아니면 가장 최근 일차의 상위다.
   *
   * **`daily` 는 일차별 1~5위다** (`S15P21A605-265`). 등수를 보지 않고 마지막 줄을
   * 집으면 그 일차의 **5위**가 "오늘의 1위" 자리에 앉는다 — 실제로 그랬다.
   */
  const featured = collapseTies(
    champions.length > 0
      ? champions
      : (awards?.daily ?? []).filter(
          (item) => item.seasonDay === latestDay && item.rank <= FEATURED_RANKS
        )
  );
  /**
   * 지난 일차 줄. **1위만 남긴다** — 이 줄을 가르는 것은 며칠차인가 하나뿐이라,
   * 2~5위를 함께 내리면 전부 그날의 우승자로 읽힌다. 최신이 위로 오게 뒤집는다.
   */
  const pastAwards = collapseTies(
    (awards?.daily ?? []).filter(
      (item) =>
        item.rank === 1 &&
        (champions.length > 0 || item.seasonDay !== latestDay)
    )
  ).reverse();

  return (
    <div className="grid min-h-0 flex-1 gap-3 xl:grid-cols-[minmax(0,1fr)_22.5rem]">
      {/* ── 리더보드 ── */}
      <Card size="sm" className="min-h-0">
        <CardContent className="flex shrink-0 flex-wrap items-center justify-between gap-3">
          <Tabs
            className="min-w-0"
            value={track}
            onValueChange={(value) => {
              setTrack(value as Track);
              // 어느 트랙을 보는지는 주소에 없어 `page_view` 가 말하지 못한다. 시즌 누적을
              // 아무도 안 본다면 그 탭의 자리를 다시 생각할 근거가 된다.
              trackEvent("ranking_view", { tab: value });
              // 쌓아 둔 목록을 버린다 — 순위 기준이 달라 이어 붙일 수 없고, 남겨
              // 두면 새 탭의 조회가 실패했을 때 지난 탭의 순위가 그대로 선다.
              resetBoard();
            }}
            aria-label="랭킹 트랙"
          >
            <TabsList
              className={cn(
                TAB_LIST,
                "w-[30rem] max-w-full overflow-x-auto group-data-horizontal/tabs:h-[37px]"
              )}
            >
              <TabsTrigger value="DAILY" className={TAB_TRIGGER}>
                일간
              </TabsTrigger>
              {/* **시즌 누적 탭이 있던 자리다.** 시즌 순위를 누적 수익률로 정하는
                  방식을 포인트 합산이 대체했다 (`S15P21A605-250`) — 둘을 나란히 두면
                  "시즌 순위" 가 둘이 되어 어느 쪽이 시상 기준인지 알 수 없다.

                  `SEASON` 트랙 자체는 서버에 남아 있고 타입에도 있다. 없앤 것은
                  탭뿐이므로 필요하면 되돌리는 데 이 줄 하나면 된다. */}
              <TabsTrigger value="POINT" className={TAB_TRIGGER}>
                시즌 포인트
              </TabsTrigger>
              <TabsTrigger value="VALUATION" className={TAB_TRIGGER}>
                평가액
              </TabsTrigger>
            </TabsList>
          </Tabs>
          {blackout && (
            <Badge variant="destructive">랭킹 암전 · 변동 사실만 공지</Badge>
          )}
        </CardContent>

        {/* 열 머리글은 **고정 줄보다 위**다. 고정 줄과 목록이 같은 열 구성을 쓰므로
            머리글 하나가 둘을 함께 설명한다 — 고정 줄을 머리글 위에 두면 라벨 없는
            `— — 나 …` 가 먼저 나와 머리글이 깨진 것처럼 읽힌다.

            목록 안에 `sticky` 로 두지 않는 이유도 같다. 스크롤 영역 밖이라 어차피 늘
            보이고, 안에 두면 고정 줄 아래에 머리글이 한 번 더 끼어든다. */}
        <CardContent
          className={cn(
            // 라벨은 접지 않는다 — 칸이 좁아지면 `닉 네 임` 처럼 한 글자씩 세로로 서서
            // 머리글 줄만 세 배로 높아진다.
            "grid shrink-0 items-center gap-2 border-b border-border px-5 pb-2 text-sm whitespace-nowrap text-muted-foreground",
            COLUMNS[track]
          )}
        >
          <span className="text-center">순위</span>
          {!point && <span className="hidden md:block">등락</span>}
          <span>닉네임</span>
          {track === "VALUATION" && (
            <span className="text-right max-md:hidden">평가액</span>
          )}
          {point && <span className="text-right">점수</span>}
          {/* **'누적' 이라고 적지 않는다.** 포인트 탭의 이 값은 일간 수익률을 그냥 더한
              것이라 복리로 계산되는 누적 수익률과 다르다 — 100 % 뒤 −50 % 면 여기는
              50 % 지만 실제 누적은 0 % 다 (`S15P21A605-250`). 공지문 표기까지 못박은
              자리이므로 화면도 같은 말을 쓴다. */}
          <span className="text-right">
            {point ? "일간 수익률 합" : "수익률"}
          </span>
        </CardContent>

        {/* 내 순위는 목록 밖에 고정한다 — 내가 몇 등이든 보여야 한다 (기획 11.4).
            목록이 스크롤해도 이 줄은 남는다. */}
        <CardContent className="shrink-0">
          {!isLoggedIn || meNeedsLogin ? (
            <LoginPrompt message="내 순위는 로그인 후에 보입니다" />
          ) : meFailed && needsMyRank ? (
            // **값이 있어도 안내한다.** 암전 때 받은 껍데기가 남아 있으면 `me` 는 있지만
            // 순위 자리가 `—` 다 — 그 화면은 "아직 순위가 없다" 와 구별되지 않는다.
            <FailureNotice
              message="내 순위를 불러오지 못했습니다."
              onRetry={() => setRetry((current) => current + 1)}
            />
          ) : point && myRow === null ? (
            // **포인트는 개인 조회가 없다** (`myRank`). 목록에서 내 줄을 만나기 전에는
            // 등수도 점수도 모르므로, 다른 탭의 값으로 칸을 채우는 대신 모른다고 적는다 —
            // 시즌 수익률이 '일간 수익률 합' 칸에 서면 라벨만 바뀐 틀린 화면이 된다.
            <p className="py-3 text-center text-xs text-muted-foreground">
              내 포인트 순위는 목록에서 내 줄을 만나면 여기 고정됩니다.
            </p>
          ) : me ? (
            <Row
              track={track}
              rank={myRow?.rank ?? myRank ?? null}
              nickname={myRow?.nickname ?? "나"}
              returnRate={myRow?.returnRate ?? rate ?? 0}
              // 목록에 내 줄이 없어도 평가액은 `/rankings/me` 가 준다 — 순위와 달리
              // 남과 비교하는 값이 아니라 내 계좌의 상태다.
              valuationKrw={
                myRow?.totalValuationKrw ?? me?.totalValuationKrw ?? null
              }
              points={myRow?.points ?? null}
              wins={myRow?.wins ?? null}
              delta={myRow?.rankDelta ?? myDelta ?? null}
              highlight
            />
          ) : (
            <Skeleton className="h-12 rounded-lg" />
          )}
        </CardContent>

        {/* 목록만 스크롤한다. `Card` 는 `overflow-hidden` 이라 이걸 두지 않으면 카드 높이를
            넘는 순위가 잘려 **아예 볼 수 없다** — 참가자가 수백 명인 화면이다.
            스크롤 모양은 마켓의 종목 목록과 같은 `ScrollArea` 다. */}
        <ScrollArea className="min-h-0 flex-1">
          <CardContent className="flex flex-col py-0 pr-3">
            {boardFailed ? (
              <FailureNotice
                message="리더보드를 불러오지 못했습니다."
                onRetry={() => setRetry((current) => current + 1)}
              />
            ) : blackout ? (
              <p className="py-10 text-center text-sm text-muted-foreground">
                16:00부터 폐장까지 순위를 가립니다. 16:40 폐장 스냅샷으로
                확정되면 순위가 다시 보이고, 시상은 17:00 정산 발표에
                공개합니다.
              </p>
            ) : board === null ? (
              <Skeleton className="my-3 h-64 rounded-lg" />
            ) : rows.length === 0 ? (
              <p className="py-10 text-center text-sm text-muted-foreground">
                {point
                  ? // 포인트는 폐장 정산에 확정된 일차에서만 나온다. 첫 정산 전에 "순위가
                    // 없다" 고만 적으면 고장으로 읽힌다 — 언제 채워지는지까지 말한다.
                    "아직 확정된 일차가 없습니다. 첫 폐장 정산(16:40)부터 점수가 쌓입니다."
                  : "아직 순위가 없습니다."}
              </p>
            ) : (
              <>
                {/* **순위를 키로 쓰지 않는다.** 동점은 같은 순위를 받으므로(`rank.md`
                    §동점 처리 — 1, 1, 3) 키가 겹치고, 그러면 순위가 바뀌는 틱에서 React 가
                    옛 줄과 새 줄을 짝지어 내지 못해 **이전 렌더의 행이 지워지지 않고 남는다**
                    (실측: 참가자 14명에 20줄이 그려지고 같은 사람이 두 번, 한 등수에 서로
                    다른 수익률이 붙었다). 되감기 직후에는 전원이 시드라 수익률이 모두 0.00%
                    이므로 목록 전체가 키 하나를 공유한다. 닉네임은 유니크 제약이 걸려 있어
                    (V24) 안정적인 키다 — 마켓 화면의 `leaderboard.tsx` 와 같은 선택이다. */}
                {rows.map((entry: LeaderboardEntry) => (
                  <Row
                    key={entry.nickname}
                    track={track}
                    rank={entry.rank}
                    nickname={entry.nickname}
                    returnRate={entry.returnRate}
                    valuationKrw={entry.totalValuationKrw ?? null}
                    points={entry.points}
                    wins={entry.wins}
                    delta={entry.rankDelta}
                    // 상위권에 들면 고정 줄과 목록에 같은 사람이 두 번 나온다.
                    // 둘을 같은 사람으로 읽히게 표시한다 (`isMe`, `rank.md`).
                    highlight={entry.isMe}
                  />
                ))}
                {/* 바닥 표식. 여기가 목록 안으로 들어오면 다음 페이지를 잇는다.
                    갱신에 실패했으면 같은 자리에서 그 사실과 재시도를 내놓는다 — 실패한
                    곳이 바로 여기이고, 위의 순위는 손대지 않는다. */}
                <div ref={bottomRef} className="text-center">
                  {refreshFailed ? (
                    <FailureNotice
                      compact
                      message="목록을 갱신하지 못했습니다."
                      onRetry={retryMore}
                    />
                  ) : (
                    <span className="block py-3 text-xs text-muted-foreground tabular-nums">
                      {board.hasNext
                        ? "더 불러오는 중…"
                        : `전체 ${board.totalElements.toLocaleString("ko-KR")}명 · 끝`}
                    </span>
                  )}
                </div>
              </>
            )}
          </CardContent>
        </ScrollArea>
      </Card>

      {/* ── 사이드 ── */}
      {/* 세로로 넘치면 이 열만 스크롤한다. 시상 카드의 줄 수는 그날 동점 인원에 달려
          있어 화면 높이로 묶어 둘 수 없다 — 없으면 카드가 그대로 늘어나 열 전체가
          바깥으로 밀린다. */}
      <div className="flex min-h-0 flex-col gap-3 overflow-y-auto">
        <Card size="sm">
          <CardContent className="flex flex-col gap-2.5">
            {/* 뉴스 탭의 `보유·관심 종목 소식` 과 같은 제목 스타일이다 — 우측 열의 주인공이라
                `size="sm"` 카드의 기본 제목(12px)으로는 아래 줄과 무게가 같아 제목으로
                읽히지 않는다. */}
            <div className="flex items-center justify-between gap-2">
              <CardTitle className="text-base">공개 스케줄</CardTitle>
              {/* 점수 기준은 여기 접어 둔다. 하루의 공개 리듬을 말하는 카드이고 점수도
                  그 리듬(폐장 정산)에 확정되므로, 궁금해지는 자리가 같다. */}
              <PointRules />
            </div>
            {SCHEDULE.map((item) => (
              <div key={item.time} className="flex flex-col gap-0.5">
                <span className="text-xs font-medium tabular-nums">
                  {item.time}
                </span>
                <span className="text-xs text-foreground">{item.state}</span>
              </div>
            ))}
          </CardContent>
        </Card>

        {featured.length > 0 && (
          <Card size="sm">
            <CardContent className="flex flex-col gap-3">
              <SectionLabel>
                {champions.length > 0
                  ? "SEASON CHAMPION · 3일차 최종"
                  : "INVESTOR OF THE DAY · 17:00 정산 발표"}
              </SectionLabel>
              {/* 등수마다 한 줄이고, 그 등수가 동점이면 "외 N명" 으로 접힌 한 줄이다.
                  공동 수상을 한 명으로 줄이면 나머지가 조용히 사라진다. */}
              {featured.map(({ head, tied }) => (
                <AwardRow
                  key={`${head.seasonDay}-${head.rank}`}
                  nickname={head.nickname}
                  tied={tied}
                  crown={head.rank === 1}
                  // 시즌 우승은 서버 칭호를 쓰되 **옆의 숫자가 무엇인지 함께 적는다** —
                  // 일차 경계마다 자산이 시드로 돌아가 복리 누적이 성립하지 않으므로, 이
                  // 값은 일간 수익률을 그냥 더한 것이다. 포인트 탭의 수익률 열과 같은
                  // 값이고 거기도 같은 말로 설명한다.
                  caption={
                    champions.length > 0
                      ? `${head.title} · 일간 수익률 합`
                      : `오늘의 ${head.rank}위`
                  }
                  returnRate={head.returnRate}
                />
              ))}

              {/* 지난 일차의 시상. 큰 카드는 최근 것 하나만 세우고(시안), 그 앞의
                  일차는 여기 한 줄씩 남긴다 — 시즌이 3일이라 최대 두 줄이다. */}
              {pastAwards.length > 0 && (
                <div className="flex flex-col gap-2 border-t border-border pt-3">
                  <SectionLabel>지난 일차</SectionLabel>
                  {pastAwards.map(({ head, tied }) => (
                    <AwardRow
                      key={head.seasonDay}
                      nickname={head.nickname}
                      tied={tied}
                      crown
                      // 칭호는 일간끼리 모두 같은 말이라 적지 않는다. 지난 줄을 가르는
                      // 것은 며칠차인가 하나뿐이므로 그 자리에 일차를 둔다.
                      caption={`DAY ${head.seasonDay} 우승자`}
                      returnRate={head.returnRate}
                      compact
                    />
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
