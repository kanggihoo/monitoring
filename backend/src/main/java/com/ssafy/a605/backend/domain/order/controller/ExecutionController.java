package com.ssafy.a605.backend.domain.order.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.order.controller.api.ExecutionApi;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.service.ExecutionService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 체결 내역. 대상은 늘 세션의 User 이며 계좌 식별자를 받지 않는다.
 *
 * <p>주문과 다른 경로에 둔 것은 {@code ord.md} §리소스 경계의 결정이다 — 미체결 예약과 체결은
 * 생명주기가 반대고 필드가 겹치지 않는다.
 */
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController implements ExecutionApi {

    /** 정렬은 최신순 고정이다. 같은 순간에 만들어진 두 행의 순서가 흔들리면 페이지 경계에서 행이 겹친다. */
    private static final String[] SORT_PROPERTIES = {"executedAt", "id"};

    /** 한 번에 가져갈 수 있는 최대치. */
    private static final int MAX_PAGE_SIZE = 100;

    private final ExecutionService executionService;

    @Override
    @GetMapping
    public ApiResponse<PageResponse<ExecutionResponse>> searchExecutions(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) Short seasonDay,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(executionService.search(loginUser.getUserId(), instrumentId, seasonDay,
                pageRequest(page, size)));
    }

    private static Pageable pageRequest(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, SORT_PROPERTIES));
    }
}
