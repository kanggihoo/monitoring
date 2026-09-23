package com.ssafy.a605.backend.domain.inquiry.dto;

import java.time.Instant;

import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;

/**
 * 문의 하나의 전문. 질문과 답변을 나란히 읽도록 함께 담는다.
 *
 * <p>답변자 식별자는 담지 않는다. 사용자에게는 누가 답했는지가 정보가 아니고, 그 사람이
 * 탈퇴했을 수도 있다.
 *
 * @param answer     답변 내용 (답변 전이면 null)
 * @param answeredAt 답변 시각 (답변 전이면 null)
 */
public record InquiryResponse(
        Long id,
        String title,
        String content,
        InquiryStatus status,
        String answer,
        Instant answeredAt,
        Instant createdAt
) {

    /**
     * @param inquiry 변환할 문의
     * @return 응답용 DTO
     */
    public static InquiryResponse from(Inquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getTitle(), inquiry.getContent(),
                inquiry.getStatus(), inquiry.getAnswer(), inquiry.getAnsweredAt(), inquiry.getCreatedAt());
    }
}
