package com.ssafy.a605.backend.domain.ranking.dto;

import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.global.response.PageResponse;

/**
 * {@code GET /api/v1/rankings} 의 {@code data}. {@code PageResponse} 의 필드에 트랙 메타를 얹은
 * 봉투다 ({@code rank.md} §GET /api/v1/rankings).
 *
 * <p>암전 중에는 {@code content} 만 비고 {@code totalElements}·{@code totalParticipants} 는 그대로다
 * — 가리는 것은 순위이고 참가자 수는 순위가 아니다.
 *
 * @param track             요청한 트랙
 * @param asOfTickSeq       순위의 기준 틱. 아직 어떤 틱도 순위가 매겨지지 않았으면 {@code null}
 * @param asOfGameDate      기준 틱의 게임 거래일. 같은 조건에서 {@code null}
 * @param blackout          암전 중인가. 참이면 {@code content} 가 빈 배열이다
 * @param finalized         폐장 스냅샷으로 확정된 순위인가
 * @param totalParticipants 순위에 든 계좌 수
 */
public record RankingPageResponse(RankingTrack track, Long asOfTickSeq, LocalDate asOfGameDate,
                                  boolean blackout, boolean finalized, int totalParticipants,
                                  List<RankingRowResponse> content, int page, int size,
                                  long totalElements, int totalPages, boolean hasNext) {

    public static RankingPageResponse of(RankingTrack track, Long asOfTickSeq, LocalDate asOfGameDate,
                                         boolean blackout, boolean finalized, int totalParticipants,
                                         PageResponse<RankingRowResponse> page) {
        return new RankingPageResponse(track, asOfTickSeq, asOfGameDate, blackout, finalized, totalParticipants,
                page.content(), page.page(), page.size(), page.totalElements(), page.totalPages(), page.hasNext());
    }
}
