---
# 마켓 리플레이 디자인 시스템 (단일 진실 원천)
# src/app/globals.css 생성물 (직접 수정 금지, pnpm theme 생성).
# 3층: 1층 primitive(raw hex, CSS 미추출) / 2층 semantic(shadcn 계약, CSS 추출) / 3층 components(대비 검증용).
# 다크 모드는 `-dark` 접미사로 정의.
version: alpha
name: Market Replay
description: 2014~2026 시장 멀티유저 모의투자 게임 디자인 토큰. 시안 v3 팔레트 기반, 2층 semantic이 shadcn 계약과 1:1 대응.

omitted:
  - section: spacing
    reason: "Tailwind v4 4px 배수 스케일(p-4 등) 기본 사용. 커스텀 spacing 토큰 불필요"

dials:
  density: 7
  variance: 3
  motion: 5

colors:
  # ── 1층 primitive (raw hex, CSS 미추출, 코드 직접 참조 불가) ────────
  # 무채색 (라이트)
  frame: "#F8F9FA" # 페이지 프레임 (앱 배경). 카드는 흰색, 프레임은 한 단계 회색 (Flashback 시안)
  shell: "#EEF0F2" # 셸 (보조 표면·버튼)
  sunken: "#F2F4F6" # 인풋/탭 트랙 (눌린 면)
  selected: "#E5E8ED" # 선택 상태 (무채색)
  line: "#E2E5E9" # 1px 테두리
  hairline: "#F2F4F6" # 카드 안 행 구분선
  line-strong: "#C7CCD3" # 프레임 위 선/포커스 링
  muted-ink: "#6B727B" # 라벨
  ink-2: "#464C54" # 본문 보조
  ink: "#14171A" # 숫자/제목
  paper: "#FFFFFF" # 카드/밝은 배경

  # 의미색 (라이트)
  up: "#D0333F" # 상승
  up-soft: "#FEF7F8" # 상승 뱃지 배경 (WCAG AA 통과값)
  down: "#2464CC" # 하락
  down-soft: "#F4F8FE" # 하락 뱃지 배경
  danger: "#C2410C" # 파괴적 액션 (up과 색상각 분리)
  danger-soft: "#FDF2EC"
  good: "#15803D" # 종목 카드 신호등 '좋음' (초록은 여기서만)
  # 시상 표식(왕관)의 채움. 흰 면 대비 1.28 이라 이 색만으로는 형태가 안 남으므로
  # 윤곽을 함께 두르거나 아바타처럼 짙은 면 위에 얹어 쓴다.
  gold-bright: "#FFE100"

  # 무채색 (다크)
  frame-dark: "#0C0E11"
  shell-dark: "#15181C"
  surface-dark: "#1D2126" # 카드 (프레임보다 밝음)
  sunken-dark: "#262B31" # 눌린 면
  raised-dark: "#333A42"
  selected-dark: "#2F353D"
  line-dark: "#2E343B"
  hairline-dark: "#262B31"
  line-strong-dark: "#454C55"
  muted-ink-dark: "#939BA5"
  ink-2-dark: "#BAC1C9"
  ink-dark: "#F2F4F6"
  paper-dark: "#14171A" # 어두운 텍스트

  # 의미색 (다크)
  up-dark: "#FF5C68"
  up-soft-dark: "#3A1F23"
  down-dark: "#5B9BFF"
  down-soft-dark: "#1B2740"
  danger-dark: "#F0913C"
  danger-soft-dark: "#3A2617"
  good-dark: "#4ADE80"
  # 다크에는 노랑을 넣지 않는다 — 어두운 면에서 노랑은 휘도가 다른 유채색의 두 배라
  # 오래 보면 눈이 피로하다. 표식은 잉크로 돌아간다 (아래 `award-mark-dark`).

  # ── 2층 semantic (shadcn 계약 1:1, CSS 커스텀 프로퍼티 추출) ────────
  background: "{colors.frame}"
  foreground: "{colors.ink}"
  card: "{colors.paper}"
  card-foreground: "{colors.ink}"
  popover: "{colors.paper}"
  popover-foreground: "{colors.ink}"
  primary: "{colors.ink}"
  primary-foreground: "{colors.paper}"
  secondary: "{colors.shell}"
  secondary-foreground: "{colors.ink}"
  muted: "{colors.sunken}"
  muted-foreground: "{colors.muted-ink}"
  accent: "{colors.selected}"
  accent-foreground: "{colors.ink}"
  destructive: "{colors.danger}"
  destructive-foreground: "{colors.paper}"
  # border 는 카드(흰 면) 위에서만 읽힌다. 프레임(회색) 위에 같은 선을 그으면 대비 1.02:1 로
  # 사라지므로, 프레임 위에 긋는 선은 border-strong 을 쓴다.
  # 면도 같다 — outline 버튼은 card, 인풋은 muted(눌린 면). 프레임을 컨트롤의 면으로 쓰지 않는다.
  border: "{colors.line}"
  border-strong: "{colors.line-strong}"
  input: "{colors.line}"
  ring: "{colors.line-strong}"

  # 프로젝트 고유 역할 (라이트)
  destructive-surface: "{colors.danger-soft}"
  foreground-subtle: "{colors.ink-2}"
  surface-raised: "{colors.paper}"
  surface-feature: "{colors.ink}"
  surface-feature-foreground: "{colors.paper}"
  price-up: "{colors.up}"
  price-up-surface: "{colors.up-soft}"
  price-down: "{colors.down}"
  price-down-surface: "{colors.down-soft}"
  order-buy: "{colors.up}"
  order-sell: "{colors.down}"
  award-mark: "{colors.gold-bright}"
  signal-good: "{colors.good}"
  signal-fair: "{colors.muted-ink}"
  signal-caution: "{colors.danger}"
  # 좌측 내비 패널. 셸의 일부다 — 레일과 본문이 한 덩어리 회색 셸 안에 들어가고 그 셸이
  # 프레임 위에 떠 있다 (Flashback 시안). 그래서 레일에는 제 배경도 테두리도 없다.
  # 선택 항목만 흰 타일로 떠오른다 — 카드와 같은 면이라 "지금 여기" 가 프레임 대비로 읽힌다.
  sidebar: "{colors.shell}"
  sidebar-foreground: "{colors.ink-2}"
  sidebar-accent: "{colors.paper}"
  sidebar-accent-foreground: "{colors.ink}"
  sidebar-border: "{colors.line}"
  # 종가 선 그래프. 선은 직전 틱 대비 등락 방향을 나르고(`price-up`/`price-down`), 격자는
  # 헤어라인이다. `chart-line` 은 보합·휴장일 때의 선 색이다.
  chart-line: "{colors.ink}"
  chart-grid: "{colors.hairline}"

  # 2층 semantic (다크 대응)
  background-dark: "{colors.frame-dark}"
  foreground-dark: "{colors.ink-dark}"
  card-dark: "{colors.surface-dark}"
  card-foreground-dark: "{colors.ink-dark}"
  popover-dark: "{colors.surface-dark}"
  popover-foreground-dark: "{colors.ink-dark}"
  primary-dark: "{colors.ink-dark}"
  primary-foreground-dark: "{colors.paper-dark}"
  secondary-dark: "{colors.shell-dark}"
  secondary-foreground-dark: "{colors.ink-dark}"
  muted-dark: "{colors.sunken-dark}"
  muted-foreground-dark: "{colors.muted-ink-dark}"
  accent-dark: "{colors.selected-dark}"
  accent-foreground-dark: "{colors.ink-dark}"
  destructive-dark: "{colors.danger-dark}"
  destructive-foreground-dark: "{colors.paper-dark}"
  border-dark: "{colors.line-dark}"
  border-strong-dark: "{colors.line-strong-dark}"
  input-dark: "{colors.line-dark}"
  ring-dark: "{colors.line-strong-dark}"
  destructive-surface-dark: "{colors.danger-soft-dark}"
  foreground-subtle-dark: "{colors.ink-2-dark}"
  surface-raised-dark: "{colors.raised-dark}"
  surface-feature-dark: "{colors.surface-dark}"
  surface-feature-foreground-dark: "{colors.ink-dark}"
  price-up-dark: "{colors.up-dark}"
  price-up-surface-dark: "{colors.up-soft-dark}"
  price-down-dark: "{colors.down-dark}"
  price-down-surface-dark: "{colors.down-soft-dark}"
  order-buy-dark: "{colors.up-dark}"
  order-sell-dark: "{colors.down-dark}"
  award-mark-dark: "{colors.ink-dark}"
  signal-good-dark: "{colors.good-dark}"
  signal-fair-dark: "{colors.muted-ink-dark}"
  signal-caution-dark: "{colors.danger-dark}"
  sidebar-dark: "{colors.shell-dark}"
  sidebar-foreground-dark: "{colors.ink-2-dark}"
  sidebar-accent-dark: "{colors.surface-dark}"
  sidebar-accent-foreground-dark: "{colors.ink-dark}"
  sidebar-border-dark: "{colors.line-dark}"
  chart-line-dark: "{colors.ink-dark}"
  chart-grid-dark: "{colors.hairline-dark}"

