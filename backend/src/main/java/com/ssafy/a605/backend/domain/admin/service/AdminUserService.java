package com.ssafy.a605.backend.domain.admin.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.admin.dto.AdminUserDetailResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminUserResponse;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditAction;
import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 콘솔의 사용자 조회와 두 가지 개입(권한 변경·세션 강제 종료).
 *
 * <p>User 를 직접 다루지 않고 사용자 서비스에 위임한다. 권한 변경은 이미 세션 무효화까지 하는
 * 기존 동작이 있어 그것을 그대로 쓰고, 여기서는 "누가 누구에게 했는지"만 더한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final UserService userService;
    private final AdminAuditLogService auditLogService;

    /**
     * 사용자를 검색한다. Test User 는 결과에 나오지 않는다.
     *
     * @param keyword  이메일·닉네임 부분일치 검색어 (빈 문자열이면 전체)
     * @param pageable 페이지 요청
     * @return 사용자 목록 페이지
     */
    public PageResponse<AdminUserResponse> search(String keyword, Pageable pageable) {
        Page<User> users = userService.search(keyword, pageable);
        Map<Long, List<String>> providers =
                userService.providersByUserId(users.getContent().stream().map(User::getId).toList());

        return PageResponse.from(users.map(user ->
                AdminUserResponse.from(user, providers.getOrDefault(user.getId(), List.of()))));
    }

    /**
     * @param id 대상 User 식별자
     * @return 로그인 방법·가입일·활성 세션 수를 포함한 상세
     * @throws BusinessException 그런 User 가 없거나 Test User 일 때 (404)
     */
    public AdminUserDetailResponse get(Long id) {
        User user = userService.getByIdExcludingTestUser(id);
        return AdminUserDetailResponse.from(
                user, userService.providersOf(id), userService.countActiveSessions(id));
    }

    /**
     * 권한을 바꾸고 그 사실을 기록한다. 대상의 세션은 기존 동작이 끊는다.
     *
     * @param actorId  수행하는 관리자의 식별자
     * @param targetId 대상 User 식별자
     * @param role     새 권한
     * @throws BusinessException 자기 자신의 권한을 낮추려 할 때 (409),
     *                           대상이 없거나 Test User 일 때 (404)
     */
    @Transactional
    public void changeRole(Long actorId, Long targetId, Role role) {
        // 권한을 잃으면 스크립트 없이는 되돌릴 수 없다. 관리자 화면에서 스스로 잠기는 것을 막는다.
        if (actorId.equals(targetId) && role != Role.ADMIN) {
            throw new BusinessException(HttpStatus.CONFLICT, "자기 자신의 권한은 낮출 수 없습니다.");
        }

        User target = userService.getByIdExcludingTestUser(targetId);
        auditLogService.write(actorId, AdminAuditAction.USER_ROLE_UPDATE, targetId,
                target.getRole() + " -> " + role);
        userService.changeRole(targetId, role);
    }

    /**
     * 이 User 의 세션을 전부 끊고 그 사실을 기록한다.
     *
     * <p>자기 자신도 대상이 될 수 있다. 권한 강등을 막는 이유는 되돌릴 수 없다는 비대칭인데,
     * 세션 종료는 재로그인 한 번이다.
     *
     * @param actorId  수행하는 관리자의 식별자
     * @param targetId 대상 User 식별자
     * @throws BusinessException 대상이 없거나 Test User 일 때 (404)
     */
    @Transactional
    public void revokeSessions(Long actorId, Long targetId) {
        userService.getByIdExcludingTestUser(targetId);
        auditLogService.write(actorId, AdminAuditAction.SESSION_REVOKE, targetId, null);
        userService.expireSessions(targetId);
    }
}
