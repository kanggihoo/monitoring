import { formatCountdown } from "@/lib/game-clock";
import { cn } from "@/lib/utils";

/**
 * 수수료 무료 타임 배너 (기획 6.7). 12:00~12:10 한 번이고 거래 수수료만 0% 다 —
 * 환전 수수료는 대상이 아니다. 할증 이벤트는 v1.0 에 없다.
 *
 * 색은 쓰지 않는다 — 무료는 무채색 `accent` 면이다. 초록을 쓰면 DESIGN.md 의
 * "초록은 신호등 한 곳" 원칙이 깨진다.
 */
function FeeEventBanner({
  remainingSeconds,
  className,
  ...props
}: React.ComponentProps<"div"> & {
  /** 무료 종료까지 남은 초. 정지 중이면 null. */
  remainingSeconds: number | null;
}) {
  return (
    <div
      role="status"
      className={cn(
        "flex w-full items-center gap-3 rounded-md border border-border bg-accent px-3 py-2 text-accent-foreground",
        className
      )}
      {...props}
    >
      <div className="flex min-w-0 flex-col">
        <span className="text-sm font-medium">수수료 무료 타임</span>
      </div>
      <div className="ml-auto flex flex-col items-end">
        <span className="text-base font-semibold tabular-nums">
          {remainingSeconds === null
            ? "정지"
            : formatCountdown(remainingSeconds)}
        </span>
        <span className="text-2xs text-muted-foreground">종료까지</span>
      </div>
    </div>
  );
}

export { FeeEventBanner };
