package com.ssafy.a605.backend.domain.user.entity;

/**
 * User 의 권한. 세션의 authorities 로 담기며, 변경 시 해당 User 의 세션을 무효화한다.
 */
public enum Role {
    USER,
    ADMIN
}
