import { useSyncExternalStore } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";

/**
 * 디자인 토큰을 눈으로 확인하는 참조 화면. 컴포넌트가 아니라 시스템 자체의 문서다.
 *
 * 색 목록을 코드에 적지 않고 :root 규칙에서 읽어온다. DESIGN.md 에 토큰을 추가하면
 * 이 화면에 저절로 나타나므로, 목록이 문서와 어긋날 일이 없다.
 */
function rootCustomProps() {
  const names = new Set<string>();
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList;
    try {
      rules = sheet.cssRules;
    } catch {
      continue; // 교차 출처 스타일시트는 읽을 수 없다
    }
    for (const rule of Array.from(rules)) {
      if (!(rule instanceof CSSStyleRule) || rule.selectorText !== ":root")
        continue;
      for (const prop of Array.from(rule.style)) {
        if (prop.startsWith("--")) names.add(prop);
      }
    }
  }
  return [...names];
}

/** 테마 토글은 <html> 의 class 를 바꾼다. 그 변화를 외부 스토어로 구독한다. */
function subscribeToTheme(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["class"],
  });
  return () => observer.disconnect();
}

function useTokens() {
  // 반환값은 안 쓴다. 테마가 바뀔 때 다시 렌더되게 하는 것이 목적이다.
  useSyncExternalStore(
    subscribeToTheme,
    () => document.documentElement.className,
    () => ""
  );
  if (typeof document === "undefined") return [];
  const styles = getComputedStyle(document.documentElement);
  return rootCustomProps().map(
    (name) => [name, styles.getPropertyValue(name).trim()] as const
  );
}

function Swatch({ name, value }: { name: string; value: string }) {
  return (
    <div className="flex items-center gap-3">
      <div
        className="size-10 shrink-0 rounded-md border border-border"
        style={{ background: `var(${name})` }}
      />
      <div className="min-w-0">
        <div className="truncate text-sm text-foreground">
          {name.replace("--", "")}
        </div>
        <div className="text-2xs text-muted-foreground tabular-nums">
          {value}
        </div>
      </div>
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg bg-card p-6">
      <h2 className="mb-4 text-lg text-card-foreground">{title}</h2>
      {children}
    </section>
  );
}

const TYPE_SCALE = [
  ["2xs", "text-2xs"],
  ["xs", "text-xs"],
  ["sm", "text-sm"],
  ["base", "text-base"],
  ["lg", "text-lg"],
  ["xl", "text-xl"],
  ["2xl", "text-2xl"],
  ["3xl", "text-3xl"],
  ["4xl", "text-4xl"],
] as const;

const RADII = [
  ["none", "rounded-none"],
  ["sm", "rounded-sm"],
  ["md", "rounded-md"],
  ["lg", "rounded-lg"],
  ["xl", "rounded-xl"],
  ["full", "rounded-full"],
] as const;

function Tokens() {
  // --radius 는 색이 아니라 치수라 아래 라운드 절에서 따로 보여준다.
  const colors = useTokens().filter(([name]) => name !== "--radius");

  return (
    <div className="flex flex-col gap-6 bg-background p-6">
      <Section title="색 (semantic)">
        <p className="mb-4 text-sm text-foreground-subtle">
          코드가 쓸 수 있는 토큰 전부다. 1층 primitive 는 CSS 로 나가지 않아
          여기 없다. 툴바에서 테마를 바꾸면 값이 함께 바뀐다.
        </p>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4">
          {colors.map(([name, value]) => (
            <Swatch key={name} name={name} value={value} />
          ))}
        </div>
      </Section>

      <Section title="가격과 주문">
        <p className="mb-4 text-sm text-foreground-subtle">
          상승이 빨강, 하락이 파랑(한국 시장 관행). 매수·매도는 지금 같은
          값이지만 별개 토큰이다. 색 단독으로 방향을 전달하지 않으려면 부호와
          화살표를 함께 쓴다.
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-xl text-price-up tabular-nums">▲ +3.42%</span>
          <span className="text-xl text-price-down tabular-nums">▼ −1.08%</span>
          <span className="rounded-sm bg-price-up-surface px-2 py-1 text-xs text-price-up tabular-nums">
            ▲ +3.42%
          </span>
          <span className="rounded-sm bg-price-down-surface px-2 py-1 text-xs text-price-down tabular-nums">
            ▼ −1.08%
          </span>
          <button className="rounded-md bg-order-buy px-4 py-2 text-sm text-primary-foreground">
            매수
          </button>
          <button className="rounded-md bg-order-sell px-4 py-2 text-sm text-primary-foreground">
            매도
          </button>
          <button className="rounded-md bg-destructive-surface px-4 py-2 text-sm text-destructive">
            주문 취소
          </button>
        </div>
      </Section>

      <Section title="표면 3단">
        <p className="mb-4 text-sm text-foreground-subtle">
          다크에서는 명도 관계가 뒤집힌다 — 카드가 프레임보다 밝고, 눌린 면이
          카드보다 밝다.
        </p>
        <div className="rounded-lg bg-background p-4">
          <div className="text-2xs text-muted-foreground">background</div>
          <div className="mt-2 rounded-lg bg-card p-4">
            <div className="text-2xs text-muted-foreground">card</div>
            <div className="mt-2 rounded-md bg-muted p-4">
              <div className="text-2xs text-muted-foreground">
                muted (눌린 면)
              </div>
              <div className="mt-2 inline-block rounded-sm bg-surface-raised px-3 py-1 text-2xs text-foreground">
                surface-raised (뜬 알약)
              </div>
            </div>
          </div>
        </div>
      </Section>

      <Section title="타이포 스케일">
        <p className="mb-4 text-sm text-foreground-subtle">
          이름은 Tailwind 스케일명이라 <code>text-sm</code> 하나로 통일된다.
          base 는 <code>0.875rem</code> 이고, 시안이 가장 많이 쓴{" "}
          <code>0.625rem</code> 을 <code>2xs</code> 로 새로 만들었다. 값은 rem
          이라 루트 <code>110%</code> 를 곱한 크기로 그려진다 (base = 15.4px).
          숫자는 전역 <code>tabular-nums</code> 다.
        </p>
        <div className="flex flex-col gap-3">
          {TYPE_SCALE.map(([name, cls]) => (
            <div key={name} className="flex items-baseline gap-4">
              <span className="w-12 shrink-0 text-2xs text-muted-foreground">
                {name}
              </span>
              <span className={`${cls} text-card-foreground`}>
                다시 사는 2020년 3월 · 1,234,567원
              </span>
            </div>
          ))}
        </div>
      </Section>

      <Section title="라운드">
        <div className="flex flex-wrap gap-4">
          {RADII.map(([name, cls]) => (
            <div key={name} className="flex flex-col items-center gap-2">
              <div className={`${cls} size-16 border border-border bg-muted`} />
              <span className="text-2xs text-muted-foreground">{name}</span>
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}

const meta = {
  title: "Design/Tokens",
  component: Tokens,
  parameters: { layout: "fullscreen" },
} satisfies Meta<typeof Tokens>;

export default meta;

type Story = StoryObj<typeof meta>;

export const All: Story = {};
