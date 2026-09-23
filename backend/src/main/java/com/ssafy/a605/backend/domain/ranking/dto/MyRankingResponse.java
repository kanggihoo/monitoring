package com.ssafy.a605.backend.domain.ranking.dto;

import java.math.BigDecimal;

import com.ssafy.a605.backend.global.response.Amounts;

/**
 * {@code GET /api/v1/rankings/me} 의 {@code data}. 두 트랙을 한 번에 준다 — 헤더가 둘을 함께
 * 표시하고, 나누면 매 틱 두 번 호출된다 ({@code rank.md} §GET /api/v1/rankings/me).
 *
 * <p><b>암전 중에는 순위·등락이 {@code null} 이고 수익률은 그대로다.</b> 수익률은 계좌 요약에서
 * 이미 볼 수 있어 가릴 이유가 없고, 가려지는 것은 타인과의 비교인 순위다 (기획서 11.3.2).
 *
 * <p>아직 어떤 틱도 순위가 매겨지지 않았거나 이 계좌가 그 틱에 평가되지 않았으면 순위·등락·
 * 수익률·총평가액이 전부 {@code null} 이다 — 없는 값을 0 으로 꾸미면 "0등"이나 "0%"로 읽힌다.
 *
 * <p><b>총평가액도 암전에 가리지 않는다.</b> 수익률과 같은 이유다 — 내 계좌 요약에서 이미 보이는
 * 값이고, 가려지는 것은 타인과의 비교인 순위다.
 *
 * @param asOfTickSeq       순위의 기준 틱. 순위가 없으면 {@code null}
 * @param seasonRank        시즌 순위
 * @param seasonRankDelta   직전 틱 대비 시즌 순위 등락
 * @param seasonReturnRate  시즌 누적 수익률(%)
 * @param dailyRank            일간 순위
 * @param dailyRankDelta       직전 틱 대비 일간 순위 등락
 * @param dailyReturnRate      당일 수익률(%)
 * @param valuationRank        평가액 순위. 암전이면 다른 두 순위와 함께 가려진다
 * @param valuationRankDelta   직전 틱 대비 평가액 순위 등락
 * @param totalValuationKrw    그 틱의 원화 총평가액. 계좌 요약의 {@code totalValuationKrw} 와 같은 값이다
 * @param totalParticipants    순위에 든 계좌 수
 * @param blackout             암전 중인가
 */
public record MyRankingResponse(Long asOfTickSeq,
                                Integer seasonRank, Integer seasonRankDelta, BigDecimal seasonReturnRate,
                                Integer dailyRank, Integer dailyRankDelta, BigDecimal dailyReturnRate,
                                Integer valuationRank, Integer valuationRankDelta,
                                String totalValuationKrw, int totalParticipants, boolean blackout) {

    /**
     * 순위에 든 한 계좌의 응답. REST 와 개인 메시지 {@code ranking.me} 가 같은 규칙으로 만든다 —
     * 암전이면 순위·등락만 가린다.
     *
     * @param snapshot 그 계좌가 든 순위
     * @param me       그 계좌의 행
     * @param blackout 암전 중인가
     * @return 내 순위
     */
    public static MyRankingResponse of(RankingSnapshot snapshot, RankingSnapshot.Entry me, boolean blackout) {
        return new MyRankingResponse(snapshot.tickSeq(),
                blackout ? null : me.seasonRank(), blackout ? null : me.seasonRankDelta(),
                Amounts.ratio(me.seasonReturn()),
                blackout ? null : me.dailyRank(), blackout ? null : me.dailyRankDelta(),
                Amounts.ratio(me.dailyReturn()),
                blackout ? null : me.valuationRank(), blackout ? null : me.valuationRankDelta(),
                Amounts.of(me.totalValuationKrw(), Amounts.KRW_SCALE),
                snapshot.totalParticipants(), blackout);
    }
}
