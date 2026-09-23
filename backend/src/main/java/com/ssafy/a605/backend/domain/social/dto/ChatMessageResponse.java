package com.ssafy.a605.backend.domain.social.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.ssafy.a605.backend.domain.social.entity.ChatMessage;
import com.ssafy.a605.backend.domain.user.entity.User;

/**
 * 채팅 메시지 한 줄 ({@code soc.md} §GET /api/v1/chat/messages). 실시간 {@code chat.message} 의
 * payload 도 이 모양이며, 전송 응답도 같다 — 보낸 사람이 자기 화면에 그린 문장을 저장된 것으로
 * 바꿔 그릴 수 있어야 한다(금칙어 마스킹).
 *
 * <p><b>닉네임과 아바타는 저장값이 아니라 조인 결과다.</b> 닉네임 변경이 과거 메시지에도 반영되어야
 * 하고(기획서 2.1.4), 탈퇴자 익명화도 조인이 비는 것으로 자동 처리된다(2.4.6).
 *
 * @param messageId 메시지 식별자. 커서 페이지네이션의 정렬 키이기도 하다
 * @param nickname  보낸 사람 닉네임. 탈퇴했으면 {@code null}
 * @param avatarId  보낸 사람 아바타. 탈퇴했으면 {@code null}
 * @param content   금칙어 마스킹을 마친 본문. 관리자가 삭제한 메시지는 {@code null}
 * @param sentAt    보낸 실제 시각
 * @param gameDate  보낸 시점의 게임 거래일. 재생 중이 아니었으면 {@code null}
 * @param withdrawn 보낸 사람이 탈퇴했는가. 참이면 화면이 "탈퇴한 사용자"로 그린다
 * @param deleted   관리자가 삭제했는가. 참이면 화면이 "삭제된 메시지"로 그린다
 */
public record ChatMessageResponse(Long messageId, String nickname, Short avatarId, String content,
                                  Instant sentAt, LocalDate gameDate, boolean withdrawn, boolean deleted) {

    /**
     * @param message  메시지 행
     * @param sender   보낸 사람. 탈퇴했으면 {@code null}
     * @param gameDate {@code tickSeq} 로 조인한 게임 거래일. 없으면 {@code null}
     * @return 응답 한 줄
     */
    public static ChatMessageResponse of(ChatMessage message, User sender, LocalDate gameDate) {
        boolean deleted = message.getDeletedAt() != null;
        return new ChatMessageResponse(message.getId(),
                sender == null ? null : sender.getNickname(),
                sender == null ? null : sender.getAvatarId(),
                // 삭제된 메시지의 본문은 내려주지 않는다 — 행을 남기는 것은 커서 때문이고
                // 내용을 보이게 하려는 것이 아니다.
                deleted ? null : message.getContent(),
                message.getSentAt(), gameDate, sender == null, deleted);
    }
}
