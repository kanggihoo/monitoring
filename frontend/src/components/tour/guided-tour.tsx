"use client";

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export type TourStep = {
  /** 가리킬 요소의 `data-tour` 값. 그 요소가 없으면 화면 가운데에 그냥 띄운다. */
  target: string;
  title: string;
  body: string;
  /** 자리가 모자라면 반대쪽으로 뒤집는다. 기본은 아래. */
  placement?: Placement;
};

type Placement = "top" | "bottom" | "left" | "right";
type Rect = { top: number; left: number; width: number; height: number };

/** 구멍과 대상 사이에 두는 숨 틈. 테두리에 딱 붙으면 무엇을 가리키는지 덜 읽힌다. */
const SPOTLIGHT_PADDING = 6;
/** 말풍선과 대상 사이의 간격, 그리고 말풍선과 화면 가장자리 사이의 최소 간격. */
const GAP = 12;
const EDGE = 16;

const OPPOSITE = {
  top: "bottom",
  bottom: "top",
  left: "right",
  right: "left",
} as const;

function findTarget(target: string): Element | null {
  return document.querySelector(`[data-tour="${target}"]`);
}

function rectOf(node: Element | null): Rect | null {
  if (!node) return null;
  const { top, left, width, height } = node.getBoundingClientRect();
  // 자리를 차지하지 않는 요소는 가리킬 것이 없다 (좁은 화면에서 접힌 칸).
  if (width === 0 && height === 0) return null;
  return { top, left, width, height };
}

/**
 * 말풍선 자리. 원하는 쪽에 자리가 모자라면 반대쪽으로 뒤집고, 그래도 넘치면 화면 안으로
 * 끌어당긴다 — **뒤집기만 해서는 좁은 화면에서 말풍선이 밖으로 나간다.**
 */
export function place(
  rect: Rect,
  size: { width: number; height: number },
  placement: Placement,
  viewport: { width: number; height: number }
): { top: number; left: number } {
  const room = {
    top: rect.top,
    bottom: viewport.height - (rect.top + rect.height),
    left: rect.left,
    right: viewport.width - (rect.left + rect.width),
  };
  const vertical = placement === "top" || placement === "bottom";
  const need = (vertical ? size.height : size.width) + GAP + EDGE;
  const side =
    room[placement] < need && room[OPPOSITE[placement]] >= need
      ? OPPOSITE[placement]
      : placement;

  let top: number;
  let left: number;
  if (side === "top" || side === "bottom") {
    top =
      side === "top"
        ? rect.top - size.height - GAP
        : rect.top + rect.height + GAP;
    left = rect.left + rect.width / 2 - size.width / 2;
  } else {
    left =
      side === "left"
        ? rect.left - size.width - GAP
        : rect.left + rect.width + GAP;
    top = rect.top + rect.height / 2 - size.height / 2;
  }

  const clamp = (value: number, extent: number, span: number) =>
    Math.min(Math.max(value, EDGE), Math.max(EDGE, extent - span - EDGE));

  return {
    top: clamp(top, viewport.height, size.height),
    left: clamp(left, viewport.width, size.width),
  };
}

/**
 * 단계별 안내. 대상 요소 하나를 밝히고 그 옆에 설명을 붙인다.
 *
 * 자리는 **매 단계 다시 잰다** — 스크롤·리사이즈로 레이아웃이 움직이므로 한 번 잰 좌표를
 * 들고 있으면 말풍선이 엉뚱한 곳을 가리킨다.
 *
 * 끝내는 길은 셋이고 모두 `onClose` 로 간다: 마지막 단계의 `완료`, 닫기(X), `Esc`.
 * **덮개를 눌러서는 닫지 않는다** — 다음으로 넘기려다 오발로 닫는 일이 잦다.
 *
 * `onComplete` 는 그중 `완료` 로 끝났을 때만 덧붙여 부른다 (`onClose` 는 그 경우에도 온다).
 * **끝까지 본 것과 도중에 닫은 것을 가르려는 것이다** — 2/6 에서 나간 사람은 "됐고 그냥
 * 볼래" 라고 말한 것이라, 끝까지 본 사람에게만 할 일을 그 사람에게 하면 안 된다.
 */
