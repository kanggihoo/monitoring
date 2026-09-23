import { apiErrorMessage, apiFetch } from "@/lib/api";
import { readJson } from "@/lib/market-api";
import type { ChatMessage } from "@/lib/market-types";

/**
 * 시즌 채팅 (`soc.md`). **전송은 REST, 수신만 실시간이다.**
 *
 * WebSocket 으로 보내지 않는 이유는 못 보내서가 아니라 **보낸 것과 돌아온 것을 짝지어 주는
 * 장치가 프로토콜에 없어서**다. 쿨다운 거부(429)와 금칙어 마스킹 둘 다 "내가 보낸 그것"의
 * 결과를 알아야 하는데, 그 짝짓기를 만들면 HTTP 를 다시 만드는 일이 된다.
 *
 * **조회는 공개다** (ADR-0005) — 미인증도 읽고 전송만 로그인이 필요하다 (기획 12.1.5).
 */

/** 커서 페이지. 스크롤 도중 위쪽이 자라므로 오프셋을 쓰지 않는다. */
export type ChatHistory = {
  /** **최신순이다** (`OrderByIdDesc`). 화면은 뒤집어 오래된 것부터 그린다. */
  items: ChatMessage[];
  nextCursor: string | null;
  hasNext: boolean;
};

/**
 * 쿨다운에 걸렸다. **429 만 이 오류다** — 계약이 상태 표에 없는 코드를 여기서만 예외로 둔
 * 이유가 이것이다 (`soc.md`): 409 로 뭉뚱그리면 "3초 뒤 다시" 라는 재시도 가능한 실패와
 * "열람 전용이라 불가" 라는 재시도 불가능한 실패를 화면이 구분하지 못한다.
 */
export class ChatCooldownError extends Error {
  constructor(readonly retryAfterSeconds: number) {
    super(`${retryAfterSeconds}초 뒤에 다시 보낼 수 있습니다.`);
  }
}

/**
 * 히스토리. 입장 시 최근 메시지를 받는다 (기획 12.1.3).
 *
 * @param cursor 과거 방향으로 더 불러올 지점
 * @param size 기본 100 — 계약의 기본값과 같다
 */
export const fetchChatHistory = (cursor?: string | null, size = 100) => {
  const query = new URLSearchParams({ size: String(size) });
  if (cursor) query.set("cursor", cursor);
  return readJson<ChatHistory>(`/api/v1/chat/messages?${query}`);
};

/**
 * 전송. **응답의 메시지를 쓴다** — 보낸 문장이 아니라 저장된 문장이다. 금칙어는 차단이
 * 아니라 마스킹이라(기획 12.2.2) `"ㅅㅂ 개폭락"` 이 `"** 개폭락"` 으로 돌아온다.
 *
 * @throws 쿨다운이면 `ChatCooldownError`, 그 밖의 실패는 문구를 담은 Error
 *         (열람 전용 구간은 409)
 */
export async function sendChatMessage(content: string): Promise<ChatMessage> {
  const response = await apiFetch("/api/v1/chat/messages", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ content }),
  });

  if (response.ok) {
    return ((await response.json()) as { data: ChatMessage }).data;
  }
  if (response.status === 429) {
    // 남은 초는 헤더에 온다. 없으면 계약의 쿨다운 3초로 본다.
    const retryAfter = Number(response.headers.get("Retry-After"));
    throw new ChatCooldownError(
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 3
    );
  }
  throw new Error(
    await apiErrorMessage(response, "메시지를 보내지 못했습니다.")
  );
}