typography:
  2xs:
    fontFamily: Pretendard Variable
    fontSize: 0.625rem
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: 0.01em
  xs:
    fontFamily: Pretendard Variable
    fontSize: 0.6875rem
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: 0.01em
  sm:
    fontFamily: Pretendard Variable
    fontSize: 0.75rem
    fontWeight: 400
    lineHeight: 1.45
  base:
    fontFamily: Pretendard Variable
    fontSize: 0.875rem
    fontWeight: 400
    lineHeight: 1.5
  lg:
    fontFamily: Pretendard Variable
    fontSize: 1rem
    fontWeight: 500
    lineHeight: 1.5
  xl:
    fontFamily: Pretendard Variable
    fontSize: 1.125rem
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: -0.01em
  2xl:
    fontFamily: Pretendard Variable
    fontSize: 1.5rem
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: -0.01em
  3xl:
    fontFamily: Pretendard Variable
    fontSize: 1.875rem
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: -0.02em
  4xl:
    fontFamily: Pretendard Variable
    fontSize: 2.125rem
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: -0.02em

rounded:
  none: 0px
  sm: 6px
  md: 8px
  lg: 12px
  xl: 16px
  full: 9999px

components:
  page:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
  page-dark:
    backgroundColor: "{colors.background-dark}"
    textColor: "{colors.foreground-dark}"

  card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.card-foreground}"
    rounded: "{rounded.lg}"
  card-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.card-foreground-dark}"

  popover:
    backgroundColor: "{colors.popover}"
    textColor: "{colors.popover-foreground}"
    rounded: "{rounded.md}"
  popover-dark:
    backgroundColor: "{colors.popover-dark}"
    textColor: "{colors.popover-foreground-dark}"

  text-body:
    backgroundColor: "{colors.card}"
    textColor: "{colors.foreground-subtle}"
    typography: "{typography.base}"
  text-body-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.foreground-subtle-dark}"

  text-label:
    backgroundColor: "{colors.card}"
    textColor: "{colors.muted-foreground}"
    typography: "{typography.xs}"
  text-label-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.muted-foreground-dark}"

  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.md}"
    height: 32px
  button-primary-dark:
    backgroundColor: "{colors.primary-dark}"
    textColor: "{colors.primary-foreground-dark}"

  button-secondary:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    rounded: "{rounded.md}"
  button-secondary-dark:
    backgroundColor: "{colors.secondary-dark}"
    textColor: "{colors.secondary-foreground-dark}"

  button-destructive:
    backgroundColor: "{colors.destructive-surface}"
    textColor: "{colors.destructive}"
    rounded: "{rounded.md}"
  button-destructive-dark:
    backgroundColor: "{colors.destructive-surface-dark}"
    textColor: "{colors.destructive-dark}"

  button-destructive-solid:
    backgroundColor: "{colors.destructive}"
    textColor: "{colors.destructive-foreground}"
  button-destructive-solid-dark:
    backgroundColor: "{colors.destructive-dark}"
    textColor: "{colors.destructive-foreground-dark}"

  button-buy:
    backgroundColor: "{colors.order-buy}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.md}"
  button-buy-dark:
    backgroundColor: "{colors.order-buy-dark}"
    textColor: "{colors.primary-foreground-dark}"

  button-sell:
    backgroundColor: "{colors.order-sell}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.md}"
  button-sell-dark:
    backgroundColor: "{colors.order-sell-dark}"
    textColor: "{colors.primary-foreground-dark}"

  input:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.md}"
  input-dark:
    backgroundColor: "{colors.muted-dark}"
    textColor: "{colors.foreground-dark}"

  input-border:
    backgroundColor: "{colors.input}"
  input-border-dark:
    backgroundColor: "{colors.input-dark}"

  menu-item-selected:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.accent-foreground}"
    rounded: "{rounded.sm}"
  menu-item-selected-dark:
    backgroundColor: "{colors.accent-dark}"
    textColor: "{colors.accent-foreground-dark}"

  tab-raised:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.sm}"
  tab-raised-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.foreground-dark}"

  feature-panel:
    backgroundColor: "{colors.surface-feature}"
    textColor: "{colors.surface-feature-foreground}"
    rounded: "{rounded.lg}"
  feature-panel-dark:
    backgroundColor: "{colors.surface-feature-dark}"
    textColor: "{colors.surface-feature-foreground-dark}"

  divider:
    backgroundColor: "{colors.border}"
  divider-dark:
    backgroundColor: "{colors.border-dark}"

  # 프레임 위에 긋는 선. divider(카드 위) 와 짝이다.
  divider-strong:
    backgroundColor: "{colors.border-strong}"
  divider-strong-dark:
    backgroundColor: "{colors.border-strong-dark}"

  focus-ring:
    backgroundColor: "{colors.ring}"
  focus-ring-dark:
    backgroundColor: "{colors.ring-dark}"

  price-up:
    backgroundColor: "{colors.card}"
    textColor: "{colors.price-up}"
  price-up-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.price-up-dark}"

  badge-up:
    backgroundColor: "{colors.price-up-surface}"
    textColor: "{colors.price-up}"
    rounded: "{rounded.sm}"
  badge-up-dark:
    backgroundColor: "{colors.price-up-surface-dark}"
    textColor: "{colors.price-up-dark}"

  price-down:
    backgroundColor: "{colors.card}"
    textColor: "{colors.price-down}"
  price-down-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.price-down-dark}"

  award-mark:
    backgroundColor: "{colors.award-mark}"
  award-mark-dark:
    backgroundColor: "{colors.award-mark-dark}"

  signal-good:
    backgroundColor: "{colors.card}"
    textColor: "{colors.signal-good}"
  signal-good-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.signal-good-dark}"

  signal-fair:
    backgroundColor: "{colors.card}"
    textColor: "{colors.signal-fair}"
  signal-fair-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.signal-fair-dark}"

  signal-caution:
    backgroundColor: "{colors.card}"
    textColor: "{colors.signal-caution}"
  signal-caution-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.signal-caution-dark}"

  sidebar:
    backgroundColor: "{colors.sidebar}"
    textColor: "{colors.sidebar-foreground}"
  sidebar-dark:
    backgroundColor: "{colors.sidebar-dark}"
    textColor: "{colors.sidebar-foreground-dark}"

  sidebar-item-selected:
    backgroundColor: "{colors.sidebar-accent}"
    textColor: "{colors.sidebar-accent-foreground}"
    rounded: "{rounded.md}"
  sidebar-item-selected-dark:
    backgroundColor: "{colors.sidebar-accent-dark}"
    textColor: "{colors.sidebar-accent-foreground-dark}"

  sidebar-border:
    backgroundColor: "{colors.sidebar-border}"
  sidebar-border-dark:
    backgroundColor: "{colors.sidebar-border-dark}"

  chart-line:
    backgroundColor: "{colors.card}"
    textColor: "{colors.chart-line}"
  chart-line-dark:
    backgroundColor: "{colors.card-dark}"
    textColor: "{colors.chart-line-dark}"

  chart-grid:
    backgroundColor: "{colors.chart-grid}"
  chart-grid-dark:
    backgroundColor: "{colors.chart-grid-dark}"

  badge-down:
    backgroundColor: "{colors.price-down-surface}"
    textColor: "{colors.price-down}"
    rounded: "{rounded.sm}"
  badge-down-dark:
    backgroundColor: "{colors.price-down-surface-dark}"
    textColor: "{colors.price-down-dark}"
