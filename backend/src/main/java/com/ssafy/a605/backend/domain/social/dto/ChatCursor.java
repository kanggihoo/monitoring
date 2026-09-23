package com.ssafy.a605.backend.domain.social.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpStatus;

import com.ssafy.a605.backend.domain.social.entity.ChatMessage;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 채팅 히스토리의 커서 ({@code soc.md} §GET /api/v1/chat/messages).
 *
 * <p><b>메시지 식별자 하나로 충분하다.</b> 뉴스가 거래일과 식별자를 함께 싣는 것과 달리, 채팅은
 * 정렬 키가 식별자 하나다 — 보낸 시각이 아니라 식별자 역순이 목록의 순서이고, 그래야 같은 초에
 * 들어온 메시지가 페이지 경계에서 사라지거나 겹치지 않는다.
 *
 * <p>인코딩하는 이유는 클라이언트가 이 값을 해석해 조작하지 않게 하려는 것이다. 비밀은 아니고,
 * 우리가 만든 모양이 아니면 400 이다.
 *
 * @param messageId 이 커서가 가리키는 지점. 다음 페이지는 이보다 작은 식별자들이다
 */
public record ChatCursor(Long messageId) {

    /**
     * @param last 이번 페이지의 마지막 메시지
     * @return 그 다음 페이지를 가리키는 커서
     */
    public static ChatCursor after(ChatMessage last) {
        return new ChatCursor(last.getId());
    }

    /**
     * @param encoded 이전 응답의 nextCursor. {@code null} 이거나 비어 있으면 첫 페이지
     * @return 커서. 첫 페이지면 {@code null}
     * @throws BusinessException 우리가 만든 모양이 아닐 때 (400)
     */
    public static ChatCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new ChatCursor(Long.parseLong(decoded));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "커서 형식이 올바르지 않습니다.");
        }
    }

    /**
     * @return 다음 요청에 그대로 실어 보낼 문자열
     */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(messageId).getBytes(StandardCharsets.UTF_8));
    }
}
