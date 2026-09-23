package com.ssafy.a605.backend.domain.notification.dto;

import java.util.List;

import com.ssafy.a605.backend.global.response.PageResponse;

/**
 * {@code GET /api/v1/notifications} 의 {@code data}. 페이지 봉투에 {@code unreadCount} 를 함께 싣는다 —
 * 미읽음 수 전용 엔드포인트를 두면 헤더 배지가 폴링 대상이 된다 ({@code noti.md} §엔드포인트).
 *
 * @param unreadCount   읽지 않은 알림 수. 페이지와 무관하게 내 알림 전체 기준이다
 * @param content       이 페이지의 알림. 최신순
 * @param page          페이지 번호
 * @param size          페이지 크기
 * @param totalElements 전체 알림 수
 * @param totalPages    전체 페이지 수
 * @param hasNext       다음 페이지가 있는가
 */
public record NotificationPageResponse(long unreadCount, List<NotificationResponse> content, int page, int size,
                                       long totalElements, int totalPages, boolean hasNext) {

    public static NotificationPageResponse of(long unreadCount, PageResponse<NotificationResponse> page) {
        return new NotificationPageResponse(unreadCount, page.content(), page.page(), page.size(),
                page.totalElements(), page.totalPages(), page.hasNext());
    }
}
