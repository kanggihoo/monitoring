"use client";

import { usePathname } from "next/navigation";
import { CircleQuestionMark } from "lucide-react";

import { MARKET_TOUR } from "@/components/tour/market-tour";
import { Button } from "@/components/ui/button";
import { requestTour } from "@/lib/tour";

/** 어느 화면에 어느 튜토리얼이 있는지. 없는 화면에서는 버튼을 내지 않는다. */
const TOUR_BY_PATH: Record<string, string> = { "/": MARKET_TOUR };

/**
 * 헤더의 다시 보기. **튜토리얼이 있는 화면에서만 보인다** — 어느 화면에서나 떠 있으면
 * 눌러도 아무 일이 없는 버튼이 되고, 그러면 고장으로 읽힌다.
 */
export function TourReplayButton() {
  const pathname = usePathname();
  const tour = TOUR_BY_PATH[pathname];
  if (!tour) return null;

  return (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-label="튜토리얼 다시 보기"
      onClick={() => requestTour(tour)}
    >
      <CircleQuestionMark aria-hidden />
    </Button>
  );
}
