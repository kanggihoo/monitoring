package com.ssafy.a605.backend.domain.ranking.dto;

/**
 * 한 틱의 순위가 교체됐다는 프로세스 내부 신호. 개인 {@code ranking.me} 와 암전 중
 * {@code ranking.top10Changed} 발행이 이것을 듣는다 ({@code rank.md} §실시간).
 *
 * @param previous 직전 틱의 순위. 첫 틱·재시작 직후에는 {@code null}
 * @param current  이 틱의 순위
 */
public record RankingRecalculatedEvent(RankingSnapshot previous, RankingSnapshot current) {
}
