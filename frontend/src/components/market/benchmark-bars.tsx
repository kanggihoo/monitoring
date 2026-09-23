import { PriceChange } from "@/components/market/price-change";
import type { Benchmark } from "@/lib/market-types";
import { cn } from "@/lib/utils";

/**
 * 비교 기준선 (기획 13.3.3, "가만히 있었다면 vs 나").
 *
 * 막대 길이는 **최대 절댓값 기준 상대 비율**이라 값들 사이의 크기 비교만 뜻한다 — 절대
 * 수치는 항상 옆의 숫자가 말한다. 음수는 0 축 왼쪽으로 뻗는다.
 *
 * `me` 는 계약이 `benchmarks` 배열 안에 함께 넣어 준 항목이므로(`rpt.md`) 여기서 따로
 * 받지 않고 라벨로 판별한다.
 */
function BenchmarkBars({
  benchmarks,
  meLabel = "나",
  className,
  ...props
}: Omit<React.ComponentProps<"div">, "children"> & {
  benchmarks: Benchmark[];
  meLabel?: string;
}) {
  const scale = Math.max(...benchmarks.map((b) => Math.abs(b.returnRate)), 1);

  return (
    <div className={cn("flex flex-col gap-2.5", className)} {...props}>
      {benchmarks.map((benchmark) => {
        const isMe = benchmark.label === meLabel;
        const width = `${(Math.abs(benchmark.returnRate) / scale) * 50}%`;
        return (
          <div key={benchmark.label} className="flex flex-col gap-1">
            <div className="flex items-baseline justify-between gap-2">
              <span
                className={cn(
                  "text-xs",
                  isMe ? "font-medium text-foreground" : "text-muted-foreground"
                )}
              >
                {benchmark.label}
              </span>
              <PriceChange value={benchmark.returnRate} />
            </div>
            {/* 0 축을 가운데 두고 양쪽으로 뻗는다. 축이 없으면 음수 막대가 양수처럼 보인다. */}
            <div className="relative h-1.5 rounded-full bg-muted">
              <span
                aria-hidden
                className={cn(
                  "absolute inset-y-0 rounded-full",
                  benchmark.returnRate < 0 ? "bg-price-down" : "bg-price-up",
                  isMe ? "opacity-100" : "opacity-45"
                )}
                style={
                  benchmark.returnRate < 0
                    ? { right: "50%", width }
                    : { left: "50%", width }
                }
              />
            </div>
          </div>
        );
      })}
    </div>
  );
}

export { BenchmarkBars };
