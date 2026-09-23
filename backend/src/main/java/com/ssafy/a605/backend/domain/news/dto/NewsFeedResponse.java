package com.ssafy.a605.backend.domain.news.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 피드 한 페이지. 커서 페이지네이션이라 전체 건수는 주지 않는다 — 새 틱마다 위쪽이 자라는
 * 목록에서 그 숫자는 계속 변하고 무한 스크롤에 쓰이지 않는다 (nws.md).
 *
 * @param asOfGameDate 이 응답이 어느 게임 거래일까지 포함하는지 (절단 기준)
 * @param items        정렬된 기사. 최신 거래일 먼저, 같은 날은 식별자 큰 것 먼저
 * @param nextCursor   다음 페이지 커서. 더 없으면 null
 * @param hasNext      다음 페이지가 있는지
 */
public record NewsFeedResponse(
        LocalDate asOfGameDate,
        List<NewsItemResponse> items,
        String nextCursor,
        boolean hasNext
) {
}