---

# 마켓 리플레이 디자인 시스템

## Overview

2014~~2026 시장 기반 멀티유저 모의투자 게임 (타깃: 2030 입문자).
게임 1개월 = 실제 720초 고정, 종가 Tick 간격은 31~~36초 유동 (거래일 수에 따라). 핵심: 숫자 시인성 유지(`tabular-nums`) 및 시세 변동 명확 전달.
화면 대다수 무채색. 유채색은 가격 방향, 주문, 파괴적 액션 3곳에만 제한 사용 (선택 상태도 무채색).

### Dials

| 다이얼     | 값    | 규칙                                                                                     |
| ---------- | ----- | ---------------------------------------------------------------------------------------- |
| `density`  | **7** | 정보 밀집. 1px 구분선 우선, 숫자 `tabular-nums` 강제 (가독성 최우선).                    |
| `variance` | **3** | 구조 일관성. 중앙정렬 히어로/그리드 이탈 금지, 화면 간 일관 배치.                        |
| `motion`   | **5** | 표준 전환. 시세 갱신/랭킹 변동 허용, 숫자 장식 모션 금지, `prefers-reduced-motion` 대응. |

## Colors

값은 front matter가 진실 원천. 아래는 역할/근거 명세.

| 토큰                                                    | 역할                              | 근거                                                                                                               |
| ------------------------------------------------------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `background` / `card` / `muted`                         | 페이지 / 카드 / 눌린 면           | 표면 3단. 라이트: 프레임 < 카드(백색). 다크: 관계 반전(카드 > 프레임).                                             |
| `foreground` / `foreground-subtle` / `muted-foreground` | 텍스트 3단                        | 숫자·제목 / 본문 보조 / 라벨. 회색 단일 계보 유지.                                                                 |
| `primary`                                               | 주 액션                           | 잉크 색상. 가격 강조 위해 주 액션은 무채색 처리.                                                                   |
| `secondary`                                             | 보조 표면·버튼                    | 셸/사이드바 동일 값 (카드 대비 한 단계 어두움).                                                                    |
| `accent`                                                | 선택·호버                         | 무채색. 파랑 사용 시 하락(`price-down`)과 혼동 발생.                                                               |
| `border` / `input` / `ring`                             | 구분선 / 입력 테두리 / 포커스 링  | 계층 표현은 그림자 대신 1px 선 사용. **카드(흰 면) 위 기준.**                                                      |
| `border-strong`                                         | 프레임 위에 긋는 선               | `border` 는 프레임 위에서 대비 1.02:1 로 사라진다. 프레임 위 선은 이것.                                            |
| `price-up` / `price-down`                               | 가격 방향 (상승 빨강 / 하락 파랑) | 국내 시장 관행. 라이트 흰 배경 대비 4.97:1 / 4.92:1 균형 유지.                                                     |
| `price-up-surface` / `price-down-surface`               | 등락 뱃지 배경                    | WCAG AA 충족용 고명도 배경.                                                                                        |
| `order-buy` / `order-sell`                              | 주문 의도 (매수 / 매도)           | 가격 방향과 별개 토큰 (하락장 매수 시 `order-buy` 적용).                                                           |
| `destructive` / `destructive-surface`                   | 파괴적 액션 / 입력 오류           | 탈퇴, 취소, `aria-invalid`. `price-up`과 색상각 분리(주황 계열).                                                   |
| `surface-raised`                                        | 탭 알약 (돌출 표면)               | 라이트: 카드 동일 백색, 다크: 별도 명도.                                                                           |
| `surface-feature`                                       | 광고 면 (로그인 좌측 패널)        | 라이트는 잉크, 다크는 카드 명도. 반전이 아니다 — 다크에서 `primary` 는 거의 흰색이라 그대로 쓰면 눈부신 판이 된다. |
| `signal-good` / `signal-fair` / `signal-caution`        | 종목 카드 질문형 요약 신호등      | 좋음/보통/주의 3단. 보통은 무채색, 주의는 `danger` 재사용(색상 수 억제).                                           |
| `sidebar` / `sidebar-accent` / `sidebar-border`         | 좌측 내비 패널 / 선택 항목 / 경계 | 레일은 셸 명도(본문과 한 덩어리), 선택 항목만 카드 면으로 떠오른다. 파랑은 하락과 혼동되므로 선택은 무채색이다.    |
| `chart-line` / `chart-grid`                             | 종가 선 그래프의 선 / 격자        | 선은 직전 틱 대비 등락에 따라 `price-up`/`price-down`, 보합·휴장이면 잉크. 격자는 헤어라인이고 방향색을 안 쓴다.   |

