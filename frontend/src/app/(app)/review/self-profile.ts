"use client";

import { useSyncExternalStore } from "react";

import { apiErrorMessage, apiFetch } from "@/lib/api";
import { readPersonalJson } from "@/lib/market-api";

/**
 * 투자 성향 설문의 답이 사는 곳. 화면(`onboarding.tsx`)과 선택 줄(`review-screen.tsx`)이
 * 같은 값을 본다.
 *
 * **서버가 정본이다.** 예전에는 `localStorage` 에 뒀는데, 그러면 답이 브라우저를 넘지 못해
 * 결과지의 '내가 생각한 나'·'예상과 실제의 차이' 두 칸이 영영 비었다 — 백엔드가 AI 로 보내는
 * 누적 집계의 `selfAnswers` 는 `accounts.self_answers` 에서 나오기 때문이다. 저장 경로는
 * 이제 이렇게 이어진다.
 *
 * ```
 * 저장 → PUT /api/v1/onboarding/self-profile → accounts.self_answers
 *      → 폐장 뒤 cumulative.selfAnswers → AI → review.selfLevel·selfView·profileGap
 *      → 다음 정산 발표부터 결과지 ③⑦ 이 채워진다
 * ```
 *
 * **리액트 밖의 저장소라 모듈 하나가 값을 들고 있는다.** 훅 안에 두면 이 값을 보는 두 화면이
 * 각자 부르게 되고, 한쪽에서 저장한 것이 다른 쪽에 안 보인다.
 */

/** 답 원본의 형식. 서버의 `ck_accounts_self_answers_format` 과 같은 규칙이다. */
const ANSWERS = /^[1-4]{5}$/;

export type SelfProfile = {
  /** 답한 적이 없으면 `null`. */
  answers: string | null;
  updatedAt: string | null;
};

/**
 * 이 화면이 아는 설문의 상태.
 *
 * `loading` 과 `ready & answers === null` 을 **가른다.** 둘을 섞으면 아직 불러오는 중인
 * 사람에게 "선택 사항" 이 한 번 번쩍이고, 이미 답한 사람은 자기 답이 없어진 줄 안다.
 */
export type SelfProfileState = {
  status: "loading" | "ready" | "failed";
  answers: string | null;
  /** 불러오기가 실패했을 때의 안내. */
  error: string | null;
};

const LOADING: SelfProfileState = {
  status: "loading",
  answers: null,
  error: null,
};

let state: SelfProfileState = LOADING;
let loading: Promise<void> | null = null;

const listeners = new Set<() => void>();

function set(next: SelfProfileState) {
  state = next;
  for (const notify of listeners) notify();
}

/**
 * 처음 구독될 때 한 번 불러온다. **화면이 부르지 않는다** — 이 값을 보는 곳이 둘이라,
 * 각자 부르게 두면 같은 조회가 두 번 나가고 순서에 따라 값이 갈린다.
 */
function load(): Promise<void> {
  loading ??= fetchSelfProfile()
    .then((profile) => {
      // 형식이 어긋난 값은 없는 것과 같게 다룬다 (AI 쪽 `score_answers` 와 같은 규칙).
      const answers =
        profile.answers && ANSWERS.test(profile.answers)
          ? profile.answers
          : null;
      set({ status: "ready", answers, error: null });
    })
    .catch((error: unknown) => {
      console.error("성향 설문을 불러오지 못했습니다", error);
      set({
        status: "failed",
        answers: null,
        error: "저장된 답을 불러오지 못했습니다.",
      });
    })
    .finally(() => {
      loading = null;
    });
  return loading;
}

function subscribe(notify: () => void) {
  listeners.add(notify);
  if (state.status === "loading") void load();
  return () => {
    listeners.delete(notify);
  };
}

/**
 * 설문 상태를 읽는 훅.
 *
 * 서버 렌더에는 값이 없으므로 `getServerSnapshot` 이 `loading` 을 준다 — 마운트 뒤에
 * 채워지고 하이드레이션이 어긋나지 않는다.
 */
export function useSelfProfile(): SelfProfileState {
  return useSyncExternalStore(
    subscribe,
    () => state,
    () => LOADING
  );
}

/** 다시 불러온다. 실패 안내의 '다시 시도' 가 쓴다. */
export function reloadSelfProfile(): Promise<void> {
  set(LOADING);
  return load();
}

export const fetchSelfProfile = () =>
  readPersonalJson<SelfProfile>("/api/v1/onboarding/self-profile");

/**
 * 답을 저장한다. **언제든 다시 고칠 수 있으므로 덮어쓰기다** (기획 확정) — 최초 저장과
 * 수정을 가르지 않는다. 성공하면 저장소가 곧바로 새 답을 들고, 보는 화면 둘이 함께 바뀐다.
 *
 * @throws 실패하면 서버가 준 문구를 담은 `Error`. 저장소는 건드리지 않는다 — 저장이 실패한
 *         자리에 성공한 것처럼 값을 넣으면 다음 화면에서 되돌아간 것처럼 보인다
 */
export async function saveSelfProfile(answers: string): Promise<void> {
  const response = await apiFetch("/api/v1/onboarding/self-profile", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ answers }),
  });
  if (!response.ok) {
    // **상태 코드는 콘솔에만 남긴다.** 백엔드가 안 떠 있으면 본문이 JSON 도 아닌
    // `Internal Server Error` 라, 화면에는 서버 문구 대신 기본 안내가 나간다 — 그것만
    // 보고는 "서버가 거절했다" 와 "서버에 닿지도 못했다" 를 가릴 수 없다.
    console.error("성향 설문 저장 실패", response.status, response.statusText);
    throw new Error(
      await apiErrorMessage(
        response,
        "성향 설문을 저장하지 못했습니다. 잠시 뒤 다시 눌러 주세요."
      )
    );
  }
  const body = (await response.json()) as { data: SelfProfile };
  set({
    status: "ready",
    answers: body.data.answers ?? answers,
    error: null,
  });
}
