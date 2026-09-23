import { cn } from "@/lib/utils";

/**
 * 종목 카드의 질문형 요약 신호등 (기획 9장).
 * 초보자는 신호등만, 숙련자는 옆의 지표 원값을 본다.
 */
export type SignalLevel = "good" | "fair" | "caution";

const LEVEL = {
  good: { label: "좋음", dot: "bg-signal-good", text: "text-signal-good" },
  fair: { label: "보통", dot: "bg-signal-fair", text: "text-signal-fair" },
  caution: {
    label: "주의",
    dot: "bg-signal-caution",
    text: "text-signal-caution",
  },
} as const satisfies Record<
  SignalLevel,
  { label: string; dot: string; text: string }
>;

/**
 * 판정할 수 없는 칸. **"보통"과 다른 사실이다** (`info.md`) — 근거 지표를 산출할 수 없을
 * 때(상장 초기 종목의 PER 등, 기획 9.1.3) 이 모양으로 그린다. 등급 색을 쓰지 않는 이유가
 * 그것이다: 회색은 세 등급 어디에도 속하지 않는다는 뜻이다.
 */
const UNJUDGED = {
  label: "판정 불가",
  dot: "bg-muted-foreground/40",
  text: "text-muted-foreground",
} as const;

function SignalLight({
  level,
  question,
  className,
  ...props
}: React.ComponentProps<"span"> & {
  /** 근거 지표를 산출할 수 없으면 null. */
  level: SignalLevel | null;
  /** "이 회사가 성장하고 있는가?" 처럼 질문형으로 쓴다. */
  question: string;
}) {
  const { label, dot, text } = level === null ? UNJUDGED : LEVEL[level];
  return (
    // span 이다 — 누르는 신호등(`instrument-info-card`)이 button 안에 넣을 수 있어야 한다.
    <span
      className={cn("flex items-center justify-between gap-2", className)}
      {...props}
    >
      <span className="truncate text-xs text-muted-foreground">{question}</span>
      <span className="flex shrink-0 items-center gap-1.5">
        {/* 점은 장식이다. 등급은 옆의 글자가 말한다 (색상 단독 의존 금지). */}
        <span aria-hidden className={cn("size-2 rounded-full", dot)} />
        <span className={cn("text-xs font-medium", text)}>{label}</span>
      </span>
    </span>
  );
}

export { SignalLight };
