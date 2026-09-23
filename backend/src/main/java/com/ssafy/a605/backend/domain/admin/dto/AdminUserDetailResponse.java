package com.ssafy.a605.backend.domain.admin.dto;

import java.time.Instant;
import java.util.List;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;

/**
 * 관리자 콘솔 사용자 상세.
 *
 * <p>"Test User 여부" 필드는 없다. Test User 는 목록에서 제외되어 상세로 들어갈 경로가 없고,
 * 필요하다면 로그인 방법이 하나도 없다는 사실로 드러난다.
 *
 * @param activeSessionCount 지금 살아 있는 세션의 수. 세션을 끊기 전 영향 범위를 보는 값이라
 *                           개수만 담고 목록은 담지 않는다 (세션 저장소가 접속 정보를 갖고 있지 않다)
 */
public record AdminUserDetailResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        Instant createdAt,
        List<String> providers,
        int activeSessionCount
) {

    /**
     * @param user               변환할 User
     * @param providers          이 User 의 Provider 목록
     * @param activeSessionCount 살아 있는 세션 수
     * @return 상세 DTO
     */
    public static AdminUserDetailResponse from(User user, List<String> providers, int activeSessionCount) {
        return new AdminUserDetailResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.getCreatedAt(), providers, activeSessionCount);
    }
}
