package com.ssafy.a605.backend.domain.market.dto;

import java.util.List;

import com.ssafy.a605.backend.domain.market.entity.Watchlist;

/**
 * 관심종목 (기획서 5.4, {@code unv.md}).
 *
 * <p><b>종목 상세를 담지 않는다.</b> 관심 여부는 종목 목록·상세 응답에 이미 들어가고, 관심종목만
 * 모아 보는 뷰는 프론트가 이 배열로 목록을 필터한다. 상세를 함께 담으면 같은 데이터를 두 번
 * 받는다.
 *
 * <p><b>비어 있으면 빈 배열이다.</b> 없음을 오류나 빈 값으로 표현하지 않는다 — 처음 들어온
 * 사용자가 오류를 보지 않아야 한다.
 *
 * @param instrumentIds 관심종목의 식별자. 종목 식별자 순이다
 */
public record WatchlistResponse(List<Long> instrumentIds) {

    /**
     * @param watchlist 한 User 의 관심종목 행
     * @return 식별자 배열 하나인 응답
     */
    public static WatchlistResponse from(List<Watchlist> watchlist) {
        return new WatchlistResponse(watchlist.stream().map(Watchlist::getInstrumentId).toList());
    }
}
