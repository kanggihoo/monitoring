"use client";

import { useEffect, useRef, useState } from "react";

import { LoginInviteDialog } from "@/components/login-invite-dialog";
import { GuidedTour, type TourStep } from "@/components/tour/guided-tour";
import { hasSeenTour, markTourSeen, onTourRequest } from "@/lib/tour";

export const MARKET_TOUR = "market";

/**
 * 마켓 화면의 단계. **가리키는 자리는 `data-tour` 로 표시한다** — 클래스나 구조로 찾으면
 * 스타일을 손볼 때마다 조용히 빗나간다.
 */
export const MARKET_TOUR_STEPS: TourStep[] = [
  {
    target: "header",
    title: "지금은 과거의 어느 날",
    body: "날짜와 내 자산을 확인할 수 있습니다.",
    placement: "bottom",
  },
  {
    target: "instrument-list",
    title: "종목 확인",
    body: "미국·국내·코인이 한자리에 있습니다. 오른쪽에서 차트를 확인할 수 있습니다.",
    placement: "right",
  },
  {
    target: "instrument-detail",
    title: "차트와 종목 정보",
    body: "종목의 등락과 정보를 확인할 수 있습니다.",
    placement: "left",
  },
  {
    target: "order-ticket",
    title: "매수, 매도",
    body: "종목을 거래할 수 있습니다.",
    placement: "top",
  },
  {
    target: "leaderboard",
    title: "리더보드",
    body: "실시간으로 순위를 확인할 수 있습니다.",
    placement: "left",
  },
  {
    target: "news-ticker",
    title: "뉴스",
    body: "실시간 기사를 확인할 수 있습니다.",
    placement: "top",
  },
];

/**
 * 마켓 화면의 튜토리얼. **처음 온 사람에게만** 저절로 뜨고, 그 뒤로는 헤더의 다시 보기로만
 * 연다 (`hasSeenTour`).
 *
 * 닫힌 채로 시작하는 이유: 본 적 있는지는 `localStorage` 에 있어 서버 렌더가 알 수 없다.
 * 첫 렌더에 바로 열면 서버(닫힘)와 클라이언트(열림)가 어긋나 하이드레이션이 깨진다.
 */
export function MarketTour({ isLoggedIn }: { isLoggedIn: boolean }) {
  const [open, setOpen] = useState(false);
  /** 튜토리얼을 끝까지 본 사람에게 여는 로그인 권유. */
  const [invite, setInvite] = useState(false);
  /**
   * 저절로 뜬 첫 회차인가. 헤더의 다시 보기로 일부러 또 본 사람에게까지 권유를 띄우면
   * 그때부터는 제안이 아니라 잔소리가 된다.
   */
  const autoOpened = useRef(false);

  useEffect(() => {
    if (hasSeenTour(MARKET_TOUR)) return;

    // 개장 브리핑 같은 모달이 이미 떠 있으면 그 위에 겹치지 않는다 — 둘 다 화면을 덮으므로
    // 겹치는 순간 어느 쪽을 닫아야 하는지가 사라진다. 먼저 뜬 쪽이 닫히면 그때 연다.
    const blocked = () =>
      document.querySelector('[role="dialog"], [role="alertdialog"]') !== null;

    // 폴링하지 않는다. 배경 탭에서는 타이머가 분 단위로 늦춰져, 돌아왔을 때 한참 뒤에야
    // 뜬다 — 관찰자와 마이크로태스크는 그 제한을 받지 않는다.
    let live = true;
    const observer = new MutationObserver(() => reconsider());
    const reconsider = () => {
      if (!live || blocked()) return;
      observer.disconnect();
      live = false;
      autoOpened.current = true;
      setOpen(true);
    };

    observer.observe(document.body, { childList: true, subtree: true });
    // 떠 있는 모달이 없으면 첫 마디에 연다. 관찰자는 "바뀔 때" 만 부르므로 지금 상태는
    // 여기서 한 번 봐야 한다.
    queueMicrotask(reconsider);
    return () => {
      live = false;
      observer.disconnect();
    };
  }, []);

  // 헤더의 다시 보기.
  useEffect(() => onTourRequest(MARKET_TOUR, () => setOpen(true)), []);

  return (
    <>
      <GuidedTour
        steps={MARKET_TOUR_STEPS}
        open={open}
        label="마켓 화면 둘러보기"
        onClose={() => {
          // 끝까지 봤든 도중에 닫았든 "봤다" 로 친다 — 닫은 사람에게 새로고침마다
          // 다시 띄우지 않는다.
          markTourSeen(MARKET_TOUR);
          setOpen(false);
        }}
        // **끝까지 본 사람에게만, 로그인하지 않은 사람에게만, 저절로 뜬 첫 회차에만.**
        // 화면을 다 둘러본 직후가 권하기 가장 좋은 순간이지만, 셋 중 하나라도 어긋나면
        // 권유가 아니라 방해가 된다.
        onComplete={() => {
          if (isLoggedIn || !autoOpened.current) return;
          setInvite(true);
        }}
      />
      <LoginInviteDialog open={invite} onOpenChange={setInvite} />
    </>
  );
}
