"use client";

import { useCallback, useEffect, useState } from "react";
import { useStore } from "zustand";

import type { Envelope } from "@/lib/ws";
import { liveQueryStore } from "@/stores/live-query-store";

export type LiveReducer<T> = (data: T, event: Envelope) => T;

/**
 * 진입·검색 조건 변경·연결 복구에만 조회하고, 이후에는 WS 본문으로 현재 페이지를 갱신한다.
 * read와 reduce는 안정된 참조를 전달한다. 초기 조회 중 사건도 최대 128개만 임시 보관한다.
 */
export function useLiveQuery<T>(
  read: () => Promise<T>,
  reduce?: LiveReducer<T>,
  enabled = true
) {
  const generation = useStore(liveQueryStore, (state) => state.generation);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const reload = useCallback(() => setRevision((value) => value + 1), []);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let loading = true;
    let overflow = false;
    const pending: Envelope[] = [];
    const unsubscribe = liveQueryStore.subscribe((state, previous) => {
      if (!reduce || !state.event || state.event === previous.event) return;
      if (loading) {
        if (pending.length < 128) pending.push(state.event);
        else overflow = true;
      } else {
        setData((current) =>
          current === null ? current : reduce(current, state.event!)
        );
      }
    });
    read()
      .then((value) => {
        if (cancelled) return;
        if (overflow) {
          reload();
          return;
        }
        setData(reduce ? pending.reduce(reduce, value) : value);
        setError(null);
        loading = false;
        pending.length = 0;
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        loading = false;
        pending.length = 0;
        setError(
          cause instanceof Error ? cause.message : "조회에 실패했습니다."
        );
      });
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [enabled, read, reduce, generation, revision, reload]);

  return { data: enabled ? data : null, error, reload };
}
