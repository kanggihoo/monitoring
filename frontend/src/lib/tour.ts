/**
 * 튜토리얼을 본 적 있는지 기억한다.
 *
 * **서버가 아니라 `localStorage` 에 둔다.** 마켓은 공개 화면이라 (ADR-0005) 로그인하지
 * 않은 사람도 들어오는데, 튜토리얼이 가장 필요한 사람이 바로 그 사람이다. 서버에 두면
 * 그 사람에게는 저장할 자리가 없어 매번 다시 뜬다.
 *
 * 값으로 **버전**을 적는다. 단계를 크게 고쳤을 때 `TOUR_VERSION` 을 올리면 이미 본 사람도
 * 새 튜토리얼을 한 번 보게 된다 — `true` 하나만 적어 두면 그 길이 막힌다.
 */

/** 단계 구성이 크게 바뀌면 올린다. 올린 판은 모두에게 한 번 더 뜬다. */
export const TOUR_VERSION = 1;

const key = (tour: string) => `flashback.tour.${tour}`;

/**
 * @param tour 튜토리얼 이름 (`market` 등)
 * @returns 이 판(`TOUR_VERSION`)을 이미 본 적이 있으면 true
 */
export function hasSeenTour(tour: string): boolean {
  // 시크릿 창·저장소 차단에서는 읽기부터 던진다. 그때는 "본 적 없다" 로 둔다 —
  // 한 번 더 보는 쪽이, 필요한 사람이 못 보는 쪽보다 낫다.
  try {
    const stored = Number(window.localStorage.getItem(key(tour)));
    return Number.isFinite(stored) && stored >= TOUR_VERSION;
  } catch {
    return false;
  }
}

/** 끝까지 봤든 닫았든 "봤다" 로 친다 — 닫은 사람에게 새로고침마다 다시 띄우지 않는다. */
export function markTourSeen(tour: string): void {
  try {
    window.localStorage.setItem(key(tour), String(TOUR_VERSION));
  } catch {
    // 저장하지 못해도 이번 판은 이미 닫혔다. 다음에 한 번 더 뜰 뿐이다.
  }
}

/** 다시 보기. 기억을 지우고 처음부터 다시 띄울 수 있게 한다. */
export function forgetTour(tour: string): void {
  try {
    window.localStorage.removeItem(key(tour));
  } catch {
    // 위와 같다.
  }
}

/**
 * 다시 보기 신호. 헤더의 버튼과 튜토리얼이 서로 다른 트리에 있어 (`layout.tsx` 와 화면)
 * prop 으로 이을 수 없다. 스토어를 하나 더 두는 대신 이벤트로 부른다 — 오가는 것이
 * "이 튜토리얼을 다시 열어라" 하나뿐이라 상태라고 할 것이 없다.
 */
const TOUR_EVENT = "flashback:tour";

export function requestTour(tour: string): void {
  // 다시 보기는 "본 적 있다" 를 지우고 연다. 그래야 끝까지 안 보고 닫아도 다음 방문에
  // 한 번 더 뜬다 — 일부러 다시 부른 사람은 아직 못 익힌 사람이다.
  forgetTour(tour);
  window.dispatchEvent(new CustomEvent(TOUR_EVENT, { detail: tour }));
}

/** @returns 구독 해제 함수 */
export function onTourRequest(tour: string, open: () => void): () => void {
  const handle = (event: Event) => {
    if ((event as CustomEvent<string>).detail === tour) open();
  };
  window.addEventListener(TOUR_EVENT, handle);
  return () => window.removeEventListener(TOUR_EVENT, handle);
}
