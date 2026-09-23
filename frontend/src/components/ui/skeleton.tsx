import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * 로딩 중 자리를 잡아 두는 블록. 모양은 `className` 으로 준다 (`h-4 w-24`).
 *
 * 맥동은 `motion-safe:` 로만 건다. Accessibility Contract 가 `prefers-reduced-motion` 대응을
 * 요구하는데(`DESIGN.md`), 이 앱에서 움직이는 요소가 이것이 처음이라 기댈 전역 차단 장치가 없다.
 *
 * 스스로 의미를 갖지 않는 장식이라 읽어 주지 않는다. 무엇을 기다리는지 알리는 것은 이 블록을
 * 놓는 쪽의 몫이다 — 각 `loading.tsx` 가 화면 단위로 한 번만 알린다.
 */
function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      aria-hidden
      className={cn("rounded-sm bg-muted motion-safe:animate-pulse", className)}
      {...props}
    />
  );
}

export { Skeleton };
