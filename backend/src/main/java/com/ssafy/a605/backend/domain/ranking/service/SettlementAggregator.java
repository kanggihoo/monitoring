package com.ssafy.a605.backend.domain.ranking.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 폐장 스냅샷 행의 복기 집계 컬럼을 채우는 확장점 ({@code rpt.md} §리포트는 스냅샷이다).
 *
 * <p>{@code daily_settlements} 는 랭킹 확정값과 일일 복기 집계를 한 행에 담고 집계 컬럼이
 * NOT NULL 이라, 순위를 얼리는 그 트랜잭션에서 집계도 함께 써야 한다. 집계 규칙(실현 손익 상·하위,
 * 자동 태깅)은 복기 영역의 것이므로 랭킹은 이 인터페이스로만 묻는다 — 복기 작업이 구현을 교체한다.
 */
public interface SettlementAggregator {

    /**
     * 그 일차에 순위에 든 계좌들의 당일 집계.
     *
     * @param seasonDay  확정하는 일차
     * @param accountIds 집계할 계좌. 순위 스냅샷에 든 계좌 전부다
     * @return 계좌별 집계. 빠진 계좌는 {@link Aggregate#EMPTY} 로 채워진다
     */
    Map<Long, Aggregate> aggregate(short seasonDay, List<Long> accountIds);

    /**
     * 한 계좌의 당일 집계. 컬럼 의미는 {@code DailySettlement} 의 같은 이름 필드와 같다.
     *
     * @param executionCount   당일 체결 건수
     * @param totalFeeKrw      당일 거래 수수료 합계(원화 환산)
     * @param realizedPnlKrw   당일 실현 손익 합계(원화 환산)
     * @param bestExecutionId  실현 손익 상위 1건. 없으면 null
     * @param worstExecutionId 실현 손익 하위 1건. 없으면 null
     * @param patternTags      자동 태깅 결과 JSON 배열
     */
    record Aggregate(int executionCount, BigDecimal totalFeeKrw, BigDecimal realizedPnlKrw,
                     Long bestExecutionId, Long worstExecutionId, String patternTags) {

        /** 집계가 없을 때의 기본값: 0 · NULL · 빈 배열. */
        public static final Aggregate EMPTY =
                new Aggregate(0, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "[]");
    }
}
