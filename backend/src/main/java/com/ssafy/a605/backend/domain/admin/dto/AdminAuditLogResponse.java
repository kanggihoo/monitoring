package com.ssafy.a605.backend.domain.admin.dto;

import java.time.Instant;

import com.ssafy.a605.backend.domain.admin.entity.AdminAuditAction;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditLog;

/**
 * 감사 로그 한 줄.
 *
 * <p>두 사용자 식별자는 참조가 아니라 그 시점의 기록이다. 그 사이 대상이 탈퇴했다면 이 값으로
 * 사용자를 조회할 수 없으며, 화면은 그 경우를 정상으로 다뤄야 한다.
 *
 * @param detail 사람이 읽을 부연 (예: {@code "USER -> ADMIN"}). 없는 행위도 있다
 */
public record AdminAuditLogResponse(
        Long id,
        Long actorUserId,
        AdminAuditAction action,
        Long targetUserId,
        String detail,
        Instant createdAt
) {

    /**
     * @param log 변환할 감사 로그
     * @return 응답용 DTO
     */
    public static AdminAuditLogResponse from(AdminAuditLog log) {
        return new AdminAuditLogResponse(log.getId(), log.getActorUserId(), log.getAction(),
                log.getTargetUserId(), log.getDetail(), log.getCreatedAt());
    }
}
