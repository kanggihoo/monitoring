#!/usr/bin/env node
/**
 * DESIGN.md → src/app/globals.css (shadcn 정규형)
 *
 * 사용법
 *   node scripts/build-theme.mjs           생성 (pnpm theme)
 *   node scripts/build-theme.mjs --check   생성물이 최신인지만 확인 (pnpm theme:check)
 *
 * 왜 CLI export 를 그대로 쓰지 않는가
 *  - export 는 `@theme { --color-background: #fff }` 를 평탄하게 뱉는다. shadcn 은
 *    `:root` / `.dark` 의 bare 이름(`--background`)과 `@theme inline` 의 간접 참조를
 *    전제로 하고, `npx shadcn add` 도 그 형태로 globals.css 를 패치한다.
 *  - export 는 1층 primitive 까지 전부 내보낸다. 코드에서 `bg-ink` 를 쓸 수 있게 되면
 *    그 자리만 다크에서 안 바뀐다(1층에는 .dark 오버라이드가 없다).
 *  - export 는 lineHeight 를 버린다. dtcg 포맷도 마찬가지다(실측 확인).
 *
 * 그래서 값 해석은 CLI 에 맡기고, 분류와 배치만 여기서 한다.
 *  1. designmd export 로 참조를 해석한 실제 값을 얻는다
 *  2. DESIGN.md 의 YAML 로 1층/2층을 가른다 — 값이 `{colors.*}` 참조면 2층
 *  3. `-dark` 접미를 떼어 .dark 블록으로 접는다
 *
 * 토큰 이름 목록을 이 파일에 두지 않으므로, DESIGN.md 에 토큰을 추가해도 여기는 안 고친다.
 */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { format, resolveConfig } from "prettier";
import { parse } from "yaml";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SRC = join(ROOT, "DESIGN.md");
const OUT = join(ROOT, "src/app/globals.css");
const CLI = join(ROOT, "node_modules/@google/design.md/dist/index.js");

const DARK = "-dark";
/** bare `--radius` 가 가리킬 단계. shadcn 등록 컴포넌트가 var(--radius) 를 직접 읽는다. */
const BASE_RADIUS = "md";

const checkOnly = process.argv.includes("--check");
const designmd = (...args) =>
  execFileSync("node", [CLI, ...args, SRC], { encoding: "utf8" });

// ── 0. lint — 경고도 실패로 취급한다 ───────────────────────────────────
// CLI 는 경고가 있어도 exit 0 이라(--strict 플래그 없음) 여기서 막는다.
// 대비 미달 같은 결함이 조용히 CSS 로 흘러나가지 않게 하는 것이 목적이다.
const report = JSON.parse(designmd("lint", "--format", "json"));
const blocking = report.findings.filter((f) => f.severity !== "info");
if (blocking.length) {
  console.error("DESIGN.md lint 실패 — 경고도 통과시키지 않는다:\n");
  for (const f of blocking) {
    console.error(
      `  [${f.severity}] ${f.rule}${f.path ? ` (${f.path})` : ""}\n    ${f.message}`
    );
  }
  process.exit(1);
}

// ── 1. YAML front matter ──────────────────────────────────────────────
const raw = readFileSync(SRC, "utf8");
const fm = raw.match(/^---\r?\n([\s\S]*?)\r?\n---/);
if (!fm) throw new Error(`${SRC}: YAML front matter 를 찾지 못했다.`);
const design = parse(fm[1]);

/** 2층 semantic = 값이 다른 색 토큰을 가리키는 참조인 것. 1층은 raw hex 라 제외된다. */
const isRef = (v) => typeof v === "string" && v.startsWith("{colors.");
const semantic = Object.keys(design.colors ?? {}).filter((k) =>
  isRef(design.colors[k])
);

// ── 2. CLI 로 값 해석 ──────────────────────────────────────────────────
const exported = designmd("export", "--format", "css-tailwind");
const resolved = new Map(
  [...exported.matchAll(/^\s*--([\w-]+):\s*([^;]+);/gm)].map((m) => [
    m[1],
    m[2].trim(),
  ])
);
const colorValue = (token) => resolved.get(`color-${token}`);

// ── 3. 라이트/다크 짝 맞추기 ───────────────────────────────────────────
const light = semantic.filter((k) => !k.endsWith(DARK));
const missing = light.filter((k) => !semantic.includes(k + DARK));
if (missing.length) {
  console.error(`다크 대응이 없는 semantic 토큰: ${missing.join(", ")}`);
  process.exit(1);
}

const rootVars = light.map((k) => `  --${k}: ${colorValue(k)};`);
const darkVars = light.map((k) => `  --${k}: ${colorValue(k + DARK)};`);
const themeColors = light.map((k) => `  --color-${k}: var(--${k});`);

