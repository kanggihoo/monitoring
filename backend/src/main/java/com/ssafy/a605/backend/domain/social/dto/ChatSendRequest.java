package com.ssafy.a605.backend.domain.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/chat/messages} 의 본문 ({@code soc.md}).
 *
 * <p>형식 검증만 여기서 한다 — 빈 문자열과 길이다. 금칙어는 검증이 아니라 마스킹이고(기획서 12.2.2),
 * 쿨다운은 형식이 아니라 서버 상태라 서비스가 판정한다.
 *
 * @param content 보낼 본문. 공백만이면 거부하고 200자를 넘으면 거부한다 (기획서 12.2.1)
 */
public record ChatSendRequest(
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 200, message = "200자 이내로 입력해 주세요.")
        String content
) {
}
