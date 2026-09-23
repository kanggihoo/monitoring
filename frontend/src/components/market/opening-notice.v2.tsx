"use client";

import {
  ChartLine,
  Hourglass,
  List,
  type LucideIcon,
  MessagesSquare,
  Trophy,
} from "lucide-react";
import { useStore } from "zustand";

import { useGameClock } from "@/hooks/use-game-clock";
import { formatCountdown } from "@/lib/game-clock";
import type { Phase } from "@/lib/market-types";
import { cn } from "@/lib/utils";
import { tickStore } from "@/stores/tick-store";

/**
 * 개장 직후 빈 칸 안내 시안 (v2).
 *
 * **문제.** 첫 시즌 첫날 11:00~11:05 는 브리핑 구간이라 아직 재생 전이고, 11:05 에 재생이
 * 시작돼도 **첫 종가가 확정되기까지 약 35초**가 더 걸린다 (`PACE` — 종가 틱 약 35초).
 * 그 1~6분 동안 마켓 화면의 네 칸이 전부 비는데, 지금은 넷이 서로 다른 말을 하거나 아무
 * 말도 하지 않는다:
 *
 * - 종목 목록 — 종목은 뜨지만 값이 전부 `—`
 * - 차트 — `아직 확정된 종가가 없다.` (차트 한가운데 작은 회색 글씨)
 * - 채팅 — **아무것도 없다** (`chat-panel.tsx`: "빈 대화창에는 아무것도 적지 않는다")
 * - 랭킹 — 빈 목록
 *
 * 넷 다 사실이지만 **아무도 "언제 시작되는지"를 말하지 않는다.** 그때 들어온 사람에게는
 * 고장과 구별되지 않는다. 이 시안은 네 칸의 문구를 **한 곳에서 정하고** "무엇을 기다리는
 * 중이고 몇 초 남았는지"를 둔다.
 *
 * **덮는 칸과 얹는 칸이 다르다.** 차트·리더보드·채팅은 지금 정말로 비어 있어 통째로 채우지만
 * (`panel`), 종목 목록은 이름·갈래·관심 별이 이미 제 값이라 덮으면 볼 수 있던 것을 못 보게
 * 된다 — 목록은 그대로 두고 값 자리의 `—` 를 설명하는 한 줄만 위에 얹는다 (`strip`).
 *
 * **남은 시간이 핵심이다.** 스피너는 "돌고 있다"만 말하고 끝을 말하지 않는데, 이 대기는
 * 끝이 정해져 있다 — 그 숫자를 보여주면 기다림이 고장이 아니게 된다. 게임 시계가 이미
 * 세는 값이라 새로 계산하지 않는다 (`use-game-clock.ts` 의 `transition`·`nextCloseSeconds`).
 *
 * 색은 쓰지 않는다. 대기는 경고도 성공도 아니라 무채색이다 (DESIGN.md).
 */
export type OpeningSlot = "instruments" | "chart" | "chat" | "ranking";

/**
 * 칸마다의 아이콘과 문구. **한 곳에 모아 둔다** — 지금 네 칸이 제각각 말하는 것이 이
 * 시안이 고치려는 문제이므로, 문구가 다시 흩어지면 같은 일이 반복된다.
 *
 * 브리핑 구간과 첫 종가 대기는 기다리는 것이 달라 문장이 갈린다 — 앞은 "아직 시작 전",
 * 뒤는 "시작했고 첫 값을 만드는 중"이다. 둘을 한 문장으로 덮으면 11:05 가 지났는지를
 * 화면에서 알 수 없다.
 */
const SLOT: Record<
  OpeningSlot,
  { icon: LucideIcon; briefing: string; firstTick: string; hint: string }
> = {
  /**
   * **목록은 덮지 않는다.** 종목 이름·갈래·관심 별은 첫 종가와 무관하게 이미 제 값이라,
   * 안내로 가리면 볼 수 있던 것을 못 보게 된다. 이 칸만 `strip` 으로 쓰는 이유다.
   *
   * 대신 값 자리의 `—` 를 설명한다. 이 목록은 **상장 예정 종목의 값 자리에 `—` 를 두지
   * 않는데**, "다른 줄과 같은 자리에 같은 빗금이 서면 '값을 못 받았다' 로 읽힌다" 는
   * 이유에서다 (`instrument-list.tsx`). 첫 종가 전의 `—` 도 같은 처지라 한 줄로 밝힌다.
   */
  instruments: {
    icon: List,
    briefing: "11:05 재생이 시작되면 값이 채워집니다",
    firstTick: "첫 종가가 나오면 값이 채워집니다",
    hint: "이름과 갈래는 지금 그대로입니다.",
  },
  chart: {
    icon: ChartLine,
    briefing: "차트는 첫 종가부터 그려집니다",
    firstTick: "첫 종가를 만드는 중입니다",
    hint: "재생이 지날수록 왼쪽부터 선이 이어집니다.",
  },
  chat: {
    icon: MessagesSquare,
    briefing: "아직 아무도 말을 걸지 않았습니다",
    firstTick: "아직 아무도 말을 걸지 않았습니다",
    hint: "먼저 한마디 남겨도 됩니다.",
  },
  ranking: {
    icon: Trophy,
    briefing: "순위는 첫 종가부터 매겨집니다",
    firstTick: "첫 종가를 만드는 중입니다",
    hint: "모두가 같은 시드로 시작합니다.",
  },
};

