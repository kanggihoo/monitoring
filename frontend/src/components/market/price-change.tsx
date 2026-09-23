import { cva, type VariantProps } from "class-variance-authority";

import { signed } from "@/lib/format";
import { cn } from "@/lib/utils";

/**
 * 등락 표기.
 *
 * DESIGN.md 의 "색상 단독 등락 표시 금지" 규칙을 이 컴포넌트 하나로 지킨다.
 * 색(price-up/price-down) + 부호(+/−) + 기호(▲/▼) 를 항상 함께 낸다.
 * 등락을 쓰는 곳은 전부 이걸 거쳐야 색맹 사용자에게도 방향이 전달된다.
 */
const priceChangeVariants = cva(
  "inline-flex items-center gap-1 font-medium whitespace-nowrap tabular-nums",
  {
    variants: {
      direction: {
        up: "text-price-up",
        down: "text-price-down",
        flat: "text-muted-foreground",
      },
      variant: {
        text: "",
        badge: "h-5 rounded-sm px-1.5 text-xs",
      },
    },
    compoundVariants: [
      { variant: "badge", direction: "up", className: "bg-price-up-surface" },
      {
        variant: "badge",
        direction: "down",
        className: "bg-price-down-surface",
      },
      { variant: "badge", direction: "flat", className: "bg-muted" },
    ],
    defaultVariants: { direction: "flat", variant: "text" },
  }
);

const MARK = { up: "▲", down: "▼", flat: "—" } as const;
const SPOKEN = { up: "상승", down: "하락", flat: "보합" } as const;

type Direction = keyof typeof MARK;

const directionOf = (value: number): Direction =>
  value > 0 ? "up" : value < 0 ? "down" : "flat";

type PriceChangeProps = Omit<
  React.ComponentProps<"span">,
  "children" | "color"
> &
  Pick<VariantProps<typeof priceChangeVariants>, "variant"> & {
    /** 등락률(%) 또는 등락폭. `unit` 으로 어느 쪽인지 정한다. */
    value: number;
    /** 값 뒤에 붙는 단위. 등락폭이면 "원" 등을 넘긴다. */
    unit?: string;
    /** 소수 자릿수. 등락폭처럼 정수로 읽는 값은 0 을 넘긴다. */
    digits?: number;
    /**
     * 보이는 문구를 대신 만든다. **헤더처럼 칸이 좁은 곳에서 축약할 때만 넘긴다** —
     * `format.ts` 의 `compactPercent` 가 그것이다.
     *
     * 낭독기에 가는 `aria-label` 은 바꾸지 않는다. 눈으로는 줄여 보여주더라도 값 자체는
     * 정확하게 읽혀야 한다.
     */
    format?: (value: number) => string;
  };

function PriceChange({
  value,
  unit = "%",
  digits = 2,
  format,
  variant,
  className,
  ...props
}: PriceChangeProps) {
  const direction = directionOf(value);
  const body = format ? format(value) : `${signed(value, digits)}${unit}`;
  const spoken = `${Math.abs(value).toFixed(digits)}${unit} ${SPOKEN[direction]}`;
  return (
    <span
      className={cn(priceChangeVariants({ direction, variant }), className)}
      {...props}
    >
      {/* 화면 낭독기에는 기호 대신 말로 방향을 준다 ("▲" 는 읽히지 않는다). generic span에 aria-label은 금지 속성이므로 sr-only로 처리한다. */}
      <span className="sr-only">{spoken}</span>
      <span aria-hidden className="text-[0.85em] leading-none">
        {MARK[direction]}
      </span>
      <span aria-hidden>{body}</span>
    </span>
  );
}

export { PriceChange, priceChangeVariants };
