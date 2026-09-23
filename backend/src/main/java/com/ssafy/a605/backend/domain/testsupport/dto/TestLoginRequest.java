package com.ssafy.a605.backend.domain.testsupport.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param name 로그인할 Test User 의 이름 (닉네임이 아니다)
 */
public record TestLoginRequest(@NotBlank String name) {
}
