package com.ssafy.a605.backend.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 전원 공지 발송 요청.
 *
 * <p>길이 상한은 {@code notifications.title}·{@code body} 의 컬럼 길이다. 화면에서만 막으면
 * API 를 직접 부른 요청이 DB 오류로 500 이 된다.
 *
 * @param title  제목
 * @param body   본문
 * @param urgent 긴급 공지인가. 단계는 둘뿐이라 불리언이다 — 셋 이상이 되면 유형이 아니라
 *               우선순위 컬럼으로 옮긴다 ({@code NotificationKind.URGENT_NOTICE})
 */
public record AdminNoticeRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 100, message = "제목은 100자 이내여야 합니다.")
        String title,

        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 500, message = "본문은 500자 이내여야 합니다.")
        String body,

        boolean urgent
) {

    /** 검증은 공백을 제거한 값으로 한다 — 문의 답변과 같은 규칙이다. */
    public AdminNoticeRequest {
        title = (title == null) ? null : title.trim();
        body = (body == null) ? null : body.trim();
    }
}
