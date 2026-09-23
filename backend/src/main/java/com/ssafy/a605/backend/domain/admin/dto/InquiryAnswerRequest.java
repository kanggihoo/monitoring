package com.ssafy.a605.backend.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 답변 등록·수정 요청. 등록과 수정이 같은 요청이라 필드가 하나뿐이다.
 *
 * @param answer 답변 내용 (컬럼 길이는 2000자)
 */
public record InquiryAnswerRequest(
        @NotBlank(message = "답변을 입력해 주세요.")
        @Size(max = 2000, message = "답변은 2000자 이내여야 합니다.")
        String answer
) {

    /** 검증은 공백을 제거한 값으로 한다 — "2000자 + 앞뒤 공백" 이 길이 초과로 거부되지 않도록. */
    public InquiryAnswerRequest {
        answer = (answer == null) ? null : answer.trim();
    }
}