### Banned

- **`chart-1~5`**: 미정의. 선 그래프는 방향색(`price-up`/`price-down`)·`chart-line`(보합)·`chart-grid`(헤어라인)으로 그린다 — 차트에 색을 더 쓰면 방향색과 경쟁한다.
- **범용 성공/경고 색**: 미정의. 초록은 신호등 `signal-good` 한 곳에서만 쓰고, 그 외 성공 표현은 무채색으로 둔다. 노랑은 여전히 미정의.
- **1층 primitive 코드 직접 사용 금지**: `bg-ink` 등 CSS 미추출 (.dark 미지원으로 다크 모드 깨짐 방지).

## Typography

**Pretendard Variable** 단일 폰트 사용 (`wght 45~930`). 별도 숫자 폰트 불필요.
전역 `tabular-nums` 적용 (비례폭에 의한 시세 갱신 흔들림 차단).
Tailwind 기본 스케일명 준수 (shadcn 호환).

- **`base`**: `0.875rem` (데이터 밀도 위해 Tailwind `1rem` 대비 하향).
- **`2xs`**: `0.625rem` 신규 추가.
- 대형 텍스트: `letterSpacing` 축소 적용.
- **단위는 rem**: 루트 폰트는 브라우저 기본(100%)이라 위 값이 곧 실제 px 다
  (`base` = 14px). 한때 루트를 `110%` 로 키웠으나 디스플레이 배율이 높은 쪽에서
  너무 커져 되돌렸다 (S15P21A605-249 → 263). rem 을 유지하는 이유는 배율을 다시
  손볼 때 루트 한 줄로 끝나기 때문이다 — px 로 쓰면 그 레버에서 빠진다.

