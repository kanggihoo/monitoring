package com.ssafy.a605.backend.domain.admin.dto;

import java.time.Instant;
import java.util.List;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;

/**
 * 관리자 콘솔 사용자 목록의 한 행.
 *
 * <p>식별자와 가입일을 함께 담는 이유는 동명이인 때문이다. 이메일이 없는 사용자끼리는 닉네임만으로
 * 구별할 수 없다.
 *
 * @param email     표시용 이메일. 없을 수 있다
 * @param providers 로그인 방법. 계정 연결 기능이 없어 지금은 항상 하나다
 */
public record AdminUserResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        Instant createdAt,
        List<String> providers
) {

    /**
     * @param user      변환할 User
     * @param providers 이 User 의 Provider 목록
     * @return 목록 행 DTO
     */
    public static AdminUserResponse from(User user, List<String> providers) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.getCreatedAt(), providers);
    }
}
