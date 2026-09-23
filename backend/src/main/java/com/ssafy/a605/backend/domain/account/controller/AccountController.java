package com.ssafy.a605.backend.domain.account.controller;

import java.math.BigDecimal;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.account.controller.api.AccountApi;
import com.ssafy.a605.backend.domain.account.dto.AbsenceAckRequest;
import com.ssafy.a605.backend.domain.account.dto.AbsenceSummaryResponse;
import com.ssafy.a605.backend.domain.account.dto.AccountSummaryResponse;
import com.ssafy.a605.backend.domain.account.dto.ExchangePreviewResponse;
import com.ssafy.a605.backend.domain.account.dto.ExchangeRequest;
import com.ssafy.a605.backend.domain.account.dto.ExchangeResponse;
import com.ssafy.a605.backend.domain.account.dto.PositionsResponse;
import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.service.AbsenceSummaryService;
import com.ssafy.a605.backend.domain.account.service.AccountService;
import com.ssafy.a605.backend.domain.account.service.ExchangeService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 계좌·포트폴리오. 대상은 늘 세션의 User 이며 식별자를 경로로 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/accounts/me")
@RequiredArgsConstructor
public class AccountController implements AccountApi {

    /** 정렬은 최신순 고정이다. 같은 순간에 만들어진 두 행의 순서가 흔들리면 페이지 경계에서 행이 겹친다. */
    private static final String[] SORT_PROPERTIES = {"exchangedAt", "id"};

    /** 한 번에 가져갈 수 있는 최대치. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AccountService accountService;
    private final ExchangeService exchangeService;
    private final AbsenceSummaryService absenceSummaryService;

    @Override
    @GetMapping
    public ApiResponse<AccountSummaryResponse> getSummary(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(accountService.getSummary(loginUser.getUserId()));
    }

    @Override
    @GetMapping("/positions")
    public ApiResponse<PositionsResponse> getPositions(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(accountService.getPositions(loginUser.getUserId()));
    }

    @Override
    @GetMapping("/exchanges/preview")
    public ApiResponse<ExchangePreviewResponse> previewExchange(@AuthenticationPrincipal LoginUser loginUser,
                                                               @RequestParam ExchangeDirection direction,
                                                               @RequestParam BigDecimal amount) {
        return ApiResponse.ok(exchangeService.preview(loginUser.getUserId(), direction, amount));
    }

    @Override
    @PostMapping("/exchanges")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExchangeResponse> exchange(@AuthenticationPrincipal LoginUser loginUser,
                                                  @Valid @RequestBody ExchangeRequest request) {
        return ApiResponse.created("환전했습니다.", exchangeService.exchange(loginUser.getUserId(), request));
    }

    @Override
    @GetMapping("/exchanges")
    public ApiResponse<PageResponse<ExchangeResponse>> searchExchanges(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(exchangeService.history(loginUser.getUserId(), pageRequest(page, size)));
    }

    private static Pageable pageRequest(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, SORT_PROPERTIES));
    }

    @Override
    @GetMapping("/absence-summary")
    public ApiResponse<AbsenceSummaryResponse> absenceSummary(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(absenceSummaryService.summary(loginUser.getUserId()));
    }

    @Override
    @PostMapping("/absence-summary/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ackAbsenceSummary(@AuthenticationPrincipal LoginUser loginUser,
                                  @Valid @RequestBody AbsenceAckRequest request) {
        absenceSummaryService.ack(loginUser.getUserId(), request.ackedTickSeq());
    }
}