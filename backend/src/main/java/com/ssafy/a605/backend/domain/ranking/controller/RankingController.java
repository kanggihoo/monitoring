package com.ssafy.a605.backend.domain.ranking.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.ranking.controller.api.RankingApi;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsResponse;
import com.ssafy.a605.backend.domain.ranking.dto.MyRankingResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingPageResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingTrack;
import com.ssafy.a605.backend.domain.ranking.service.AwardService;
import com.ssafy.a605.backend.domain.ranking.service.RankingService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 리더보드·내 순위. 조회만 있다 — 순위 계산·확정은 틱과 폐장이 자동으로 하며 운영자 조작 대상이
 * 아니다 ({@code rank.md} §엔드포인트).
 */
@RestController
@RequestMapping("/api/v1/rankings")
@RequiredArgsConstructor
public class RankingController implements RankingApi {

    /** 한 번에 가져갈 수 있는 최대치. */
    private static final int MAX_PAGE_SIZE = 100;

    private final RankingService rankingService;
    private final AwardService awardService;

    @Override
    @GetMapping
    public ApiResponse<RankingPageResponse> getLeaderboard(@AuthenticationPrincipal LoginUser loginUser,
                                                           @RequestParam(defaultValue = "SEASON") RankingTrack track,
                                                           @RequestParam(defaultValue = DEFAULT_PAGE) int page,
                                                           @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        // 미인증도 목록을 본다 (ADR-0005). 그때 `isMe` 로 표시할 사람이 없다.
        Long userId = (loginUser != null) ? loginUser.getUserId() : null;
        return ApiResponse.ok(rankingService.leaderboard(userId, track,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @Override
    @GetMapping("/me")
    public ApiResponse<MyRankingResponse> getMyRanking(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(rankingService.me(loginUser.getUserId()));
    }

    @Override
    @GetMapping("/awards")
    public ApiResponse<AwardsResponse> getAwards() {
        return ApiResponse.ok(awardService.awards());
    }
}
