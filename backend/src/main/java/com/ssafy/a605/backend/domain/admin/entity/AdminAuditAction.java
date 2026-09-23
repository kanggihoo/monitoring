package com.ssafy.a605.backend.domain.admin.entity;

/**
 * 감사 로그에 남는 행위의 종류.
 *
 * <p>샌드박스 진입은 여기에 없다. 대상이 자신에게 배정된 Test User 하나뿐이라 사칭이 구조적으로
 * 불가능하고 진입하면 권한이 오히려 줄어들어, 사후에 대조할 분쟁이 생기지 않는다 (ADR-0003 8번).
 */
public enum AdminAuditAction {
    USER_ROLE_UPDATE,
    SESSION_REVOKE
}
