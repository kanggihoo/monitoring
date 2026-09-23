package com.ssafy.a605.backend.domain.user.dto;

import java.util.List;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;

/**
 * @param email     표시용 이메일. IdP 가 주지 않으면 null 이다.
 * @param providers 이 User 가 쓰는 로그인 방법. 계정 연결 기능이 없어 지금은 항상 하나다.
 */
public record UserResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        List<String> providers
) {

    /**
     * @param user      변환할 User
     * @param providers 이 User 의 Provider 목록
     * @return 응답용 DTO
     */
    public static UserResponse from(User user, List<String> providers) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(), providers);
    }
}
