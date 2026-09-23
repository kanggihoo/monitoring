package com.ssafy.a605.backend.domain.inquiry.dto;

import java.time.Instant;

import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;

/**
 * 목록의 한 줄. 본문과 답변을 담지 않는다 — 한 페이지에 2000자 본문 스무 개를 실을 이유가 없고,
 * 목록에서 필요한 것은 무엇을 물었는지와 답변이 달렸는지뿐이다.
 *
 * @param answeredAt 답변 시각 (답변 전이면 null)
 */
public record InquirySummaryResponse(
        Long id,
        String title,
        InquiryStatus status,
        Instant answeredAt,
        Instant createdAt
) {

    /**
     * @param inquiry 변환할 문의
     * @return 목록용 DTO
     */
    public static InquirySummaryResponse from(Inquiry inquiry) {
        return new InquirySummaryResponse(inquiry.getId(), inquiry.getTitle(), inquiry.getStatus(),
                inquiry.getAnsweredAt(), inquiry.getCreatedAt());
    }
}
