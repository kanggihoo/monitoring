package com.ssafy.a605.backend.domain.market.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.market.controller.api.WatchlistApi;
import com.ssafy.a605.backend.domain.market.dto.WatchlistResponse;
import com.ssafy.a605.backend.domain.market.service.WatchlistService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 관심종목. 대상은 늘 세션의 User 이며 식별자를 경로로 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users/me/watchlist")
@RequiredArgsConstructor
public class WatchlistController implements WatchlistApi {

    private final WatchlistService watchlistService;

    @Override
    @GetMapping
    public ApiResponse<WatchlistResponse> getWatchlist(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(watchlistService.getWatchlist(loginUser.getUserId()));
    }

    @Override
    @PutMapping("/{instrumentId}")
    public ApiResponse<Void> addToWatchlist(@AuthenticationPrincipal LoginUser loginUser,
                                            @PathVariable Long instrumentId) {
        watchlistService.add(loginUser.getUserId(), instrumentId);
        return ApiResponse.ok("관심종목에 추가했습니다.");
    }

    @Override
    @DeleteMapping("/{instrumentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromWatchlist(@AuthenticationPrincipal LoginUser loginUser,
                                    @PathVariable Long instrumentId) {
        watchlistService.remove(loginUser.getUserId(), instrumentId);
    }
}