### Banned

- **px 단위 금지**: 타이포는 rem 으로만 쓴다 (루트 배율에서 빠지지 않게).
- **0.5px 단위 금지** (`11.5px` 등): 지정 스케일 준수.
- 단일 화면 내 **폰트 굵기 3종 초과 금지**.

## Layout

반응형 웹. Tailwind 기본 브레이크포인트 (`sm 640`, `md 768`, `lg 1024`, `xl 1280`, `2xl 1536`) 및 4px 간격 스케일 (`p-4` 등) 준수.

- **밀도 (`density 7`)**: 카드 여백보다 1px 구분선 우선.
- **구조 (`variance 3`)**: 화면 간 요소 위치 고정.
- **본문 축**: 좌측 패널 옆의 본문 열을 모든 화면이 `PAGE_CONTAINER` 하나로 **왼쪽부터
  채운다** (가운데 정렬 없음). 좁은 폭이 필요한 폼은 그 **안에서** `max-w-*` 로 줄인다 —
  오른쪽만 달라지고 왼쪽 축은 고정된다. `mx-auto` 로 가운데 놓으면 넓은 화면에서 패널과
  본문 사이에 빈 여백이 생기고 화면을 옮길 때 본문 좌측이 튄다.
- **격자**: 시안(Flashback)의 12px 격자 — 본문 여백·카드 사이 간격은 `p-3`/`gap-3`.
  마켓처럼 오래 들여다보는 화면은 뷰포트를 채우고 패널 안에서 스크롤한다 (페이지가 스크롤되지
  않는다).
