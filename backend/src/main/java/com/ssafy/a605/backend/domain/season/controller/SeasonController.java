package com.ssafy.a605.backend.domain.season.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.season.controller.api.SeasonApi;
import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse;
import com.ssafy.a605.backend.domain.season.dto.SeasonBriefingResponse;
import com.ssafy.a605.backend.domain.season.service.SeasonQueryService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 시즌 안내와 개장 브리핑. 시즌 식별자를 경로로 받지 않는다 — 시즌이 하나뿐이라 값이 항상
 * 하나인 경로 변수가 된다.
 *
 * <p><b>시즌 안내만 미인증에 열려 있다.</b> 인증 주체가 없으면 {@code loginUser} 가
 * {@code null} 이고 그때 참가 상태도 {@code null} 이다.
 */
@RestController
@RequestMapping("/api/v1/seasons")
@RequiredArgsConstructor
public class SeasonController implements SeasonApi {

    private final SeasonQueryService seasonQueryService;

    @Override
    @GetMapping("/current")
    public ApiResponse<CurrentSeasonResponse> getCurrentSeason(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(seasonQueryService.current(
                loginUser == null ? null : loginUser.getUserId()));
    }

    @Override
    @GetMapping("/current/briefing")
    public ApiResponse<SeasonBriefingResponse> getBriefing() {
        return ApiResponse.ok(seasonQueryService.briefing());
    }
}
