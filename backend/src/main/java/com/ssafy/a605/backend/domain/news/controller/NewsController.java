package com.ssafy.a605.backend.domain.news.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.news.controller.api.NewsApi;
import com.ssafy.a605.backend.domain.news.dto.NewsFeedResponse;
import com.ssafy.a605.backend.domain.news.dto.NewsRelevanceFilter;
import com.ssafy.a605.backend.domain.news.dto.NewsTickerResponse;
import com.ssafy.a605.backend.domain.news.entity.NewsChannel;
import com.ssafy.a605.backend.domain.news.service.NewsService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 뉴스 피드와 티커. 둘 다 미인증에 열린 조회이고 (ADR-0005), 세션이 있으면 그 사람 기준의
 * 관련도를 채운다 — 그래서 주체를 선택으로 받는다 ({@code InstrumentController} 와 같은 방식).
 *
 * <p>절단 기준도 받지 않는다. 서버가 게임 시계에서 읽는다 (→ {@link NewsApi}).
 */
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class NewsController implements NewsApi {

    /** 한 번에 가져갈 수 있는 최대치. */
    private static final int MAX_PAGE_SIZE = 100;

    private final NewsService newsService;

    @Override
    @GetMapping
    public ApiResponse<NewsFeedResponse> getFeed(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) NewsChannel channel,
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) NewsRelevanceFilter relevance,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        // 범위를 벗어난 크기는 거절하지 않고 맞춘다 — 목록 조회에서 오류로 다룰 만한 일이 아니다.
        return ApiResponse.ok(newsService.getFeed(userIdOf(loginUser), channel, instrumentId, relevance,
                cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @Override
    @GetMapping("/ticker")
    public ApiResponse<NewsTickerResponse> getTicker(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(newsService.getTicker(userIdOf(loginUser)));
    }

    /** 미인증이면 {@code null}. 그때 채울 관련도가 없어 전부 MARKET 이다. */
    private static Long userIdOf(LoginUser loginUser) {
        return loginUser != null ? loginUser.getUserId() : null;
    }
}