- **테스트 뷰포트**: 360px(모바일 최소), 768px, 1280px.

## Elevation & Depth

표면 명도 및 1px 선으로 계층 표현 (과도한 그림자 지양).

- 라이트: 프레임(어두움) < 셸 < 카드(백색).
- 다크: 프레임(어두움) < 카드 < 눌린 면.
- 그림자 (카드 한정 미세 적용):
  - 라이트: `0 1px 2px rgba(20,23,26,.05)` + `0 5px 14px -8px rgba(20,23,26,.16)`.
  - 다크: `0 1px 2px rgba(0,0,0,.5)` + `0 6px 18px -10px rgba(0,0,0,.75)`.
- 팝오버/모달만 강한 그림자 허용.

## Shapes

5단계 고정:

- `sm`(6px): 뱃지, 탭 알약, 소형 태그.
- `md`(8px): 버튼, 인풋, 팝오버 (기본값 `--radius`).
- `lg`(12px): 카드.
- `xl`(16px): 셸, 모달.
- `full`(9999px): 아바타, 원형 버튼.

스케일 밖 단계 사용 금지 (`rounded-2xl`, `rounded-4xl` …). 정의되지 않은 이름은 Tailwind 기본값
(16px, 32px …)으로 조용히 새어 들어와 위 5단계를 무너뜨린다.

