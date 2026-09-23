package com.ssafy.a605.backend.domain.onboarding.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.onboarding.controller.api.OnboardingApi;
import com.ssafy.a605.backend.domain.onboarding.dto.SelfProfileRequest;
import com.ssafy.a605.backend.domain.onboarding.dto.SelfProfileResponse;
import com.ssafy.a605.backend.domain.onboarding.service.SelfProfileService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 온보딩 설문. 한 자원의 조회·저장이라 경로 하나에 GET · PUT 을 둔다.
 */
@RestController
@RequestMapping("/api/v1/onboarding/self-profile")
@RequiredArgsConstructor
public class OnboardingController implements OnboardingApi {

    private final SelfProfileService selfProfileService;

    @Override
    @GetMapping
    public ApiResponse<SelfProfileResponse> getSelfProfile(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(selfProfileService.find(loginUser.getUserId()));
    }

    @Override
    @PutMapping
    public ApiResponse<SelfProfileResponse> putSelfProfile(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody SelfProfileRequest request) {
        return ApiResponse.ok(selfProfileService.save(loginUser.getUserId(), request.answers()));
    }
}
