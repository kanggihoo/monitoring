package com.ssafy.a605.backend.domain.social.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.social.controller.api.SocialApi;
import com.ssafy.a605.backend.domain.social.dto.HeatmapResponse;
import com.ssafy.a605.backend.domain.social.service.HeatmapService;
import com.ssafy.a605.backend.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 소셜·군중심리. 히트맵은 조회만 있다 — 집계는 종가 틱마다 자동으로 갱신되며 운영자 조작 대상이
 * 아니다 ({@code soc.md}).
 *
 * <p>세션 사용자를 인자로 받지 않는다. 히트맵은 전원에게 같은 값이고 개인화할 것이 없다.
 */
@RestController
@RequestMapping("/api/v1/social")
@RequiredArgsConstructor
public class SocialController implements SocialApi {

    private final HeatmapService heatmapService;

    @Override
    @GetMapping("/heatmap")
    public ApiResponse<HeatmapResponse> getHeatmap() {
        return ApiResponse.ok(heatmapService.heatmap());
    }
}