## Components

상태 스타일은 Tailwind variant 사용 (`hover:bg-primary/80`, `focus-visible:ring-ring/50`).

| 상태          | 규칙                                                 |
| ------------- | ---------------------------------------------------- |
| hover         | 배경 1단계 강조 (솔리드 `/80`, 소프트 `/20`).        |
| active        | 배경 유지 + `translate-y-px`.                        |
| focus-visible | `ring-ring/50` 1px + `border-ring` (포커스 링 필수). |
| disabled      | `opacity-50` + 포인터 이벤트 차단.                   |
| aria-invalid  | `border-destructive` + `ring-destructive/20`.        |

비상태 UI:

- **loading**: 스켈레톤 기본 (레이아웃 흔들림 차단).
- **empty**: 중립 안내 (현금 100% 정상 전략 간주, 매매 유도 금지).
- **error**: 해결 행동 중심 안내 (원인 코드 미노출).

## Do's and Don'ts

### 토큰 규율

| 금지                          | 권장                                  |
| ----------------------------- | ------------------------------------- |
| `globals.css` 수동 수정       | `DESIGN.md` 수정 후 `pnpm theme` 실행 |
| hex / `rgb()` 인라인 하드코딩 | semantic 토큰 유틸리티 (`bg-card` 등) |
| 1층 토큰 직접 사용 (`bg-ink`) | 2층 semantic 토큰 사용                |
| 임의 수치 (`text-[13px]`)     | 표준 스케일 (`text-sm` 등)            |
| 즉석 색상 생성                | `DESIGN.md` 등록 및 lint 검증 후 사용 |

