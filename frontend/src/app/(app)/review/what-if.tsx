"use client";

import { useEffect, useState } from "react";

import { PriceChange } from "@/components/market/price-change";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchWhatIf } from "@/lib/market-api";
import type { InstrumentMeta, WhatIf } from "@/lib/market-types";
import { cn, FOCUS_RING } from "@/lib/utils";

import { SectionLabel } from "./sections";

/**
 * '만약에' 시뮬레이터 (기획 13.3.4).
 *
 * *"1일차 개장에 이 종목을 전액 매수해 3일차까지 보유했다면"* 을 내 성적과 견준다. 옆의
 * '가만히 있었다면' 이 지수를 견주는 것과 짝이다 — 그쪽은 고정된 기준선이고 이쪽은 28 종을
 * 눌러 가며 고르는 쪽이다.
 *
 * **종목을 고를 때마다 한 건씩 부른다.** 계약이 시즌 리포트에 담지 않고 별도 엔드포인트로
 * 뺀 이유가 그것이다 (`rpt.md`) — 미리 28 건을 당기면 대부분 안 쓰인다.
 *
 * **시즌 축과 일차 축을 나눠 그린다.** 시즌은 누적 대 누적, 일차는 당일 대 당일이다.
 * 한 줄에 섞으면 "내 시즌 84% 대 1일차 종목 38%" 처럼 성립하지 않는 비교가 된다.
 */

/** 비교 한 쌍. 막대 없이 숫자만 둔다 — 옆 카드의 막대와 축이 다르므로 섞어 보이면 안 된다. */
function Facing({
  label,
  held,
  mine,
  heldNote,
  mineNote,
}: {
  label: string;
  held: number | null;
  mine: number | null;
  /** 종목 쪽이 비었을 때의 까닭. */
  heldNote: string;
  /** 내 쪽이 비었을 때의 까닭. */
  mineNote: string;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <SectionLabel>{label}</SectionLabel>
      <div className="grid grid-cols-2 gap-3">
        <div className="flex flex-col gap-0.5 rounded-md bg-muted px-3 py-2">
          <span className="text-2xs text-muted-foreground">
            들고만 있었다면
          </span>
          {held === null ? (
            <span className="text-2xs text-muted-foreground">{heldNote}</span>
          ) : (
            <PriceChange value={held} />
          )}
        </div>
        <div className="flex flex-col gap-0.5 rounded-md bg-muted px-3 py-2">
          <span className="text-2xs text-muted-foreground">나</span>
          {mine === null ? (
            <span className="text-2xs text-muted-foreground">{mineNote}</span>
          ) : (
            <PriceChange value={mine} />
          )}
        </div>
      </div>
    </div>
  );
}

export function WhatIfCard({ instruments }: { instruments: InstrumentMeta[] }) {
  const [selectedId, setSelectedId] = useState<number>(
    instruments[0]?.instrumentId ?? 0
  );
  /**
   * 답이 온 종목과 함께 든다. 고른 종목과 다르면 쓰지 않는다 — 종목을 바꾸는 동안 이전
   * 종목의 수익률이 새 이름 아래 남아 있으면 화면이 조용히 거짓말을 한다 (마켓 화면의
   * 주문 미리보기와 같은 판단).
   */
  const [answered, setAnswered] = useState<{
    instrumentId: number;
    /** 아직 발표된 일차가 없으면(409) null 이다. */
    data: WhatIf | null;
    failed: boolean;
  } | null>(null);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    fetchWhatIf(selectedId)
      .then((fresh) => {
        if (!cancelled)
          setAnswered({
            instrumentId: selectedId,
            data: fresh,
            failed: false,
          });
      })
      // 시뮬레이터만 비운다. 시즌 성적과 매매 지점은 이것과 무관하게 보여야 한다.
      .catch((error: unknown) => {
        if (cancelled) return;
        console.warn("'만약에' 결과를 불러오지 못했습니다", error);
        setAnswered({ instrumentId: selectedId, data: null, failed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  const current = answered?.instrumentId === selectedId ? answered : null;

  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-3">
        <SectionLabel>만약에</SectionLabel>

        {/* 종목 선택. 28 종이라 목록을 접지 않고 그대로 흐르게 둔다. */}
        <div
          role="group"
          aria-label="비교할 종목"
          className="flex flex-wrap gap-1.5"
        >
          {instruments.map((instrument) => (
            <button
              key={instrument.instrumentId}
              type="button"
              aria-pressed={instrument.instrumentId === selectedId}
              onClick={() => setSelectedId(instrument.instrumentId)}
              className={cn(
                FOCUS_RING,
                "rounded-md px-2 py-1 text-2xs",
                instrument.instrumentId === selectedId
                  ? "bg-surface-feature text-surface-feature-foreground"
                  : "bg-muted text-foreground-subtle"
              )}
            >
              {instrument.name}
            </button>
          ))}
        </div>

        {current === null ? (
          <Skeleton className="h-28 w-full rounded-md" />
        ) : current.failed ? (
          <p className="py-6 text-center text-xs text-muted-foreground">
            결과를 불러오지 못했습니다.
          </p>
        ) : current.data === null ? (
          // 409. 실패가 아니라 아직 열리지 않은 정상 상태다 (`rpt.md`).
          <p className="py-6 text-center text-xs text-muted-foreground">
            정산이 발표된 일차가 아직 없습니다. 1일차 발표(17:00) 이후에 비교할
            수 있습니다.
          </p>
        ) : (
          <div className="flex flex-col gap-4">
            <Facing
              label="시즌 전체"
              held={current.data.returnRate}
              mine={current.data.myReturnRate}
              heldNote="3일차 발표 후"
              mineNote="3일차 발표 후"
            />

            {current.data.days.map((day) => (
              <Facing
                key={day.seasonDay}
                label={`DAY ${day.seasonDay}`}
                held={day.returnRate}
                mine={day.myReturnRate}
                heldNote="그날은 아직 상장 전"
                mineNote="그날은 참가 전"
              />
            ))}
          </div>
        )}

        {/* 옆 카드('가만히 있었다면')와 같은 단서다. 이 가정을 적지 않으면 사용자가
            "내가 이만큼 손해 봤다"로 읽는다 — 실제 매매에는 수수료가 붙는다. */}
        <p className="text-2xs text-muted-foreground">
          시작 잔고 전액으로 그 종목만 들고 있었다고 본다. 수수료와 환전
          수수료를 반영하지 않는다.
        </p>
      </CardContent>
    </Card>
  );
}
