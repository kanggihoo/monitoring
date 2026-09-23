"use client";

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { BriefingDialog } from "@/components/market/briefing-dialog";
import { useGameClock } from "@/hooks/use-game-clock";
import { trackEvent } from "@/lib/analytics";
import { fetchBriefing } from "@/lib/market-api";
import type { Briefing } from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";

/**
 * 개장 브리핑을 여닫는다 (기획 3.5).
 *
 * **`game.phase` 가 `BRIEFING` 이 되면 연다.** 브리핑 시작을 알리는 WS 메시지는 계약에 없다 —
 * 매일 11:00 고정이라 phase 전환만으로 충분하고, 메시지를 하나 더 두면 같은 사실을 두 경로가
 * 말하게 된다 (`sea.md`).
 *
 * 여는 조건을 상태로 들지 않고 **파생**한다. 시계는 매초 다시 그려지므로, phase 를 보고
 * `setOpen` 하는 효과를 두면 그 효과가 매초 후보가 되고 닫아 둔 모달이 다시 열릴 길이 생긴다.
 *
 * **개장 뒤에도 손으로 열 수 있다** (기획 3.5.3, 조회의 허용 phase 가 `ALWAYS` 인 이유).
 * 진입점은 홈의 날짜 카드 옆 "오늘 브리핑" 이고, 그 버튼은 세션 스토어에 요청만 올린다.
 * **자동 표시(`ackedDay`)와 섞지 않는다** — 하나로 두면 손으로 연 것이 "오늘 안 봤음" 으로
 * 되돌아가 브리핑 구간 내내 모달이 다시 열린다.
 */
export function BriefingGate({ children }: { children: React.ReactNode }) {
  const clock = useGameClock();
  const requests = useStore(sessionStore, (state) => state.briefingRequests);
  /**
   * 받아 둔 브리핑과 **그것이 어느 일차의 것인지**. 일차를 함께 들고 있어야 날이 바뀐 순간
   * 어제 일정이 화면에서 곧바로 사라진다 — 효과 안에서 비우면 렌더가 한 번 더 도는 데다,
   * 새 조회가 끝날 때까지 어제 청약 시각이 그대로 보인다.
   */
  const [loaded, setLoaded] = useState<{
    day: number;
    briefing: Briefing;
  } | null>(null);
  /** 확인하고 들어간 날. 하루에 한 번 보는 화면이라 그날은 다시 열지 않는다. */
  const [ackedDay, setAckedDay] = useState<number | null>(null);
  /** 손으로 연 요청 번호. 자동 표시와 달리 일차와 무관하게 그때 한 번 연다. */
  const [openedRequest, setOpenedRequest] = useState(0);

  const open =
    (clock.phase === "BRIEFING" && ackedDay !== clock.seasonDay) ||
    openedRequest !== requests;
  /** 분석에 싣는 시즌 일차. GA4 매개변수는 null 을 받지 않아 시계가 붙기 전은 0 이다. */
  const seasonDay = clock.seasonDay ?? 0;
  /** 손으로 연 것인가. 실패를 알릴지와 분석 구분에 쓴다. */
  const manual = openedRequest !== requests;

  // 열림 자체는 한 번만 센다. **조회와 갈라 둔다** — 조회는 다시 눌렀을 때와 일차가 바뀔 때
  // 다시 나가야 하는데, 한 효과에 묶으면 그때마다 열림이 한 번씩 더 세어져 완주율이 망가진다.
  useEffect(() => {
    if (!open) return;
    // 이 값과 `briefing_complete` 의 차이가 브리핑 완주율이다 — 열리기만 하고 닫히지 않은
    // 날이 곧 중도 이탈이다. `manual` 로 가른다: 손으로 다시 연 것을 섞으면 그 차이가 더는
    // 완주율이 아니다.
    trackEvent("briefing_open", { season_day: seasonDay, manual });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 여는 순간의 값이면 된다
  }, [open]);

  // 열려 있는 동안의 조회. **다시 누를 때(`requests`)와 일차가 바뀔 때(`seasonDay`)
  // 다시 읽는다.**
  //
  // `requests` 가 없으면 첫 조회가 실패했을 때 버튼이 죽는다 — 내용이 없어 다이얼로그는
  // 안 보이는데 `open` 은 참이라, 다시 눌러도 이 효과가 깨어나지 않는다.
  // `seasonDay` 가 없으면 수동으로 열어 둔 탭이 다음 일차로 넘어가도 어제 청약 일정을
  // 계속 보여준다 — 수동 열람은 브리핑 구간이 끝나도 닫히지 않기 때문이다.
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    fetchBriefing()
      .then((fresh) => {
        if (!cancelled) setLoaded({ day: seasonDay, briefing: fresh });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.warn("개장 브리핑을 불러오지 못했습니다", error);
        // 자동 표시는 조용히 지나간다 (다음 날 다시 열린다). **손으로 연 것은 말해 준다** —
        // 누른 사람에게 아무 일도 일어나지 않는 화면은 고장과 구분되지 않는다.
        if (manual) {
          toast.error(
            "오늘 브리핑을 불러오지 못했습니다. 잠시 후 다시 눌러 주세요."
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [open, requests, seasonDay, manual]);

  // 닫는 것은 곧 확인이다. 그러지 않으면 브리핑 구간 내내 매초 다시 열린다.
  const close = useCallback(() => {
    trackEvent("briefing_complete", { season_day: clock.seasonDay ?? 0 });
    setAckedDay(clock.seasonDay);
    // 손으로 연 것도 같은 닫기를 쓴다. 자동 표시는 위의 `ackedDay` 가, 수동은 이 번호가
    // 각자 닫힌 상태를 기억한다.
    setOpenedRequest(requests);
  }, [clock.seasonDay, requests]);

  return (
    <>
      {children}
      <BriefingDialog
        // **오늘 것이 아니면 그리지 않는다.** 다이얼로그는 내용이 없으면 아무것도 그리지
        // 않으므로, 일차가 바뀌는 순간 어제 일정이 사라지고 새 조회가 끝나면 다시 찬다.
        briefing={loaded && loaded.day === seasonDay ? loaded.briefing : null}
        open={open}
        onOpenChange={(next) => !next && close()}
        onAcknowledge={close}
      />
    </>
  );
}
