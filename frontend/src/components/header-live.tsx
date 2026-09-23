"use client";

import Link from "next/link";
import { WifiOff } from "lucide-react";
import { useStore } from "zustand";

import { PriceChange } from "@/components/market/price-change";
import { useGameClock } from "@/hooks/use-game-clock";
import {
  compactKrw,
  compactPercent,
  compactUsd,
  gameMonth,
} from "@/lib/format";
import { formatCountdown } from "@/lib/game-clock";
import { cn } from "@/lib/utils";
import { tickStore } from "@/stores/tick-store";

/**
 * 상단 바의 주요 지표. 라벨은 위에 작게, 값은 아래에 크게 — 시안의 헤더 그대로다.
 * 전부 스토어에서 오고 Tick 마다 한 번에 바뀐다. 값이 없으면(연결 전) 자리만 비워 둔다.
 *
 * **개인 지표(자산·현금·당일·순위)는 미인증에 로그인 안내로 바뀐다** — 빈 값으로 두면 "데이터가
 * 없는 것" 과 "권한이 없는 것" 을 구별할 수 없다 (ADR-0005). 공개 지표(게임 시계·환율)는 그대로다.
 */
export function HeaderLive({ isLoggedIn }: { isLoggedIn: boolean }) {
  const clock = useGameClock();
  const tickSeq = useStore(tickStore, (state) => state.tickSeq);
  /** 이 연결의 시세 기준이 섰는가. 붙기만 하고 아직 못 선 구간을 단절과 가른다. */
  const hydrated = useStore(tickStore, (state) => state.baseTickSeq !== null);
  /** 최초 공개 전 틱 대기 여부. 1일차 브리핑이나 첫 틱 전에는 이것이 참이다. */
  const awaitingFirstPublish = useStore(
    tickStore,
    (state) => state.awaitingFirstPublish
  );
  const valuation = useStore(tickStore, (state) => state.valuation);
  const myRank = useStore(tickStore, (state) => state.myRank);
  const usdKrwRate = useStore(tickStore, (state) => state.usdKrwRate);

  return (
    // 연결 상태와 Tick 번호는 **화면에 내지 않고 속성으로만 남긴다.** 사람에게는 늘 켜져
    // 있는 표시라 읽을 것이 없고, e2e 는 이 값으로 Tick 이 흐르는지를 본다.
    <div
      className="flex min-w-0 flex-1 items-center gap-6 overflow-x-auto"
      data-testid="ws-status"
      data-ws-connected={clock.connected ? "true" : "false"}
      data-ws-synced={clock.connected && hydrated ? "true" : "false"}
      data-ws-tick-seq={tickSeq > 0 ? tickSeq : ""}
    >
      <Metric label="Replay date">
        <span className="flex items-center gap-2">
          <span className="text-base font-semibold tabular-nums">
            {clock.gameMonth ? gameMonth(clock.gameMonth) : "—"}
          </span>
          {clock.nextCloseSeconds !== null ? (
            <span className="text-xs text-muted-foreground tabular-nums">
              다음 종가 {formatCountdown(clock.nextCloseSeconds)}
            </span>
          ) : clock.phase === "TRADING" && clock.transition.seconds !== null ? (
            /* 16:29~16:40 꼬리 시간: 틱은 끝났지만 16:40 폐장 전까지 여전히 매매 가능 */
            <span className="text-xs font-medium text-amber-500 tabular-nums">
              폐장까지 {formatCountdown(clock.transition.seconds)}
            </span>
          ) : clock.phase === "BRIEFING" &&
            clock.transition.seconds !== null ? (
            <span className="text-xs text-muted-foreground tabular-nums">
              개장까지 {formatCountdown(clock.transition.seconds)}
            </span>
          ) : clock.phase === "SETTLING" &&
            clock.transition.seconds !== null ? (
            <span className="text-xs text-muted-foreground tabular-nums">
              정산 발표까지 {formatCountdown(clock.transition.seconds)}
            </span>
          ) : null}
        </span>
      </Metric>

      <Divider />

      {/* 버튼을 여기 두지 않는다 — 같은 헤더 오른쪽 끝에 로그인 버튼이 이미 있고, 둘이면
          같은 행동의 진입점이 둘로 보인다. 여기 필요한 것은 왜 비어 있는지에 대한 설명이다. */}
      {!isLoggedIn && (
        <span className="shrink-0 text-xs whitespace-nowrap text-muted-foreground">
          내 자산과 순위는 로그인 후에 보입니다
        </span>
      )}

      {isLoggedIn && (
        <>
          {/* **훑어보는 자리라 줄여 쓴다** — `482.5억원` · `+4.8만%`. 정확한 값은 내 계좌가
              쉼표까지 그대로 보여주고, 그래서 이 묶음이 그리로 가는 링크다 (마우스가 없는
              화면에서도 닿아야 한다). 낭독기에는 `PriceChange` 가 정확한 값을 읽어 준다. */}
          <Link
            href="/mypage"
            className="flex shrink-0 items-center gap-6 rounded-sm outline-offset-4"
            title="정확한 금액은 내 계좌에서 봅니다"
          >
            <Metric label="Total assets">
              <span className="flex items-baseline gap-2">
                <span className="text-base font-semibold tabular-nums">
                  {valuation ? compactKrw(valuation.totalValuationKrw) : "—"}
                </span>
                {valuation && (
                  <PriceChange
                    value={valuation.cumulativeReturn}
                    format={compactPercent}
                    className="text-xs"
                  />
                )}
              </span>
            </Metric>

            <Metric label="Cash" className="hidden xl:flex">
              <span className="flex items-baseline gap-2 tabular-nums">
                <span className="font-medium">
                  {valuation ? compactKrw(valuation.krwCash) : "—"}
                </span>
                {valuation && (
                  <span className="text-xs text-muted-foreground">
                    {compactUsd(valuation.usdCash)}
                  </span>
                )}
              </span>
            </Metric>

            <Metric label="Today" className="hidden lg:flex">
              {valuation ? (
                <PriceChange
                  value={valuation.dailyReturn}
                  format={compactPercent}
                  className="text-base"
                />
              ) : (
                <span className="font-medium">—</span>
              )}
            </Metric>
          </Link>

          <Divider />

          <Metric label="Rank">
            {/* **순위가 없는 두 이유를 가른다.** 암전 중에는 서버가 순위를 `null` 로 주고,
                그것이 "암전" 이다. 암전이 풀렸는데도 `null` 이면 아직 다시 읽지 못한 것이고
                (확정 직후의 조회 실패), 그때까지 "암전" 이라고 적으면 화면이 거짓을 말한다.
                정확한 순위와 다시 시도는 랭킹 화면이 갖고 있다. */}
            <span className="font-semibold tabular-nums">
              {myRank?.seasonRank != null
                ? `#${myRank.seasonRank}`
                : clock.leaderboardBlackout
                  ? "암전"
                  : "—"}
            </span>
          </Metric>
        </>
      )}

      <Metric label="USD/KRW">
        <span className="font-medium tabular-nums">{usdKrwRate ?? "—"}</span>
      </Metric>

      {/* **정상일 때는 아무것도 그리지 않는다** — 늘 켜져 있는 표시는 읽을 것이 없다는
          위의 판단 그대로다. 다만 끊긴 구간은 다르다: 화면의 값이 전부 옛것인데 카운트다운만
          계속 돌아 멀쩡해 보이고, 주문 패널이 잠긴 이유도 여기 있다. 그때만 말한다.
          끊긴 것과 아직 기준이 서지 않은 것을 가르는 이유는 기다릴 시간이 달라서다.
          단, 최초 틱 발행 전(1일차 브리핑 또는 첫 틱 대기)에는 기준 틱이 없는 것이 정상이므로
          연결만 정상이라면 '동기화 중' 경고를 띄우지 않는다. */}
      {!(clock.connected && (hydrated || awaitingFirstPublish)) && (
        <span className="ml-auto flex shrink-0 items-center gap-1.5 text-xs whitespace-nowrap text-signal-caution">
          <WifiOff className="size-3.5" aria-hidden />
          {clock.connected ? "동기화 중" : "연결 끊김 · 다시 연결하는 중"}
        </span>
      )}
    </div>
  );
}

function Metric({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex shrink-0 flex-col gap-0.5 whitespace-nowrap",
        className
      )}
    >
      <span className="text-2xs tracking-wider text-muted-foreground uppercase">
        {label}
      </span>
      <span className="text-sm leading-none">{children}</span>
    </div>
  );
}

function Divider() {
  return <span aria-hidden className="hidden h-8 w-px bg-border lg:block" />;
}
