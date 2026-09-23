"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { AbsenceSummaryDialog } from "@/components/market/absence-summary";
import { acknowledgeAbsence, fetchAbsenceSummary } from "@/lib/absence-api";
import type { AbsenceSummary } from "@/lib/market-types";

type Absent = Extract<AbsenceSummary, { absent: true }>;

/**
 * 부재 중 요약을 여닫는다 (기획 7.5).
 *
 * **호출 시점을 프론트가 정한다** (`acc.md`). 부를 곳은 둘이다 — 앱 로드·로그인 직후(나갔다
 * 다시 들어옴)와 탭이 다시 활성화될 때(자리를 비웠다 돌아옴). 장중에 저절로 뜨는 경로는
 * 없으므로 틱을 보고 여는 길을 두지 않는다.
 *
 * **판정은 서버가 한다.** `현재 tickSeq − lastAckedTickSeq ≥ 50` 을 화면이 흉내내면 기준이
 * 둘이 되어, 모달이 뜨는 조건과 서버가 요약하는 구간이 어긋난다.
 *
 * 받은 요약은 **스냅샷이라 갱신하지 않는다.** 모달이 떠 있는 동안 뒤의 화면은 틱마다 계속
 * 움직이지만 이 숫자는 "없던 구간"의 정산 보고서다.
 */
export function AbsenceGate({ children }: { children: React.ReactNode }) {
  const [summary, setSummary] = useState<Absent | null>(null);
  /** 떠 있는 모달. 효과가 다시 돌지 않도록 상태가 아니라 참조로 읽는다. */
  const shown = useRef(false);

  useEffect(() => {
    let cancelled = false;

    const check = () => {
      // 이미 하나 떠 있으면 다시 묻지 않는다. 물으면 그 사이 흐른 틱까지 포함된 요약으로
      // 바뀌어, 사용자가 읽고 있던 숫자가 눈앞에서 갈린다.
      if (cancelled || shown.current) return;

      void fetchAbsenceSummary()
        .then((fresh) => {
          if (cancelled || shown.current || !fresh.absent) return;
          shown.current = true;
          setSummary(fresh);
        })
        .catch((error: unknown) =>
          console.warn("부재 요약을 불러오지 못했습니다", error)
        );
    };

    check();

    // 탭이 돌아왔다 — 그 사이 자리를 비웠을 수 있다. 게임 시계를 다시 받는
    // `market-live-provider.tsx` 와 같은 신호다.
    const onVisible = () => {
      if (document.visibilityState === "visible") check();
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, []);

  /**
   * 닫으면서 확인한다. **버튼과 Esc 가 같은 길이다** — 모달에 버튼이 하나뿐이라 "닫았지만
   * 확인하지 않은" 상태를 사용자가 구분할 방법이 없다.
   *
   * 확인이 실패해도 모달은 닫는다. 서버에 지점이 남지 않았으므로 다음 접속에 다시 뜬다.
   */
  const acknowledge = useCallback((ackedTickSeq: number) => {
    shown.current = false;
    setSummary(null);
    void acknowledgeAbsence(ackedTickSeq).catch((error: unknown) =>
      console.warn("부재 요약 확인을 보내지 못했습니다", error)
    );
  }, []);

  return (
    <>
      {children}
      <AbsenceSummaryDialog
        summary={summary}
        open={summary !== null}
        onOpenChange={(next) =>
          !next && summary && acknowledge(summary.toTickSeq)
        }
        onAcknowledge={acknowledge}
      />
    </>
  );
}