/**
 * 빈 칸 하나를 채우는 대기 안내.
 *
 * @param slot           어느 칸인가. 아이콘과 문구를 이 값이 정한다
 * @param phase          `BRIEFING` 이면 아직 재생 전, `TRADING` 이면 첫 종가 대기다
 * @param seconds        남은 초. **정지 중이면 `null`** 이고 그때는 숫자를 그리지 않는다 —
 *                       `00:00` 으로 두면 "곧 바뀐다" 와 "멈춰서 안 바뀐다" 를 구별할 수 없다
 *                       (`header-live.tsx` 가 같은 이유로 같은 판단을 한다)
 * @param countdownLabel 무엇까지 남은 시간인지. 브리핑은 `개장까지`, 재생 중은 `첫 종가까지`
 */
export function OpeningNoticeV2({
  slot,
  phase,
  seconds,
  countdownLabel,
  variant = "panel",
  className,
  ...props
}: React.ComponentProps<"div"> & {
  slot: OpeningSlot;
  phase: Phase;
  seconds: number | null;
  countdownLabel: string;
  /**
   * `panel` 은 빈 칸을 통째로 채운다 (차트·리더보드·채팅). `strip` 은 이미 제 내용이 있는
   * 칸 위에 한 줄로 얹는다 — 지금은 종목 목록뿐이다.
   */
  variant?: "panel" | "strip";
}) {
  const { icon: Icon, briefing, firstTick, hint } = SLOT[slot];
  const title = phase === "BRIEFING" ? briefing : firstTick;

  if (variant === "strip") {
    return (
      <div
        role="status"
        className={cn(
          "flex shrink-0 items-center gap-2 rounded-md bg-muted px-2.5 py-2 text-2xs text-muted-foreground",
          className
        )}
        {...props}
      >
        <Icon className="size-3.5 shrink-0" aria-hidden />
        <span className="min-w-0 break-keep">{title}</span>
        {seconds !== null && (
          <span className="ml-auto shrink-0 font-medium tabular-nums">
            {formatCountdown(seconds)}
          </span>
        )}
      </div>
    );
  }

  return (
    <div
      // 칸이 비어 있다는 사실을 화면 낭독기에도 알린다. 대기는 상태이지 오류가 아니므로
      // `alert` 가 아니라 `status` 다.
      role="status"
      // **`w-full` 이 있어야 한다.** 가로로 늘어나는 부모(채팅 목록의 `li` 는 `flex` 다)
      // 안에서는 이 블록이 제 내용 폭(205px)만 차지해 왼쪽에 붙고, 안쪽 `items-center` 는
      // 그 좁은 폭의 가운데라 칸 기준으로는 46px 어긋난다.
      className={cn(
        "flex h-full min-h-0 w-full flex-col items-center justify-center gap-2 px-6 py-8 text-center",
        className
      )}
      {...props}
    >
      <span className="flex size-10 items-center justify-center rounded-full bg-muted">
        <Icon className="size-5 text-muted-foreground" aria-hidden />
      </span>

      {/* `break-keep` 이 필요하다. 한국어는 기본 규칙에서 글자 사이 어디서나 끊겨,
          좁은 칸(종목 목록은 18rem)에서 `첫 종 / 가가` 처럼 어절 한가운데가 갈린다. */}
      <p className="text-sm font-medium break-keep text-foreground">{title}</p>
      <p className="max-w-56 text-xs leading-relaxed text-balance break-keep text-muted-foreground">
        {hint}
      </p>

      {/* 채팅은 기다리는 칸이 아니다 — 첫 종가와 무관하게 지금 바로 쓸 수 있으므로
          카운트다운을 붙이면 "쓸 수 없는 동안"으로 잘못 읽힌다. */}
      {slot !== "chat" && (
        <span className="mt-1 inline-flex items-center gap-1.5 rounded-md bg-accent px-2 py-1 text-xs text-accent-foreground">
          <Hourglass className="size-3.5 shrink-0" aria-hidden />
          {seconds === null ? (
            "재생 정지 중"
          ) : (
            <>
              {countdownLabel}{" "}
              <span className="font-medium tabular-nums">
                {formatCountdown(seconds)}
              </span>
            </>
          )}
        </span>
      )}
    </div>
  );
}

/**
 * 지금이 "첫 종가 전" 인가. **판정을 한 곳에 둔다** — 칸마다 따로 세면 어떤 칸은 안내를 내고
 * 어떤 칸은 안 내는 어긋난 화면이 나온다.
 *
 * 기준은 `awaitingFirstPublish` 다 (`tick-store.ts`: "히스토리를 받았는데 아직 공개된 틱이
 * 없었다 — 1일차 브리핑·되감기 직후"). `baseTickSeq === null` 로 세지 않는 이유는 그 값이
 * **연결이 끊겨 있는 동안에도** null 이기 때문이다 — 단절을 개장 대기로 잘못 말하게 된다
 * (단절은 헤더가 이미 따로 말한다).
 *
 * @returns 대기 중이 아니면 `null`. 대기 중이면 안내에 그대로 넘길 값들
 */
export function useOpeningWait(): {
  phase: Phase;
  seconds: number | null;
  countdownLabel: string;
} | null {
  const clock = useGameClock();
  const awaiting = useStore(tickStore, (state) => state.awaitingFirstPublish);

  if (!awaiting || clock.phase === null) return null;

  // 브리핑은 아직 시작 전이라 "개장까지" 를 세고, 재생 중이면 다음 종가가 곧 첫 종가다.
  return clock.phase === "BRIEFING"
    ? {
        phase: clock.phase,
        seconds: clock.transition.seconds,
        countdownLabel: "개장까지",
      }
    : {
        phase: clock.phase,
        seconds: clock.nextCloseSeconds,
        countdownLabel: "첫 종가까지",
      };
}
