import { cn } from "@/lib/utils";

/** 라벨 위 / 값 아래의 지표 한 칸. 카드·요약·청약이 같은 모양을 쓴다. */
function Stat({
  label,
  value,
  hint,
  className,
  ...props
}: Omit<React.ComponentProps<"div">, "children"> & {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
}) {
  return (
    <div className={cn("flex flex-col gap-0.5", className)} {...props}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className="text-base font-medium text-foreground tabular-nums">
        {value}
      </span>
      {hint != null && (
        <span className="text-xs text-muted-foreground">{hint}</span>
      )}
    </div>
  );
}

export { Stat };
