package com.ssafy.a605.backend.domain.social.dto;

import java.util.List;

/**
 * {@code GET /api/v1/chat/messages} 의 {@code data} ({@code soc.md}).
 *
 * <p>페이지 번호가 아니라 커서다. 채팅은 새 메시지가 계속 앞에 쌓이므로 번호 페이지로 과거를
 * 넘기면 그 사이 들어온 메시지 때문에 같은 줄을 두 번 보거나 건너뛴다.
 *
 * @param items      최신순 메시지. 첫 항목이 가장 최근이다
 * @param nextCursor 과거 방향으로 더 불러올 커서. 더 없으면 {@code null}
 * @param hasNext    더 불러올 것이 있는가
 */
public record ChatHistoryResponse(List<ChatMessageResponse> items, String nextCursor, boolean hasNext) {
}
