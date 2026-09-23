package com.ssafy.a605.backend.domain.admin.dto;

import com.ssafy.a605.backend.domain.user.entity.Role;

import jakarta.validation.constraints.NotNull;

/**
 * @param role 대상에게 부여할 권한
 */
public record UserRoleUpdateRequest(
        @NotNull(message = "권한은 필수입니다.")
        Role role
) {
}
