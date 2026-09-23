package com.ssafy.a605.backend.domain.ranking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.ranking.controller.api.DailyResetApi;
import com.ssafy.a605.backend.domain.ranking.dto.DailyResetStatus;
import com.ssafy.a605.backend.domain.ranking.service.DailySettlementService;
import com.ssafy.a605.backend.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/** 기존 ADMIN 필터 체인으로 보호되는 일차 초기화 점검·재실행. 문서는 {@link DailyResetApi} 가 갖는다. */
@RestController
@RequestMapping("/api/v1/admin/daily-reset")
@RequiredArgsConstructor
public class DailyResetController implements DailyResetApi {

    private final DailySettlementService settlements;

    @Override
    @GetMapping("/{seasonDay}")
    public ApiResponse<DailyResetStatus> status(@PathVariable short seasonDay) {
        return ApiResponse.ok(settlements.resetStatus(seasonDay));
    }

    @Override
    @PostMapping("/{seasonDay}")
    public ApiResponse<DailyResetStatus> rerun(@PathVariable short seasonDay) {
        return ApiResponse.ok(settlements.rerunDailyReset(seasonDay));
    }
}
