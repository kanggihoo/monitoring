import { createStore } from "zustand/vanilla";

import type { Envelope } from "@/lib/ws";

/** 사건은 마지막 한 통만 유지한다. 화면별 과거 목록은 현재 조회 페이지에만 보관한다. */
export const liveQueryStore = createStore<{
  event: Envelope | null;
  generation: number;
  receive: (event: Envelope) => void;
  reset: () => void;
}>((set) => ({
  event: null,
  generation: 0,
  receive: (event) => set({ event }),
  reset: () =>
    set((state) => ({ event: null, generation: state.generation + 1 })),
}));