### 타이포그래피

| 금지                  | 권장                                  |
| --------------------- | ------------------------------------- |
| 비례폭 숫자 사용      | 전역 `tabular-nums` 강제              |
| 0.5px 반칸 사용       | 9단계 표준 스케일                     |
| 한 화면 굵기 4종 이상 | 400, 500, 600 중 최대 2종             |
| 본문 자간 확대        | 기본 자간 (18px 이상에서만 축소 조정) |

### 컬러·표면

| 금지                          | 권장                                  |
| ----------------------------- | ------------------------------------- |
| 등락 외 목적의 빨강/파랑 사용 | 무채색 사용                           |
| 파란색 선택 상태              | 무채색 `accent`                       |
| 매수/취소 동일 색상           | `order-buy`와 `destructive` 색상 분리 |
| 다크 모드 단순 반전           | 다크 전용 표면 토큰 정의              |
| 그라디언트 배경               | 단색 표면 + 1px 선                    |
| 프레임을 컨트롤의 면으로 사용 | 뜬 컨트롤 `bg-card`, 입력 `bg-muted`  |
| 프레임 위 `border`            | 프레임 위 선은 `border-strong`        |

### 레이아웃 & 상태

| 금지                        | 권장                                      |
| --------------------------- | ----------------------------------------- |
| 화면별 구조 변형            | 위치 일관성 유지 (`variance 3`)           |
| 화면마다 다른 본문 컨테이너 | 공유 `PAGE_CONTAINER`, 안쪽에서 `max-w-*` |
| 제목 위 장식용 영문 라벨    | 제목 한 줄로 끝낸다                       |
| 임의 브레이크포인트         | Tailwind 5단계 표준                       |
| 카드 3단 이상 중첩          | 1px 구분선 분리                           |
| 로딩 UI 누락                | 스켈레톤 적용                             |
| 매매 강요 빈 화면 카피      | 중립 안내 문구                            |
| 포커스 링 임의 제거         | `focus-visible` 유지                      |
| 에러 코드 노출              | 행동 안내 문구                            |

### 모션 & 카피

| 금지                            | 권장                                    |
| ------------------------------- | --------------------------------------- |
| 숫자 값 장식 모션               | 배경 flash 수준 제한                    |
| `prefers-reduced-motion` 미대응 | 모션 축소 대응 (`motion 5`)             |
| 페이지 진입 지연 애니메이션     | 즉시 렌더링                             |
| 투자 자문형 문구                | 게임/사실 서술 카피                     |
| 색상 단독 등락 표시             | 색 + 부호(`+`/`−`) + 기호(`▲`/`▼`) 병기 |

## Accessibility Contract

| 항목                          | 기준                                                  |
| ----------------------------- | ----------------------------------------------------- |
| 본문 텍스트 대비              | **4.5:1 이상** (`designmd lint` 검증).                |
| 대형 텍스트(18px+) / 비텍스트 | **3:1 이상**.                                         |
| 색상 의존성                   | 색상 단독 사용 금지 (부호/화살표 병기).               |
| 다크 모드                     | 라이트 모드와 동일 대비 기준 적용.                    |
| 키보드 포커스                 | 모든 인터랙티브 요소에 `focus-visible` 표시.          |
| 모션 감소                     | `prefers-reduced-motion` 감지 시 flash/트랜지션 차단. |
| 대체 텍스트                   | 차트/히트맵에 수치 라벨 또는 데이터 테이블 병기.      |

새 토큰 추가 시 `components` 항목 동시 등록 필수 (`contrast-ratio` 검증 및 `orphaned-tokens` 방지).
