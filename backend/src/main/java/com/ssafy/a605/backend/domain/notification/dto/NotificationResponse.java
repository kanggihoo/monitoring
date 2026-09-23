package com.ssafy.a605.backend.domain.notification.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.ssafy.a605.backend.domain.notification.entity.Notification;
import com.ssafy.a605.backend.domain.notification.entity.NotificationKind;

/**
 * 알림 목록 항목 ({@code noti.md} §GET /api/v1/notifications). 개인 메시지 {@code noti.created} 의
 * {@code notification} 도 이 모양이다.
 *
 * <p>{@code title}·{@code body} 는 발생 시점에 확정된 저장값이라 프론트는 페이로드를 해석하지 않고
 * 그대로 토스트를 그린다.
 *
 * @param notificationId 알림 식별자
 * @param kind           유형. 어느 화면으로 가는지와 {@code referenceId} 가 어느 테이블인지를 정한다
 * @param title          표기 문구
 * @param body           본문
 * @param referenceId    이동할 대상의 식별자. 고정 화면으로 가는 유형은 {@code null}
 * @param tickSeq        발생 시점의 게임 시각. 틱에 속하지 않는 사건은 {@code null}
 * @param gameDate       그 틱의 게임 거래일. {@code tickSeq} 가 {@code null} 이면 함께 {@code null}
 * @param read           읽었는가 ({@code readAt} 의 유무)
 * @param createdAt      발생 실제 시각. 정렬 기준
 */
public record NotificationResponse(Long notificationId, NotificationKind kind, String title, String body,
                                   Long referenceId, Long tickSeq, LocalDate gameDate, boolean read,
                                   Instant createdAt) {

    /**
     * @param notification 알림 행
     * @param gameDate     {@code tickSeq} 로 조인한 게임 거래일. 없으면 {@code null}
     * @return 응답 항목
     */
    public static NotificationResponse from(Notification notification, LocalDate gameDate) {
        return new NotificationResponse(notification.getId(), notification.getKind(), notification.getTitle(),
                notification.getBody(), notification.getReferenceId(), notification.getTickSeq(), gameDate,
                notification.getReadAt() != null, notification.getCreatedAt());
    }
}
