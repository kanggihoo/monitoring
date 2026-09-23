package com.ssafy.a605.backend.domain.market.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.market.controller.api.InstrumentApi;
import com.ssafy.a605.backend.domain.market.dto.InstrumentDetailResponse;
import com.ssafy.a605.backend.domain.market.dto.InstrumentInfoResponse;
import com.ssafy.a605.backend.domain.market.dto.InstrumentListResponse;
import com.ssafy.a605.backend.domain.market.dto.SignalCriteriaResponse;
import com.ssafy.a605.backend.domain.market.entity.InstrumentCategory;
import com.ssafy.a605.backend.domain.market.service.InstrumentQueryService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 거래 유니버스와 종목 정보 카드. 절단 기준을 파라미터로 받지 않는다 — 게임 시계가 그 단일
 * 진실이다.
 *
 * <p>시세는 {@link MarketController} 가 담당한다. 같은 경로 접두사를 쓰지만 계약 문서가
 * 다르고({@code unv.md} · {@code info.md} 대 {@code mkt.md}) 갱신 주기도 다르다.
 */
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class InstrumentController implements InstrumentApi {

    private final InstrumentQueryService instrumentQueryService;

    @Override
    @GetMapping("/instruments")
    public ApiResponse<InstrumentListResponse> getInstruments(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) InstrumentCategory category) {
        // 목록은 미인증에도 열려 있다 (ADR-0005). 그때 채울 관심 여부가 없다.
        Long userId = (loginUser != null) ? loginUser.getUserId() : null;
        return ApiResponse.ok(instrumentQueryService.list(userId, category));
    }

    @Override
    @GetMapping("/instruments/{instrumentId}")
    public ApiResponse<InstrumentDetailResponse> getInstrument(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long instrumentId) {
        return ApiResponse.ok(instrumentQueryService.detail(loginUser.getUserId(), instrumentId));
    }

    @Override
    @GetMapping("/instruments/{instrumentId}/info")
    public ApiResponse<InstrumentInfoResponse> getInstrumentInfo(@PathVariable Long instrumentId) {
        return ApiResponse.ok(instrumentQueryService.info(instrumentId));
    }

    @Override
    @GetMapping("/signal-criteria")
    public ApiResponse<SignalCriteriaResponse> getSignalCriteria() {
        return ApiResponse.ok(instrumentQueryService.signalCriteria());
    }
}
