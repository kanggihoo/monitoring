/**
 * 투자 성향 설문의 답 (`GET/PUT /api/v1/onboarding/self-profile`).
 *
 * **프로세스 메모리에만 둔다.** 목에는 계좌 테이블이 없고, 이 값이 필요한 이유는 화면이
 * 저장·조회를 왕복하는지를 보려는 것뿐이다. 목을 껐다 켜면 답이 사라지는데 그것이 "아직
 * 답하지 않은 사람" 경로를 보는 가장 쉬운 방법이기도 하다.
 *
 * 실제 백엔드는 `accounts.self_answers` 에 저장하고, 폐장 뒤 AI 로 보내는 누적 집계의
 * `selfAnswers` 에 실어 결과지의 '내가 생각한 나' 를 만든다. **목의 리포트는 그 연결이
 * 없다** — `reports.ts` 의 `review` 가 고정값이라, 여기서 답을 바꿔도 결과지 ③ 은 그대로다.
 */

/** 답 원본의 형식. 서버의 `ck_accounts_self_answers_format` 과 같은 규칙이다. */
const ANSWERS = /^[1-4]{5}$/;

let answers: string | null = null;
let updatedAt: string | null = null;

export function selfProfile(): {
  answers: string | null;
  updatedAt: string | null;
} {
  return { answers, updatedAt };
}

/** @returns 저장된 값, 또는 형식이 어긋나면 `undefined` */
export function saveSelfProfile(
  value: unknown
): { answers: string; updatedAt: string } | undefined {
  if (typeof value !== "string" || !ANSWERS.test(value)) {
    return undefined;
  }
  answers = value;
  updatedAt = new Date().toISOString();
  return { answers, updatedAt };
}
