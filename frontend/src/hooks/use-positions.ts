"use client";

import { useStore } from "zustand";

import { reloadPositions, tickStore } from "@/stores/tick-store";

/** 앱 진입 때 받은 보유 목록을 화면 간 공유한다. 틱 갱신은 account.positions가 맡는다. */
export function usePositions(enabled = true) {
  const data = useStore(tickStore, (state) => state.positions);
  return { data: enabled ? data : null, reload: reloadPositions };
}
