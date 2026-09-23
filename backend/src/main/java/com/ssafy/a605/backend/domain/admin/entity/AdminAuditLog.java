package com.ssafy.a605.backend.domain.admin.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 권한으로 수행된 행위 하나의 불변 기록. 상태를 바꾸는 도메인 메서드가 없다 — 추가만 된다.
 *
 * <p>사용자 식별자들은 {@code users} 를 참조하지 않는다. 탈퇴가 hard delete 라 대상이 사라질 수
 * 있고, 그때도 기록은 남아야 하기 때문이다. 조회하는 쪽은 그 식별자로 사용자를 찾지 못하는 경우를
 * 정상으로 다뤄야 한다.
 */
@Entity
@Table(name = "admin_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {

    /** {@code detail} 컬럼 길이. 길이 초과는 예측 가능한 실패라 넣기 전에 잘라 애초에 나지 않게 한다. */
    private static final int DETAIL_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private AdminAuditAction action;

    @Column(name = "target_user_id", updatable = false)
    private Long targetUserId;

    @Column(updatable = false, length = DETAIL_MAX_LENGTH)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @param actorUserId  행위를 수행한 관리자의 식별자
     * @param action       행위의 종류
     * @param targetUserId 대상 User 의 식별자
     * @param detail       사람이 읽을 부연 (예: {@code "USER -> ADMIN"}). 길면 잘라 담는다
     */
    public AdminAuditLog(Long actorUserId, AdminAuditAction action, Long targetUserId, String detail) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetUserId = targetUserId;
        this.detail = truncate(detail);
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_MAX_LENGTH) {
            return detail;
        }
        return detail.substring(0, DETAIL_MAX_LENGTH);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
