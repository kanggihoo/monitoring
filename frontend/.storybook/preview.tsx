import { useEffect } from "react";
import type { Preview } from "@storybook/nextjs-vite";

import { pretendard } from "../src/fonts/pretendard";
import { cn } from "../src/lib/utils";

import "../src/app/globals.css";

// Tailwind v4 와 shadcn 테마 토큰(--background, --primary ...)을 스토리에도 적용한다.
// 이걸 빠뜨리면 스토리가 스타일 없이 렌더된다.
//
// Storybook 은 layout.tsx 를 실행하지 않으므로 next/font 가 만드는 --font-sans 도 없다.
// 앱과 같은 폰트 설정을 여기서 직접 걸어 준다. 빠뜨리면 스토리의 라틴 글자·숫자가
// 브라우저 기본 세리프로 렌더돼 타이포를 검증할 수 없다.

const preview: Preview = {
  parameters: {
    nextjs: {
      appDirectory: true,
    },
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    a11y: {
      // 'todo' - a11y 위반을 테스트 UI 에만 표시 / 'error' - CI 실패 / 'off' - 검사 안 함
      test: "todo",
    },
  },
  globalTypes: {
    theme: {
      description: "라이트/다크 테마",
      toolbar: {
        icon: "circlehollow",
        items: [
          { value: "light", title: "라이트" },
          { value: "dark", title: "다크" },
        ],
        dynamicTitle: true,
      },
    },
  },
  initialGlobals: {
    theme: "light",
  },
  decorators: [
    (Story, context) => {
      // 다크 변형은 `&:is(.dark *)` 이라 조상에 .dark 가 있어야 한다.
      // 반대로 라이트는 .light 를 명시해야 한다 — 토큰 기본값이 OS 설정을 따르므로,
      // 걸어 두지 않으면 다크 OS 를 쓰는 사람에게 라이트 스토리가 다크로 뜬다.
      const dark = context.globals.theme === "dark";
      // Docs 페이지는 한 iframe 에 그 컴포넌트의 모든 스토리를 함께 그린다.
      // <html> 에 걸면 마지막에 렌더된 스토리의 테마가 문서 전체를 덮어써서,
      // 툴바가 라이트여도 다크 스토리 하나 때문에 페이지 전체가 어두워진다.
      const isDocs = context.viewMode === "docs";
      useEffect(() => {
        const root = document.documentElement;
        root.classList.add("font-sans", pretendard.variable);
        // docs 에서는 <html> 을 항상 라이트로 둔다 (문서 자체의 바탕).
        root.classList.toggle("dark", !isDocs && dark);
        root.classList.toggle("light", isDocs || !dark);
      }, [dark, isDocs]);
      return (
        // story 모드: 테마는 <html> 이 이미 들고 있고 body 가 배경을 칠한다.
        //   래퍼가 박스를 만들면 layout: centered/fullscreen 이 깨지므로 contents 로 둔다.
        // docs 모드: 스토리마다 자기 테마를 들고 자기 배경을 직접 칠한다.
        <div
          className={
            isDocs
              ? cn(dark && "dark", "bg-background text-foreground")
              : cn(dark && "dark", "contents")
          }
        >
          <Story />
        </div>
      );
    },
  ],
};

export default preview;