export function GuidedTour({
  steps,
  open,
  onClose,
  onComplete,
  label = "튜토리얼",
}: {
  steps: TourStep[];
  open: boolean;
  onClose: () => void;
  /** 마지막 단계의 `완료` 로 끝났을 때만. 도중에 닫으면 오지 않는다 */
  onComplete?: () => void;
  label?: string;
}) {
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState<Rect | null>(null);
  const [spot, setSpot] = useState<{ top: number; left: number } | null>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);
  const step = steps[index];

  // 열릴 때마다 처음부터. 닫았다 다시 부른 사람이 보던 단계에서 이어지면 어리둥절하다.
  // 이펙트가 아니라 렌더 중에 맞춘다 — 이펙트로 되돌리면 옛 단계가 한 번 그려졌다 바뀐다.
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) setIndex(0);
  }

  const measure = useCallback(() => {
    if (!step) return;
    const next = rectOf(findTarget(step.target));
    const tooltip = tooltipRef.current;
    setRect(next);
    if (!tooltip) return;
    const size = { width: tooltip.offsetWidth, height: tooltip.offsetHeight };
    const viewport = { width: window.innerWidth, height: window.innerHeight };
    setSpot(
      next
        ? place(next, size, step.placement ?? "bottom", viewport)
        : {
            top: Math.max(EDGE, (viewport.height - size.height) / 2),
            left: Math.max(EDGE, (viewport.width - size.width) / 2),
          }
    );
  }, [step]);

  // 대상이 화면 밖이면 끌어온다. 재기보다 먼저다 — 스크롤한 뒤의 좌표를 재야 한다.
  useLayoutEffect(() => {
    if (!open || !step) return;
    findTarget(step.target)?.scrollIntoView({
      block: "nearest",
      inline: "nearest",
    });
    // 한 프레임 뒤에 잰다. 이 단계의 말풍선이 아직 안 그려졌으면 크기가 지난 단계의 것이고,
    // 스크롤도 아직 자리를 잡지 않았다.
    const frame = requestAnimationFrame(measure);
    return () => cancelAnimationFrame(frame);
  }, [open, step, measure]);

  useEffect(() => {
    if (!open) return;
    const onChange = () => measure();
    window.addEventListener("resize", onChange);
    // 캡처로 듣는다 — 스크롤은 안쪽 상자(`ScrollArea`)에서 나고 window 로 버블하지 않는다.
    window.addEventListener("scroll", onChange, true);
    return () => {
      window.removeEventListener("resize", onChange);
      window.removeEventListener("scroll", onChange, true);
    };
  }, [open, measure]);

  const last = index === steps.length - 1;
  const goNext = useCallback(() => {
    if (!last) {
      setIndex((i) => i + 1);
      return;
    }
    // 닫는 일이 먼저다. 권유가 열리기 전에 이 덮개가 사라져야 둘이 겹치지 않는다.
    onClose();
    onComplete?.();
  }, [last, onClose, onComplete]);
  const goPrev = useCallback(() => setIndex((i) => Math.max(0, i - 1)), []);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
      else if (event.key === "ArrowRight") goNext();
      else if (event.key === "ArrowLeft") goPrev();
      else if (event.key === "Tab") {
        // 덮개 뒤의 화면으로 포커스가 새지 않게 말풍선 안에 가둔다.
        event.preventDefault();
        const focusable = tooltipRef.current?.querySelectorAll<HTMLElement>(
          "button:not(:disabled)"
        );
        if (!focusable || focusable.length === 0) return;
        const list = Array.from(focusable);
        const at = list.indexOf(document.activeElement as HTMLElement);
        const delta = event.shiftKey ? -1 : 1;
        list[(at + delta + list.length) % list.length]?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onClose, goNext, goPrev]);

  if (!open || !step) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-50"
      role="dialog"
      aria-modal="true"
      aria-label={label}
    >
      {/* 구멍 하나로 화면을 덮는다 — 네 장을 이어 붙이면 모서리에 이음선이 보인다.
          `pointer-events-none` 이라 밝힌 자리도 덮개도 눌리지 않는다. */}
      <div
        data-testid="tour-spotlight"
        className="pointer-events-none fixed rounded-xl shadow-[0_0_0_9999px_rgba(0,0,0,0.6)] transition-all duration-200"
        style={
          rect
            ? {
                top: rect.top - SPOTLIGHT_PADDING,
                left: rect.left - SPOTLIGHT_PADDING,
                width: rect.width + SPOTLIGHT_PADDING * 2,
                height: rect.height + SPOTLIGHT_PADDING * 2,
              }
            : { top: "50%", left: "50%", width: 0, height: 0 }
        }
      />

      <div
        ref={tooltipRef}
        className={cn(
          "fixed w-[min(20rem,calc(100vw-2rem))] rounded-xl border border-border bg-card p-4 shadow-lg",
          // 재기 전에 보이면 왼쪽 위에서 한 번 깜빡인다.
          spot ? "opacity-100" : "opacity-0"
        )}
        style={spot ?? { top: 0, left: 0 }}
      >
        <div className="flex items-start justify-between gap-3">
          <h2 className="text-base font-semibold tracking-tight">
            {step.title}
          </h2>
          <Button
            variant="ghost"
            size="icon-xs"
            onClick={onClose}
            aria-label="튜토리얼 닫기"
          >
            <X aria-hidden />
          </Button>
        </div>

        <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">
          {step.body}
        </p>

        <div className="mt-4 flex items-center justify-between gap-3">
          <span className="text-xs text-muted-foreground tabular-nums">
            {index + 1} / {steps.length}
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={goPrev}
              disabled={index === 0}
            >
              이전
            </Button>
            <Button size="sm" onClick={goNext} autoFocus>
              {last ? "완료" : "다음"}
            </Button>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
}
