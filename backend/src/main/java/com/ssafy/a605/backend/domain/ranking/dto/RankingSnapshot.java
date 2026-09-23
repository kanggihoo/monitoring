package com.ssafy.a605.backend.domain.ranking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 한 틱의 순위 결과 ({@code rank.md} §매 틱 전 계좌 순회). 리더보드·내 순위·실시간 발행이 모두
 * 이 객체를 읽고, 다음 틱의 {@code rankDelta} 는 이 객체와의 비교로 나온다.
 *
 * <p><b>저장하지 않는다.</b> 실시간 순위는 매 틱 전체 재계산되는 파생값이고, 저장하는 것은
 * 폐장에 확정된 {@code daily_settlements} 뿐이다. 서버가 재시작되면 다음 틱에 다시 만들어지며
 * 그 틱의 등락은 0 이다 — 비교할 직전 값이 없다.
 *
 * @param tickSeq           이 순위의 기준 틱
 * @param gameDate          그 틱의 게임 거래일
 * @param seasonDay         그 틱의 일차
 * @param totalParticipants 순위에 든 계좌 수. 탈퇴자는 계좌와 함께 사라지므로 여기 없다
 * @param bySeasonRank      시즌 순위 오름차순. 같은 순위 안에서는 계좌 식별자 순
 * @param byDailyRank       일간 순위 오름차순. 같은 순위 안에서는 계좌 식별자 순
 * @param byValuationRank   평가액 순위 오름차순. 같은 순위 안에서는 계좌 식별자 순
 * @param byAccountId       계좌 식별자로 자기 행을 찾는 색인 ({@code /rankings/me}, {@code isMe})
 * @param finalized         이 순위가 폐장 스냅샷으로 {@code daily_settlements} 에 얼려졌는가.
 *                          폐장 확정 뒤 참이 되고 다음 날 브리핑 진입이나 새 틱이 거짓으로 돌린다
 */
public record RankingSnapshot(long tickSeq, LocalDate gameDate, short seasonDay, int totalParticipants,
                              List<Entry> bySeasonRank, List<Entry> byDailyRank, List<Entry> byValuationRank,
                              Map<Long, Entry> byAccountId, boolean finalized) {

    /**
     * @param finalized 새 확정 여부
     * @return 확정 여부만 바뀐 같은 순위
     */
    public RankingSnapshot withFinalized(boolean finalized) {
        return new RankingSnapshot(tickSeq, gameDate, seasonDay, totalParticipants,
                bySeasonRank, byDailyRank, byValuationRank, byAccountId, finalized);
    }

    /**
     * 한 계좌의 두 트랙 순위. 리더보드 행이 공개하는 것은 순위·등락·수익률까지다 — 무엇을 들고
     * 있는지는 여기 없고, 그것이 기획서 11.6.2 의 경계다.
     *
     * @param accountId         계좌
     * @param userId            계좌 주인. 닉네임·아바타는 응답을 만들 때 조인한다
     * @param seasonRank        시즌 누적 수익률 순위. 1 부터, 동점은 같은 순위
     * @param seasonRankDelta   직전 틱 대비 시즌 순위 등락. 양수가 상승. 첫 틱·신규 참가자는 0
     * @param seasonReturn      시즌 누적 수익률(%). 계좌 요약의 {@code cumulativeReturn} 그 값이다
     * @param dailyRank          당일 수익률 순위
     * @param dailyRankDelta     직전 틱 대비 일간 순위 등락
     * @param dailyReturn        당일 수익률(%). 계좌 요약의 {@code dailyReturn} 그 값이다
     * @param valuationRank      총평가액 순위. 금액 내림차순이며 동점 규칙은 두 트랙과 같다
     * @param valuationRankDelta 직전 틱 대비 평가액 순위 등락
     * @param totalValuationKrw  이 틱의 총평가액. 폐장 스냅샷이 그대로 얼린다
     */
    public record Entry(long accountId, long userId,
                        int seasonRank, int seasonRankDelta, BigDecimal seasonReturn,
                        int dailyRank, int dailyRankDelta, BigDecimal dailyReturn,
                        int valuationRank, int valuationRankDelta,
                        BigDecimal totalValuationKrw) {
    }
}