// ── 4. 타이포 — export 가 버리는 lineHeight/letterSpacing 을 YAML 에서 채운다 ──
// Tailwind v4 는 `--text-<name>--line-height` 형태의 동반 변수를 쓴다.
// fontFamily / fontWeight 는 문서용이라 CSS 로 내보내지 않는다(--font-sans 로 충분).
const themeText = Object.entries(design.typography ?? {}).flatMap(
  ([name, t]) => {
    const lines = [`  --text-${name}: ${t.fontSize};`];
    if (t.lineHeight != null)
      lines.push(`  --text-${name}--line-height: ${t.lineHeight};`);
    if (t.letterSpacing != null)
      lines.push(`  --text-${name}--letter-spacing: ${t.letterSpacing};`);
    return lines;
  }
);

// ── 5. radius ─────────────────────────────────────────────────────────
const rounded = design.rounded ?? {};
const themeRadius = Object.entries(rounded).map(
  ([k, v]) => `  --radius-${k}: ${v};`
);
if (!rounded[BASE_RADIUS])
  throw new Error(
    `rounded.${BASE_RADIUS} 가 없다. bare --radius 를 만들 수 없다.`
  );

// ── 6. 조립 ───────────────────────────────────────────────────────────
const rawCss = `/*
 * 생성 파일 — 직접 수정하지 마세요.
 * 값은 frontend/DESIGN.md 에 있습니다. 고친 뒤 \`pnpm theme\` 으로 다시 생성하세요.
 * 여기서 직접 고친 내용은 다음 생성 때 사라집니다.
 *   배경: frontend/docs/adr/0002-design-md-token-pipeline.md
 */
@import "tailwindcss";
@import "tw-animate-css";
@import "shadcn/tailwind.css";

/* 기본값은 OS 설정이다. \`.light\` / \`.dark\` 를 얹으면 그쪽이 이긴다 — 스토리북이 툴바로
   테마를 강제할 때 쓴다 (그게 없으면 다크 OS 를 쓰는 사람에게 라이트 스토리가 다크로 뜬다). */
@custom-variant dark {
  @media (prefers-color-scheme: dark) {
    &:where(:not(.light *)) {
      @slot;
    }
  }
  &:is(.dark *) {
    @slot;
  }
}

:root {
${rootVars.join("\n")}
  --radius: ${rounded[BASE_RADIUS]};
  /* 스크롤바·리사이즈 손잡이 같은 브라우저 기본 UI 도 함께 따라오게 한다. */
  color-scheme: light;
}

@media (prefers-color-scheme: dark) {
  :root:not(.light) {
${darkVars.join("\n")}
    color-scheme: dark;
  }
}

.dark {
${darkVars.join("\n")}
  color-scheme: dark;
}

@theme inline {
  --font-sans: var(--font-sans);
  --font-heading: var(--font-sans);

${themeColors.join("\n")}

${themeText.join("\n")}

${themeRadius.join("\n")}
}

@layer base {
  * {
    @apply border-border outline-ring/50;
  }
  html {
    @apply font-sans;
    /* 루트 배율은 건드리지 않는다 (브라우저 기본 100%).
       한때 110% 로 키웠으나(S15P21A605-249), 디스플레이 배율이 높은 쪽에서는 화면을
       90% 로 줄여야 볼 수 있을 만큼 커졌다(S15P21A605-263). 한 값으로 두 요구를 다
       맞출 수 없어 기본으로 되돌린다 — 크기 선택은 사용자에게 맡길 일이다.
       타이포 토큰과 Tailwind 여백은 rem 그대로 둔다. 배율을 다시 조정할 일이 생기면
       여기 한 줄이면 되고, px 로 바꾸면 그 레버를 잃는다. */
  }
  body {
    @apply bg-background text-foreground;
    /* Pretendard 의 기본 숫자는 비례폭이다. 켜지 않으면 시세가 갱신될 때마다
       숫자가 좌우로 흔들린다 (일봉 하나가 29초인 화면에서는 읽기를 방해한다). */
    font-variant-numeric: tabular-nums;
  }
}
`;

// 생성물도 저장소의 prettier 설정을 따라야 한다. 손으로 맞추면 중첩 블록을 하나 추가할 때마다
// 들여쓰기가 어긋나고, 그때부터 lint-staged 가 형식을 고치면 --check 가 "DESIGN.md 와
// 어긋났다" 며 커밋을 막는다 (둘 다 제 할 일을 하는데 영영 풀리지 않는다).
const prettierConfig = await resolveConfig(OUT);
const css = await format(rawCss, { ...prettierConfig, filepath: OUT });

const summary = `semantic ${light.length} · typography ${Object.keys(design.typography ?? {}).length} · radius ${Object.keys(rounded).length}`;

if (checkOnly) {
  const current = readFileSync(OUT, "utf8");
  if (current !== css) {
    console.error(
      "src/app/globals.css 가 DESIGN.md 와 어긋나 있다.\n" +
        "  globals.css 는 생성물이라 직접 고치면 사라진다. `pnpm theme` 으로 다시 생성하고 함께 커밋할 것."
    );
    process.exit(1);
  }
  console.log(`globals.css 최신 (${summary})`);
} else {
  writeFileSync(OUT, css);
  console.log(`→ src/app/globals.css  (${summary})`);
}
