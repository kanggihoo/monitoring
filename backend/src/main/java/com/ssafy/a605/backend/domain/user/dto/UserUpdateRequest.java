package com.ssafy.a605.backend.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 현재 사용자 정보 부분 수정 요청. 항목이 늘어도 엔드포인트가 늘지 않도록 필드별로 나누지 않는다.
 *
 * <p><b>형식만 본다.</b> 금칙어와 중복은 사전과 다른 행을 봐야 하므로 서비스가 판정한다
 * ({@code UserService.changeNickname}).
 *
 * @param nickname 새 닉네임. 공백 제거 후 2~12 자, 한글·영문·숫자만 (기획서 2.1.2)
 */
public record UserUpdateRequest(
        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Size(min = MIN_LENGTH, max = MAX_LENGTH,
                message = "닉네임은 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.")
        @Pattern(regexp = ALLOWED, message = "닉네임에는 한글·영문·숫자만 쓸 수 있습니다.")
        String nickname
) {

    /** 기획서 2.1.2 의 길이 하한. 1 자 닉네임은 리더보드에서 사람을 구분하지 못한다. */
    public static final int MIN_LENGTH = 2;

    /** 길이 상한. 컬럼 길이(50)가 아니라 기획서 값이다. */
    public static final int MAX_LENGTH = 12;

    /**
     * 허용 문자. 한글 음절·자모, 영문, 숫자만이다.
     *
     * <p><b>공백과 이모지를 막는다.</b> 리더보드·채팅·시상이 이 값을 그대로 그리므로, 폭이
     * 들쭉날쭉한 문자나 보이지 않는 공백이 섞이면 그 화면들이 깨진다.
     *
     * <p>자모(ㄱ-ㅎ, ㅏ-ㅣ)를 허용 범위에 넣는 이유는 "ㅋㅋㅋ" 같은 값이 기획서가 말하는
     * 한글에서 빠질 이유가 없기 때문이다. 조합되지 않은 자모도 화면에서 한 글자 폭이다.
     */
    public static final String ALLOWED = "^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+$";

    /** 검증은 공백을 제거한 값으로 한다 — 앞뒤 공백이 길이에 섞이지 않도록. */
    public UserUpdateRequest {
        nickname = (nickname == null) ? null : nickname.trim();
    }
}
