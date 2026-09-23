package com.ssafy.a605.backend.domain.news.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.ssafy.a605.backend.domain.news.entity.News;
import com.ssafy.a605.backend.domain.news.entity.NewsChannel;

/**
 * 피드의 기사 한 건. 본문까지 담는다 — 기사가 헤드라인 + 본문 몇 문장이 전부라 상세 엔드포인트를
 * 두지 않는다 (nws.md).
 *
 * @param publishedGameDate 기사가 매핑된 게임 거래일
 * @param publishedTime     표시용 발행 시각
 * @param major             중요 뉴스 (강조 점)
 * @param instrumentIds     관련 종목 링크 태그. 비어 있으면 매크로 뉴스다
 * @param tags              분류 태그. 표시만 하며 이동하지 않는다
 * @param relevance         요청자 기준 관련도. 서버가 보유·관심 종목과 대조해 정한다. 미인증이면 MARKET
 */
public record NewsItemResponse(
        Long newsId,
        NewsChannel channel,
        String headline,
        String body,
        LocalDate publishedGameDate,
        LocalTime publishedTime,
        boolean major,
        List<Long> instrumentIds,
        List<String> tags,
        NewsRelevance relevance
) {

    /**
     * @param news          기사
     * @param instrumentIds 그 기사의 종목 링크
     * @param tags          그 기사의 분류 태그
     * @param relevance     요청자 기준 관련도
     * @return 피드용 DTO
     */
    public static NewsItemResponse from(News news, List<Long> instrumentIds, List<String> tags,
                                        NewsRelevance relevance) {
        return new NewsItemResponse(news.getId(), news.getChannel(), news.getHeadline(), news.getBody(),
                news.getPublishedGameDate(), news.getPublishedTime(), news.isMajor(),
                instrumentIds, tags, relevance);
    }
}
