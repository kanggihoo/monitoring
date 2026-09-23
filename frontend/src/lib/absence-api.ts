import { apiFetch } from "@/lib/api";
import { readJson } from "@/lib/market-api";
import type { AbsenceSummary } from "@/lib/market-types";

/**
 * 부재 중 요약 (`acc.md`). 조회와 확인이 한 흐름이라 함께 둔다 — 확인은 상태를 바꾸므로
 * 조회만 모아 둔 `market-api.ts` 가 아니라 여기서 `apiFetch` 를 거친다 (`order-api.ts` 와
 * 같은 방식이다).
 */

/**
 * **조회 시점은 프론트가 정한다** — 서버는 판정 결과만 돌려주고 장중에 모달이 저절로
 * 튀어나오는 경로는 없다. 상태를 바꾸지 않으므로 여러 번 불러도 된다.
 */
export const fetchAbsenceSummary = () =>
  readJson<AbsenceSummary>("/api/v1/accounts/me/absence-summary");

/**
 * 확인 처리. **모달이 보여준 `toTickSeq` 를 그대로 돌려보낸다** — 지금 시점의 틱을 새로
 * 읽어 보내면 모달을 띄워 둔 채 자리를 비운 시간까지 "본 것" 이 되어 다음 부재가 통째로
 * 사라진다.
 *
 * 서버가 `max(기존, 보낸 값)` 으로 갱신하므로 재시도해도 뒤로 가지 않고, 같은 값을 여러 번
 * 보내도 결과가 같다.
 */
export async function acknowledgeAbsence(ackedTickSeq: number): Promise<void> {
  const response = await apiFetch("/api/v1/accounts/me/absence-summary/ack", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ackedTickSeq }),
  });

  if (!response.ok) {
    throw new Error(`부재 요약 확인에 실패했습니다 (${response.status})`);
  }
}
