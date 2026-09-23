package com.ssafy.a605.backend.domain.notification.dto;

/**
 * 개인 메시지 {@code noti.created} 의 payload ({@code noti.md} §실시간). 프론트는 {@code notification} 의
 * 문구로 토스트를 띄우고 {@code unreadCount} 로 배지를 갈아 끼운다 — 목록 재조회가 필요 없다.
 *
 * @param notification 새로 생긴 알림
 * @param unreadCount  이 알림을 포함한 읽지 않은 알림 수
 */
public record NotificationCreatedPayload(NotificationResponse notification, long unreadCount) {
}
