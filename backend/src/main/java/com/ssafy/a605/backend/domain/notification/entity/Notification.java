package com.ssafy.a605.backend.domain.notification.entity;

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
 * 개인 알림 1건 (기획서 15장).
 *
 * <p>사용자가 아니라 계좌에 매단다. 체결·당첨·수상이 전부 게임 계좌의 사건이고 시즌에
 * 종속되므로, 시즌이 끝나면 계좌와 함께 정리되는 것이 자연스럽다.
 *
 * <p>문구를 조회 시점에 조립하지 않고 발생 시점에 확정해 저장한다. 알림 문구에는 그 시점의
 * 수수료율이나 순위처럼 나중에 재현할 수 없는 값이 들어가기 때문이다 - 체결의
 * appliedFeeRate 를 저장하는 것과 같은 이유다.
 *
 * <p>읽지 않은 알림 수(15.1.4)는 readAt 이 null 인 행을 세면 나오고, 미접속 중 발생한
 * 알림(15.2.3)은 이 테이블에 그대로 쌓여 있으므로 다음 접속에 그냥 조회된다.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationKind kind;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    // 이동할 대상의 식별자 (15.1.3). 어느 테이블인지는 kind 가 정한다.
    // 대상 테이블이 유형마다 달라 FK 를 걸 수 없고, 고정 화면으로 가는 유형은 null 이다.
    @Column(name = "reference_id")
    private Long referenceId;

    // 발생 시점의 게임 시각. 폐장 자동 취소는 어떤 틱에도 속하지 않으므로 null 이다
    // - ord.md 의 order.expired 가 tickSeq 를 null 로 두는 것과 같은 판단이다.
    @Column(name = "tick_seq")
    private Long tickSeq;

    // 읽음 처리 시각. null 이면 안 읽음.
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @param accountId   받는 계좌
     * @param kind        알림 유형
     * @param title       발생 시점에 확정한 표기 문구
     * @param body        발생 시점에 확정한 본문
     * @param referenceId 이동할 대상의 식별자. 고정 화면으로 가는 유형이면 null
     * @param tickSeq     발생 시점의 게임 시각. 틱에 속하지 않는 사건이면 null
     */
    public Notification(Long accountId, NotificationKind kind, String title, String body,
                        Long referenceId, Long tickSeq) {
        this.accountId = accountId;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
        this.tickSeq = tickSeq;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
