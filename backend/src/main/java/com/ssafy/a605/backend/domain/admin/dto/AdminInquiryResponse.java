package com.ssafy.a605.backend.domain.admin.dto;

import java.time.Instant;

import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;

/**
 * 관리자가 보는 문의 하나. 목록에서 바로 답변하므로 본문과 답변을 함께 담는다 — 상세 조회
 * 엔드포인트를 따로 두지 않는 이유다.
 *
 * @param authorNickname 작성자 닉네임. 작성자에는 외래키가 있어 항상 채워진다
 * @param answeredBy     답변한 관리자의 식별자 (답변 전이면 null). 참조가 아니라 그 시점의
 *                       기록이므로, 그 관리자가 탈퇴했다면 이 값으로 사용자를 조회할 수 없다
 */
public record AdminInquiryResponse(
        Long id,
        Long authorUserId,
        String authorNickname,
        String title,
        String content,
        InquiryStatus status,
        String answer,
        Long answeredBy,
        Instant answeredAt,
        Instant createdAt
) {

    /**
     * @param inquiry        변환할 문의
     * @param authorNickname 작성자 닉네임
     * @return 응답용 DTO
     */
    public static AdminInquiryResponse from(Inquiry inquiry, String authorNickname) {
        return new AdminInquiryResponse(inquiry.getId(), inquiry.getUserId(), authorNickname,
                inquiry.getTitle(), inquiry.getContent(), inquiry.getStatus(), inquiry.getAnswer(),
                inquiry.getAnsweredBy(), inquiry.getAnsweredAt(), inquiry.getCreatedAt());
    }
}
