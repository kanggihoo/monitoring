package com.ssafy.a605.backend.domain.admin.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.admin.controller.api.AdminApi;
import com.ssafy.a605.backend.domain.admin.dto.AdminAuditLogResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminInquiryResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminNoticeRequest;
import com.ssafy.a605.backend.domain.admin.dto.AdminUserDetailResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminUserResponse;
import com.ssafy.a605.backend.domain.admin.dto.InquiryAnswerRequest;
import com.ssafy.a605.backend.domain.admin.dto.UserRoleUpdateRequest;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditAction;
import com.ssafy.a605.backend.domain.admin.service.AdminAuditLogService;
import com.ssafy.a605.backend.domain.admin.service.AdminInquiryService;
import com.ssafy.a605.backend.domain.admin.service.AdminNoticeService;
import com.ssafy.a605.backend.domain.admin.service.AdminUserService;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 콘솔. 경로 보호는 {@code SecurityConfig} 가 이미 하고 있으므로 여기서 권한을 다시 보지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController implements AdminApi {

    /**
     * 정렬은 최신순 고정이다. 세 목록 모두 이 두 속성을 갖는다.
     *
     * <p>식별자를 뒤에 붙이는 이유는 같은 순간에 만들어진 두 행의 순서가 정해지지 않기 때문이다 —
     * 한 트랜잭션에서 남는 감사 기록이 특히 그렇고, 순서가 흔들리면 페이지 경계에서 행이 겹치거나
     * 빠진다. 두 테이블 모두 식별자가 증가하므로 생성 순서와 같은 방향이다.
     */
    private static final String[] SORT_PROPERTIES = {"createdAt", "id"};

    /** 한 번에 가져갈 수 있는 최대치. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService adminUserService;
    private final AdminAuditLogService adminAuditLogService;
    private final AdminInquiryService adminInquiryService;
    private final AdminNoticeService adminNoticeService;

    @Override
    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserResponse>> searchUsers(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(adminUserService.search(keyword, pageRequest(page, size)));
    }

    @Override
    @GetMapping("/users/{id}")
    public ApiResponse<AdminUserDetailResponse> getUser(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.get(id));
    }

    @Override
    @PatchMapping("/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeUserRole(@AuthenticationPrincipal LoginUser loginUser,
                               @PathVariable Long id,
                               @Valid @RequestBody UserRoleUpdateRequest request) {
        adminUserService.changeRole(loginUser.getUserId(), id, request.role());
    }

    @Override
    @DeleteMapping("/users/{id}/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeUserSessions(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        adminUserService.revokeSessions(loginUser.getUserId(), id);
    }

    @Override
    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<AdminAuditLogResponse>> searchAuditLogs(
            @RequestParam(required = false) AdminAuditAction action,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(adminAuditLogService.search(action, pageRequest(page, size)));
    }

    @Override
    @GetMapping("/inquiries")
    public ApiResponse<PageResponse<AdminInquiryResponse>> searchInquiries(
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(adminInquiryService.search(status, pageRequest(page, size)));
    }

    @Override
    @PutMapping("/inquiries/{id}/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answerInquiry(@AuthenticationPrincipal LoginUser loginUser,
                              @PathVariable Long id,
                              @Valid @RequestBody InquiryAnswerRequest request) {
        adminInquiryService.answer(loginUser.getUserId(), id, request.answer());
    }

    @Override
    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendNotice(@Valid @RequestBody AdminNoticeRequest request) {
        adminNoticeService.send(request);
    }

    /**
     * 정렬을 여기서 못 박는다. {@code Pageable} 을 그대로 받으면 프레임워크가 정렬 파라미터를
     * 바인딩해 호출자가 정렬을 고를 수 있게 되는데, 그것을 만들지 않기로 했다.
     *
     * <p>범위를 벗어난 값은 거절하지 않고 맞춘다 — 목록 조회에서 잘못된 페이지 번호는 오류로 다룰
     * 만한 일이 아니다.
     */
    private static Pageable pageRequest(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, SORT_PROPERTIES));
    }
}
