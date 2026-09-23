import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
// v10 부터 flat config 는 이 진입점을 쓴다.
import prettier from "eslint-config-prettier/flat";
import simpleImportSort from "eslint-plugin-simple-import-sort";
import storybook from "eslint-plugin-storybook";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  ...storybook.configs["flat/recommended"],
  {
    plugins: { "simple-import-sort": simpleImportSort },
    rules: {
      // 주의: 아래 문자열은 JS 문자열로 한 번, 정규식으로 한 번 해석된다.
      // 역슬래시를 하나만 쓰면 JS 가 먼저 먹어서 의미가 달라진다.
      // 반드시 두 개씩 쓴다.
      "simple-import-sort/imports": [
        "error",
        {
          groups: [
            ["^react", "^next", "^@?\\w"], // 외부 패키지
            ["^@/"], // 내부 절대경로
            ["^\\."], // 상대경로
            // 부수효과 import (import "./globals.css" 처럼 바인딩이 없는 것).
            // simple-import-sort 가 이들에 널 문자 접두사를 붙여 취급하므로,
            // CSS 를 뒤로 보내려면 확장자가 아니라 이 패턴으로 잡아야 한다.
            // ".css$" 같은 그룹은 절대 매칭되지 않는다.
            ["^\\u0000"],
          ],
        },
      ],
      "simple-import-sort/exports": "error",
    },
  },
  // 포맷 규칙은 Prettier 가 담당한다 — 반드시 마지막에 둬야 앞의 규칙을 끈다.
  prettier,
  globalIgnores([
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // 빌드 산출물 — 린트 대상이 아니다. 빠뜨리면 번들 JS 수만 줄이 걸린다.
    "storybook-static/**",
    "coverage/**",
    "test-results/**",
    "playwright-report/**",
  ]),
]);

export default eslintConfig;
