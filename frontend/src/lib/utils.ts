import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * 키보드 포커스 표시. `DESIGN.md` 의 Accessibility Contract 가 **모든** 인터랙티브 요소에
 * 요구하며, 상태 규칙은 `ring-ring/50` 3px 이다.
 *
 * 버튼은 `buttonVariants` 가 같은 값을 이미 품고 있다. 이 상수는 링크·인풋처럼 그 밖의
 * 요소를 위한 것이다.
 */
export const FOCUS_RING =
  "rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50";

/**
 * 본문 축. 좌측 패널 옆의 본문 열을 **왼쪽에서부터 채운다** — 가운데 정렬(`mx-auto`)을 하면
 * 넓은 화면에서 패널과 본문 사이에 빈 여백이 생기고 화면마다 좌측 축이 튄다
 * (`DESIGN.md` 의 `variance 3`). 모든 화면이 이 컨테이너를 공유한다.
 *
 * 좁은 측정폭이 필요한 화면(폼)은 이 안에서 `max-w-*` 로 줄인다 — 오른쪽만 달라지고 왼쪽
 * 축은 고정된다. 안쪽 상자를 `mx-auto` 로 가운데 놓지 않는다.
 */
export const PAGE_CONTAINER = "w-full";

/**
 * 알약 탭 줄. 여덟 화면이 같은 모양을 쓰므로 클래스의 소유자를 여기 하나로 둔다 —
 * 예전에는 같은 문자열이 호출부 열 곳에 복사돼 있어서, 한 번 손보려면 같은 편집을
 * 열 번 해야 했고 한 곳을 빠뜨리면 그 화면만 다르게 보였다.
 *
 * **폭과 높이는 여기 없다.** 탭 줄마다 들어갈 자리가 달라(`w-full`·`w-[21.5rem]`)
 * 호출부가 정한다. 여기 있는 것은 "탭으로 보이게 하는 것" 뿐이다.
 *
 * **선택 항목을 잉크로 채운다.** 예전에는 흰 알약(`card`)을 옅은 트랙(`secondary`)
 * 위에 얹었는데, 이 앱의 바탕들은 명도가 서로 붙어 있어서(`background` `#f8f9fa` ·
 * `secondary` `#eef0f2` · `card` `#ffffff`) 그 방식으로는 선택이 드러나지 않았다.
 * 밑줄로도 바꿔 봤지만 2px 선 하나는 이 화면의 시각적 무게를 이기지 못했다.
 * 잉크(`primary`)로 채우면 대비가 17:1 이 되어 못 보고 지나갈 수가 없다.
 *
 * 잉크는 **무채색**이라 선택 표시에 쓸 수 있다 — 파랑은 하락과 혼동된다 (`DESIGN.md`).
 *
 * **`hover` 는 활성 탭을 건드리면 안 된다.** `hover:text-…` 와 `data-active:text-…` 는
 * 명시도가 같아서(둘 다 0,2,0) 스타일시트에서 뒤에 오는 `hover` 가 이긴다. 그래서 hover
 * 억제 규칙만 넣으면 **활성 탭에 마우스를 올렸을 때 흰 글자가 어두운 글자로 덮여 잉크
 * 알약 위에서 글자가 사라진다.** `data-active:hover:` 로 한 조건 더 걸어 되찾는다 —
 * 순서가 아니라 명시도로 이기므로 Tailwind 의 변형 출력 순서가 바뀌어도 안전하다.
 *
 * **글자색은 전환에서 뺀다.** 기본 컴포넌트가 `transition-all` 이라 배경과 글자색이 함께
 * 150ms 동안 변하는데, 그 중간 지점에서 반쯤 어두워진 알약 위에 반쯤 진해진 글자가 겹쳐
 * 검게 번지는 순간이 보인다. 배경만 애니메이션하고 글자는 즉시 뒤집는다.
 *
 * 트랙의 헤어라인은 남긴다. 선택되지 않은 탭들이 어디까지 한 묶음인지 알려주는 것은
 * 여전히 그 테두리뿐이다.
 */
export const TAB_LIST = "rounded-[12px] bg-card p-1 ring-1 ring-border";
export const TAB_TRIGGER =
  "rounded-[10px] text-[12.5px] text-foreground/70 transition-[background-color,box-shadow] hover:text-foreground/70 data-active:bg-primary data-active:font-semibold data-active:text-primary-foreground data-active:hover:text-primary-foreground dark:hover:text-muted-foreground dark:data-active:hover:text-primary-foreground dark:data-active:border-transparent dark:data-active:bg-primary dark:data-active:text-primary-foreground";

/** 탭 줄 안에 또 탭 줄이 들어가는 자리(거래 내역의 `체결`·`환전`). 한 단계 작다. */
export const TAB_LIST_SM = "rounded-[10px] bg-card p-[3px] ring-1 ring-border";
export const TAB_TRIGGER_SM =
  "rounded-[8px] text-[11.5px] text-foreground/70 transition-[background-color,box-shadow] hover:text-foreground/70 data-active:bg-primary data-active:font-semibold data-active:text-primary-foreground data-active:hover:text-primary-foreground dark:hover:text-muted-foreground dark:data-active:hover:text-primary-foreground dark:data-active:border-transparent dark:data-active:bg-primary dark:data-active:text-primary-foreground";
