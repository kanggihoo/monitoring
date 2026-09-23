package com.ssafy.a605.backend.domain.inquiry.dto;

import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문의 작성 요청. 작성 후 본문을 고치는 수단은 없다 — 답변 근거가 되는 질문이 바뀌면 답변이
 * 엉뚱해지므로, 지우고 새로 쓰게 한다.
 *
 * @param title   제목 (컬럼 길이는 100자)
 * @param content 내용 (컬럼 길이는 2000자)
 */
public record InquiryCreateRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 100, message = "제목은 100자 이내여야 합니다.")
        String title,

        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 2000, message = "내용은 2000자 이내여야 합니다.")
        String content
) {

    /** 검증은 공백을 제거한 값으로 한다 — "100자 + 앞뒤 공백" 이 길이 초과로 거부되지 않도록. */
    public InquiryCreateRequest {
        title = (title == null) ? null : title.trim();
        content = (content == null) ? null : content.trim();
    }

    /** @return 답변 전 상태의 문의 */
    public Inquiry toEntity(Long userId) {
        return new Inquiry(userId, title, content);
    }
}
