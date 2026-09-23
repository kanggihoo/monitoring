package com.ssafy.a605.backend.domain.news.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.news.dto.NewsFeedResponse;
import com.ssafy.a605.backend.domain.news.dto.NewsRelevanceFilter;
import com.ssafy.a605.backend.domain.news.dto.NewsTickerResponse;
import com.ssafy.a605.backend.domain.news.entity.NewsChannel;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 뉴스 피드 (기획서 10장, docs/projects/market-replay-api/nws.md). 문서만 담당하며 구현은
 * 컨트롤러가 한다. 두 엔드포인트 모두 <b>{@code 공개}</b> 다 (ADR-0005) — 절단 기준은 요청
 * 파라미터가 아니라 재생 상태가 갖고, 세션이 있으면 그 사람 기준의 관련도를 채울 뿐이다.
 * 예외는 {@code relevance=PERSONAL} 하나로, 보유·관심은 개인 데이터라 미인증에 401 이다.
 *
 * <p><b>절단 기준을 호출자가 정하지 않는다.</b> 서버가 게임 시계의 공개 기준 거래일로 미래
 * 기사를 잘라낸다 ({@code ReplayService.clock()}). 기준을 파라미터로 받던 임시 계약이 있었는데
 * (S15P21A605-50, 시계가 없던 시절), 그것은 공개 경로에서 {@code ?asOfGameDate=2026-08-31} 한
 * 줄로 시즌 끝까지의 기사를 꺼낼 수 있다는 뜻이었다. 뉴스는 시세의 선행 신호라 미래 기사를
 * 읽으면 게임이 성립하지 않는다 (기획서 4.5, S15P21A605-131). 응답 모양은 그대로다.
 */
@Tag(name = "News", description = "뉴스 피드 API")
public interface NewsApi {

    /** 페이지 크기 기본값. 이 인터페이스가 Swagger 문서의 단일 진실이라 구현과 갈리면 안 된다. */
    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "뉴스 피드",
            description = "공개 기준 게임 거래일 이하의 기사를 최신순으로 돌려준다 (같은 날은 식별자 큰 것 먼저). "
                    + "기준은 서버의 게임 시계이며 호출자가 정할 수 없다 — 응답의 asOfGameDate 가 그 기준이다. "
                    + "최초 공개 전에는 items 가 빈 배열이고 asOfGameDate 가 null 이다: 아직 아무 기사도 나오지 않은 시점이다. "
                    + "channel 을 생략하면 전체 탭이고, instrumentId 를 주면 그 종목이 링크된 기사만이다 — "
                    + "종목 상세의 뉴스 탭은 이 필터다. "
                    + "커서 페이지네이션이다: 응답의 nextCursor 를 그대로 cursor 로 보내면 다음 페이지가 온다. "
                    + "우리가 만든 모양이 아닌 커서는 400 이다. 전체 건수는 주지 않는다. "
                    + "relevance 는 요청자 기준 관련도다 — 보유 종목이 링크되면 HOLDING, 관심 종목이면 WATCHLIST, "
                    + "아니면 MARKET (기획서 10.4.2). 미인증이면 전부 MARKET 이다. "
                    + "relevance=PERSONAL 을 주면 보유·관심 기사만 나오며, 이 파라미터만 로그인이 필요하다 (미인증 401)."
    )
    ApiResponse<NewsFeedResponse> getFeed(
            @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "채널 탭. 생략하면 전체")
            @RequestParam(required = false) NewsChannel channel,
            @Parameter(description = "종목 링크 필터. 생략하면 전체")
            @RequestParam(required = false) Long instrumentId,
            @Parameter(description = "관련도 필터. PERSONAL 이면 보유·관심 기사만 (로그인 필요). 생략하면 전체")
            @RequestParam(required = false) NewsRelevanceFilter relevance,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략")
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size);

    @Operation(
            summary = "뉴스 티커",
            description = "화면 상단에 굴릴 최신 헤드라인 몇 건. 본문·태그가 없고 페이지도 없다. "
                    + "피드와 같은 절단 기준을 쓰며 최초 공개 전에는 빈 배열이다. "
                    + "누르면 피드의 해당 기사로 이동하므로 프론트는 newsId 로 앵커를 잡는다. "
                    + "publishedGameDate 는 띠가 헤드라인 앞에 찍는 날짜다. "
                    + "relevance 는 피드와 같은 규칙으로 채워지며 티커 강조의 근거다 (10.2.2)."
    )
    ApiResponse<NewsTickerResponse> getTicker(@AuthenticationPrincipal LoginUser loginUser);
}
