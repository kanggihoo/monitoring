/**
 * 테마 설정. 값을 담는 브라우저 로컬 저장소 키 — 쓰는 쪽은 `components/theme-toggle.tsx`,
 * 첫 페인트 전에 읽는 쪽은 `app/layout.tsx` 의 인라인 스크립트다.
 */
export const THEME_KEY = "theme";

/**
 * 고를 수 있는 값. 라이트 ↔ 다크 2단계 토글이다.
 */
export const THEMES = ["light", "dark"] as const;

export type Theme = (typeof THEMES)[number];

/** 토글이 도는 순서 (라이트 ↔ 다크). */
export const NEXT_THEME: Record<Theme, Theme> = {
  light: "dark",
  dark: "light",
};

export const THEME_LABELS: Record<Theme, string> = {
  light: "라이트",
  dark: "다크",
};

/**
 * `<html>` 에 테마 클래스를 얹는다.
 */
export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  root.classList.toggle("dark", theme === "dark");
  root.classList.toggle("light", theme === "light");
}

/** 저장된 선택. 값이 없으면 OS 기본 설정을 확인해 라이트/다크를 결정한다. */
export function readTheme(): Theme {
  if (typeof window === "undefined") return "light";
  try {
    const stored = window.localStorage.getItem(THEME_KEY);
    if (stored === "light" || stored === "dark") return stored;
    return window.matchMedia("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light";
  } catch {
    return "light";
  }
}
