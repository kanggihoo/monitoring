package com.ssafy.a605.backend.domain.notification.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.notification.controller.api.NotificationApi;
import com.ssafy.a605.backend.domain.notification.dto.NotificationPageResponse;
import com.ssafy.a605.backend.domain.notification.service.NotificationService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 알림 센터. 목록과 일괄 읽음 둘뿐이다 — 알림을 만드는 것은 사건이 일어난 도메인이고, 삭제·개별 읽음은
 * 쓰는 화면이 없어 두지 않는다 ({@code noti.md} §엔드포인트).
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController implements NotificationApi {

    /** 최신순 고정. 같은 시각이면 식별자 내림차순으로 순서를 고정한다. */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;

    @Override
    @GetMapping
    public ApiResponse<NotificationPageResponse> getNotifications(@AuthenticationPrincipal LoginUser loginUser,
                                                                  @RequestParam(defaultValue = DEFAULT_PAGE) int page,
                                                                  @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(notificationService.list(loginUser.getUserId(),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), LATEST_FIRST)));
    }

    @Override
    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal LoginUser loginUser) {
        notificationService.readAll(loginUser.getUserId());
    }
}
