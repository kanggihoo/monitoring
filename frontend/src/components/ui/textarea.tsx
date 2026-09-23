import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * 여러 줄 입력. `Input` 과 같은 테두리·포커스 링·오류 표시를 쓴다 — 한 화면에 둘이 나란히
 * 놓이므로 모양이 갈리면 그것이 먼저 보인다.
 *
 * Base UI 에는 textarea 프리미티브가 없어 순수 `<textarea>` 를 쓴다 (`Input` 만 감싸져 있다).
 *
 * 높이는 `field-sizing-content` 로 내용에 맞춰 늘어난다. 스크롤 안에서 긴 글을 쓰면 방금 쓴
 * 줄이 화면 밖으로 밀린다.
 */
function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "field-sizing-content min-h-20 w-full rounded-md border border-input bg-muted px-3 py-2 text-base transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-1 aria-invalid:ring-destructive/20 md:text-sm dark:disabled:bg-input/80 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  );
}

export { Textarea };
