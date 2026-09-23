package com.ssafy.a605.backend.domain.market.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.market.controller.api.MarketApi;
import com.ssafy.a605.backend.domain.market.dto.InstrumentClosesResponse;
import com.ssafy.a605.backend.domain.market.dto.MarketPricesResponse;
import com.ssafy.a605.backend.domain.market.service.MarketQueryService;
import com.ssafy.a605.backend.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 시세 조회. 절단 기준을 파라미터로 받지 않는다 — 게임 시계가 그 단일 진실이다.
 */
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketController implements MarketApi {

    private final MarketQueryService marketQueryService;

    @Override
    @GetMapping("/prices")
    public ApiResponse<MarketPricesResponse> getPrices() {
        return ApiResponse.ok(marketQueryService.getPrices());
    }

    @Override
    @GetMapping("/instruments/{instrumentId}/closes")
    public ApiResponse<InstrumentClosesResponse> getCloses(
            @PathVariable Long instrumentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return ApiResponse.ok(marketQueryService.getCloses(instrumentId, from));
    }
}
