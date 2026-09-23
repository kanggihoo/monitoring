"use client";

import { useLayoutEffect, useState, useSyncExternalStore } from "react";
import { Moon, Sun } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  applyTheme,
  NEXT_THEME,
  readTheme,
  type Theme,
  THEME_KEY,
  THEME_LABELS,
} from "@/lib/theme";
import { cn, FOCUS_RING } from "@/lib/utils";

/** 저장값이 바뀌는 것을 구독할 필요가 없다 — 이 화면에서 바꾸는 사람은 아래 버튼뿐이다. */
const noSubscribe = () => () => {};

function ThemeIcon({ theme }: { theme: Theme }) {
  if (theme === "dark") return <Moon aria-hidden="true" />;
  return <Sun aria-hidden="true" />;
}

/**
 * 테마를 라이트 ↔ 다크 2단계로 돌린다. 화면에 그리는 모양은 둘(헤더의 아이콘 버튼,
 * 폰 `더보기` 시트의 한 줄)이지만 상태를 읽고 쓰는 규칙은 하나여야 하므로 여기 둔다.
 *
 * 서버는 저장된 값을 모르므로 서버 스냅샷을 `null` 로 두고 하이드레이션 후에 읽는다 — 서버와
 * 클라이언트가 다른 아이콘을 그리면 하이드레이션이 어긋난다. 화면 깜빡임은 이 컴포넌트가
 * 아니라 `layout.tsx` 의 인라인 스크립트가 첫 페인트 전에 막는다.
 */
function useThemeToggle() {
  const stored = useSyncExternalStore(noSubscribe, readTheme, () => null);
  // 이번 세션에 사용자가 고른 값. 구독을 하지 않으므로 다시 그리는 건 이쪽 몫이다.
  const [picked, setPicked] = useState<Theme | null>(null);
  const theme = picked ?? stored;

  // 페인트 전에 돈다. 개발 모드의 Strict Mode 재마운트가 <html> 의 class 를 JSX 값으로
  // 되돌려 인라인 스크립트가 얹은 것을 지우므로 여기서 다시 얹는다
  // (프로덕션에서는 같은 값을 덮어쓰는 무해한 재적용이다).
  useLayoutEffect(() => {
    applyTheme(readTheme());
  }, []);

  function cycle() {
    const next = NEXT_THEME[theme ?? readTheme()];
    window.localStorage.setItem(THEME_KEY, next);
    applyTheme(next);
    setPicked(next);
  }

  return { theme, cycle };
}

/** 헤더의 아이콘 버튼. 넓은 화면에서만 보인다 — 폰에서는 `ThemeToggleRow` 가 대신한다. */
export function ThemeToggle() {
  const { theme, cycle } = useThemeToggle();

  return (
    <Button
      variant="ghost"
      size="icon-sm"
      onClick={cycle}
      aria-label={
        theme ? `테마 ${THEME_LABELS[theme]}. 눌러서 바꿉니다` : "테마 바꾸기"
      }
    >
      {theme && <ThemeIcon theme={theme} />}
    </Button>
  );
}

/**
 * 폰 `더보기` 시트의 한 줄.
 *
 * **로그인하지 않아도 닿아야 한다.** 헤더에서 아이콘을 뺀 자리를 사용자 메뉴로 옮기면
 * 미로그인 상태에서는 그 메뉴 자체가 없어 테마를 바꿀 방법이 사라진다 — 공개 열람이
 * 열려 있으므로(ADR-0005) 그 상태로 머무는 사람이 실제로 있다. 시트는 로그인 여부와
 * 무관하게 열린다.
 */
export function ThemeToggleRow({ className }: { className?: string }) {
  const { theme, cycle } = useThemeToggle();

  return (
    <button
      type="button"
      onClick={cycle}
      className={cn(
        FOCUS_RING,
        "flex h-12 w-full items-center gap-3 rounded-lg px-2 text-[15px] text-foreground/80",
        className
      )}
    >
      <span className="flex size-5 items-center justify-center [&_svg]:size-5">
        {theme && <ThemeIcon theme={theme} />}
      </span>
      {theme ? `테마 · ${THEME_LABELS[theme]}` : "테마"}
    </button>
  );
}
