package com.ssafy.a605.backend.domain.onboarding.dto;

import com.ssafy.a605.backend.domain.account.entity.Account;

import jakarta.validation.constraints.Pattern;

/**
 * 설문(선언) 성향 저장 요청.
 *
 * <p><b>형식만 본다.</b> 다섯 자리이고 각 자리가 1~4 인지까지다. 합계가 어느 단계인지는 화면이
 * 정하므로 여기서 채점하면 같은 판단이 두 곳에 생긴다.
 *
 * <p>같은 형식을 DB 제약({@code ck_accounts_self_answers_format})도 갖는다. 두 곳에 두는 이유는
 * 이 검증이 400 으로 돌려주기 위한 것이고, 그쪽은 다른 경로로 들어온 값까지 막기 위한 것이다.
 *
 * @param answers 다섯 문항의 답 원본 (예: {@code "24312"})
 */
public record SelfProfileRequest(
        @Pattern(regexp = Account.SELF_ANSWERS_PATTERN,
                message = "설문 답은 다섯 자리이고 각 자리가 1~4 여야 합니다.")
        String answers
) {
}
