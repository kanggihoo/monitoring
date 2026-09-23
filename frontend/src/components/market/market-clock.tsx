import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { gameMonth } from "@/lib/format";
import { formatCountdown } from "@/lib/game-clock";
import { cn } from "@/lib/utils";

/**
 * 게임 시계 — 서로 다른 세 시계.
 *
 * - 크게: 현재 게임 월
 * - 그 아래: 다음 상태 전환까지 (폐장까지 / 수수료 무료 종료까지)
 * - 작게: 다음 종가까지 — "이 가격으로 살 수 있는 남은 시간". 이 게임에서 서두를 이유가 되는
 *   유일한 정보다.
 *
 * 당일 재생 **종료 지점은 어디에도 없다.** 기획서가 노출을 금지하고 서버도 주지 않는다.
 * 값은 전부 `useGameClock` 이 절대 시각과 오프셋에서 매 렌더링마다 다시 계산한다.
 */
function MarketClock({
  gameMonth: month,
  seasonDay,
  transitionLabel,
  transitionSeconds,
  nextCloseSeconds,
  action,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  /** `YYYY-MM`. 최초 공개 전(1일차 브리핑)에는 null 일 수 있다. */
  gameMonth: string | null;
  /** 진행 중인 일차. 최초 공개 전 개장 준비 안내에 쓴다. */
  seasonDay?: number | null;
  /** "폐장까지" · "수수료 무료 종료까지" 등. 지금 어떤 구간인지는 이 문구가 말한다. */
  transitionLabel: string;
  /** 다음 전환까지 남은 초. 정지 중(개발 환경)이면 null. */
  transitionSeconds: number | null;
  /** 다음 종가까지 남은 초. 재생 중이 아니거나 정지 중이면 null. */
  nextCloseSeconds: number | null;
  /** 제목 옆에 놓을 것. 오늘 브리핑을 다시 여는 버튼이 여기 산다. */
  action?: React.ReactNode;
}) {
  return (
    <Card size="sm" className={cn("w-full", className)} {...props}>
      {/* **phase 배지는 여기 없다.** 상단 바(`header-live.tsx`)가 어느 화면에서나 그
          배지를 들고 있고, 이 카드는 그 바로 아래 놓인다 — 같은 배지가 3cm 간격으로 두 번
          뜨면 둘 중 하나가 다른 것을 말한다고 읽힌다. 지금 어떤 구간인지는 아래 전환 문구
          ("개장까지" · "폐장까지" · "수수료 무료 종료까지")가 이어서 말한다. */}
      <CardHeader className="flex items-center justify-between gap-2">
        <CardTitle className="text-base font-semibold">날짜</CardTitle>
        {action}
      </CardHeader>

      {/* 옆의 두 카드와 같은 높이다 (`portfolio-summary.tsx` 의 같은 주석). */}
      <CardContent className="flex min-h-15 items-center">
        <span className="text-3xl font-semibold text-foreground tabular-nums">
          {month
            ? gameMonth(month)
            : seasonDay != null
              ? `${seasonDay}일차 개장 준비`
              : "개장 준비"}
        </span>
      </CardContent>

      <Separator />

      <CardContent className="flex items-center justify-between gap-2">
        <span className="text-sm text-muted-foreground">{transitionLabel}</span>
        <span className="text-xl font-semibold text-foreground tabular-nums">
          {transitionSeconds === null
            ? "정지"
            : formatCountdown(transitionSeconds)}
        </span>
      </CardContent>

      <CardContent className="flex items-center justify-between gap-2 text-xs">
        <span className="text-muted-foreground">다음 종가까지</span>
        <span
          className="font-medium text-foreground tabular-nums"
          data-testid="next-close-countdown"
        >
          {nextCloseSeconds === null
            ? "—"
            : nextCloseSeconds === 0
              ? "종가 확정 중"
              : formatCountdown(nextCloseSeconds)}
        </span>
      </CardContent>
    </Card>
  );
}

export { MarketClock };
