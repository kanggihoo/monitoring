package com.ssafy.a605.backend.domain.news.dto;

import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.news.entity.News;

/**
 * 화면 상단의 롤링 헤드라인. 본문과 태그가 없다 — 티커는 헤드라인만 굴리고, 누르면 피드의
 * 해당 기사로 이동한다. 프론트는 newsId 로 앵커를 잡는다 (nws.md).
 *
 * @param asOfGameDate 절단 기준 게임 거래일
 * @param items        최신 몇 건
 */
public record NewsTickerResponse(LocalDate asOfGameDate, List<Item> items) {

    /**
     * @param major             중요 뉴스 (티커 강조 후보)
     * @param relevance         요청자 기준 관련도. 티커 강조의 근거다 (10.2.2)
     * @param publishedGameDate 기사의 게임 거래일. 띠가 헤드라인 앞에 날짜를 찍는다 — 실시간으로 온
     *                          기사는 피드 DTO 그대로라 날짜가 있는데 접속 직후 띠를 채우는 이 응답에만
     *                          없으면, 기사 있는 틱이 한 번 지나가기 전까지 날짜 없는 띠가 보인다
     */
    public record Item(Long newsId, String headline, boolean major, NewsRelevance relevance,
                       LocalDate publishedGameDate) {

        /**
         * @param news      기사
         * @param relevance 요청자 기준 관련도
         * @return 티커용 DTO
         */
        public static Item from(News news, NewsRelevance relevance) {
            return new Item(news.getId(), news.getHeadline(), news.isMajor(), relevance, news.getPublishedGameDate());
        }
    }
}
