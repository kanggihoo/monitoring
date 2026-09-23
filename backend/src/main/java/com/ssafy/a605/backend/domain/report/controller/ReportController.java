package com.ssafy.a605.backend.domain.report.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.report.controller.api.ReportApi;
import com.ssafy.a605.backend.domain.report.dto.DailyReportListResponse;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse;
import com.ssafy.a605.backend.domain.report.dto.SeasonReportResponse;
import com.ssafy.a605.backend.domain.report.dto.WhatIfResponse;
import com.ssafy.a605.backend.domain.report.service.DailyReportService;
import com.ssafy.a605.backend.domain.report.service.SeasonReportService;
import com.ssafy.a605.backend.domain.report.service.WhatIfService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 복기·리포트. 조회만 있다 — 리포트는 폐장 스냅샷과 함께 자동 생성되며 운영자 조작 대상이
 * 아니다 ({@code rpt.md} §엔드포인트).
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController implements ReportApi {

    private final DailyReportService dailyReportService;
    private final SeasonReportService seasonReportService;
    private final WhatIfService whatIfService;

    @Override
    @GetMapping("/daily")
    public ApiResponse<DailyReportListResponse> getDailyReports(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(dailyReportService.list(loginUser.getUserId()));
    }

    @Override
    @GetMapping("/daily/{seasonDay}")
    public ApiResponse<DailyReportResponse> getDailyReport(
            @AuthenticationPrincipal LoginUser loginUser, @PathVariable short seasonDay) {
        return ApiResponse.ok(dailyReportService.detail(loginUser.getUserId(), seasonDay));
    }

    @Override
    @GetMapping("/season")
    public ApiResponse<SeasonReportResponse> getSeasonReport(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(seasonReportService.detail(loginUser.getUserId()));
    }

    @Override
    @GetMapping("/season/what-if")
    public ApiResponse<WhatIfResponse> getWhatIf(@AuthenticationPrincipal LoginUser loginUser,
                                                 @RequestParam Long instrumentId) {
        return ApiResponse.ok(whatIfService.simulate(loginUser.getUserId(), instrumentId));
    }
}
