package com.ssafy.a605.backend.domain.ranking.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 폐장 스냅샷 1행 = (일차, 계좌). 랭킹 확정값과 일일 복기 집계를 한 행에 담는다 -
 * 둘 다 같은 순간에 같은 키로 확정되므로 테이블을 나눌 이유가 없다.
 *
 * <p>실시간 순위는 저장하지 않는다. 매 틱 전 계좌를 순회해 재계산하며, 그 순회에서
 * 히트맵의 카테고리 합산도 함께 뽑는다. 저장하는 것은 확정된 값뿐이다 -
 * 나중에 재계산하면 그 사이의 거래와 탈퇴 때문에 달라진다.
 *
 * <p>시상은 컬럼이 아니라 조회다. 일간 1위는 dailyRank 가 1인 행, 시즌 우승은 3일차의
 * seasonRank 가 1인 행이다. 이 행 자체가 이미 확정 스냅샷이라 그 조회는 재계산이 아니라
 * 저장된 값 읽기이고, 칭호는 고정 문자열이라 저장할 데이터가 아니다.
 */
@Entity
@Table(name = "daily_settlements")
@IdClass(DailySettlement.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailySettlement {

    @Id
    @Column(name = "season_day", nullable = false)
    private Short seasonDay;

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    // 동점은 같은 순위를 주고 다음 순위를 건너뛴다. 시드가 같아 초반에는 동점이 흔한데,
    // 임의의 tie-break 를 넣으면 실력과 무관한 순서가 순위처럼 보인다.
    @Column(name = "season_rank", nullable = false)
    private Integer seasonRank;

    @Column(name = "daily_rank", nullable = false)
    private Integer dailyRank;

    @Column(name = "season_return", nullable = false, precision = 38, scale = 4)
    private BigDecimal seasonReturn;

    @Column(name = "daily_return", nullable = false, precision = 38, scale = 4)
    private BigDecimal dailyReturn;

    // 확정 시점 총 평가액. 다음 날 일간 기준값의 출발점이기도 하다.
    @Column(name = "total_valuation_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal totalValuationKrw;

    @Column(name = "execution_count", nullable = false)
    private Integer executionCount;

    // 거래 수수료만이며 환전 수수료는 제외한다.
    @Column(name = "total_fee_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal totalFeeKrw;

    @Column(name = "realized_pnl_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal realizedPnlKrw;

    // 실현 손익 상·하위 1건. 매도 체결이 없는 날에는 판정할 것이 없어 null 이다.
    // 폐장 시점의 시즌 누적 최대 낙폭(%). accounts.max_drawdown 을 그 시점에 고정한 것이다 (V38).
    // **소급이 안 되므로 여기 찍는다** - 저쪽은 매 틱 갱신되는 한 값이고 평가액 시계열이 없어,
    // 다음 일차가 시작되면 이 일차 시점의 값을 되살릴 방법이 없다.
    // 이 칼럼이 생기기 전에 확정된 행은 null 이다.
    @Column(name = "max_drawdown", precision = 9, scale = 4)
    private BigDecimal maxDrawdown;

    @Column(name = "best_execution_id")
    private Long bestExecutionId;

    @Column(name = "worst_execution_id")
    private Long worstExecutionId;

    // 자동 태깅 결과(고점 매수·저점 매도). 판정 기준이 편성값이라 저장한다 -
    // 기준이 바뀌어도 이미 발행된 과거 리포트의 태그가 소급 변경되면 안 된다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pattern_tags", nullable = false)
    private String patternTags;

    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    /**
     * 폐장 시점의 원화·달러 현금과 평가에 쓴 틱 (V42).
     *
     * <p><b>다음 일차 개장이 지우는 값이라 여기 남긴다.</b> 초기화가 현금을 시드로 덮으므로,
     * 저장해 두지 않으면 "총평가액 = 현금 + 보유 평가액" 이라는 대조식을 사후에 세울 수 없다.
     * 이 컬럼이 생기기 전에 확정된 행은 {@code null} 이다.
     */
    @Column(name = "closing_krw_cash", precision = 38, scale = 4)
    private BigDecimal closingKrwCash;

    @Column(name = "closing_usd_cash", precision = 38, scale = 4)
    private BigDecimal closingUsdCash;

    @Column(name = "closing_tick_seq")
    private Long closingTickSeq;

    /**
     * 마감 보유 스냅샷을 저장한 시각 ({@code daily_holdings}, V42).
     *
     * <p><b>"보유 0" 과 "스냅샷 실패" 를 가르는 유일한 표식이다.</b> 아무것도 들고 있지
     * 않은 계좌는 정상적으로 0 행이라, 행의 존재로는 판정할 수 없다. 이 값이 비어 있으면
     * 그 일차의 기록이 보존되지 않았다는 뜻이고, 다음 일차 초기화가 막힌다.
     */
    @Column(name = "holdings_snapshotted_at")
    private Instant holdingsSnapshottedAt;

    /**
     * @param seasonDay         확정된 일차
     * @param accountId         대상 계좌
     * @param seasonRank        시즌 누적 수익률 기준 순위
     * @param dailyRank         당일 수익률 기준 순위
     * @param seasonReturn      확정 시점 시즌 누적 수익률(%)
     * @param dailyReturn       확정 시점 당일 수익률(%)
     * @param totalValuationKrw 확정 시점 총 평가액
     * @param executionCount    당일 체결 건수
     * @param totalFeeKrw       당일 거래 수수료 합계(원화 환산)
     * @param realizedPnlKrw    당일 실현 손익 합계(원화 환산)
     * @param bestExecutionId   실현 손익 상위 1건. 없으면 null
     * @param worstExecutionId  실현 손익 하위 1건. 없으면 null
     * @param patternTags       자동 태깅 결과 JSON
     * @param finalizedAt       폐장 스냅샷 시각
     */
    public DailySettlement(Short seasonDay, Long accountId, Integer seasonRank, Integer dailyRank,
                           BigDecimal seasonReturn, BigDecimal dailyReturn, BigDecimal totalValuationKrw,
                           Integer executionCount, BigDecimal totalFeeKrw, BigDecimal realizedPnlKrw,
                           BigDecimal maxDrawdown, Long bestExecutionId, Long worstExecutionId,
                           String patternTags, Instant finalizedAt) {
        this.seasonDay = seasonDay;
        this.accountId = accountId;
        this.seasonRank = seasonRank;
        this.dailyRank = dailyRank;
        this.seasonReturn = seasonReturn;
        this.dailyReturn = dailyReturn;
        this.totalValuationKrw = totalValuationKrw;
        this.executionCount = executionCount;
        this.totalFeeKrw = totalFeeKrw;
        this.realizedPnlKrw = realizedPnlKrw;
        this.maxDrawdown = maxDrawdown;
        this.bestExecutionId = bestExecutionId;
        this.worstExecutionId = worstExecutionId;
        this.patternTags = patternTags;
        this.finalizedAt = finalizedAt;
    }

    /**
     * 마감 현금과 스냅샷 완료를 함께 기록한다 (V42).
     *
     * <p><b>보유 행 저장과 같은 트랜잭션에서만 불러야 한다.</b> 이 표식 하나가 "그 일차의
     * 기록이 보존됐다" 는 뜻이고, 행은 없는데 표식만 남으면 다음 날 초기화가 기록 없이
     * 자산을 지운다.
     *
     * @param krwCash    폐장 시점 원화 현금 (잠긴 몫 포함)
     * @param usdCash    폐장 시점 달러 현금 (잠긴 몫 포함)
     * @param tickSeq    평가에 쓴 마지막 공개 틱
     * @param snapshotAt 스냅샷을 저장한 시각
     */
    public void recordClosingSnapshot(BigDecimal krwCash, BigDecimal usdCash, Long tickSeq,
                                      Instant snapshotAt) {
        this.closingKrwCash = krwCash;
        this.closingUsdCash = usdCash;
        this.closingTickSeq = tickSeq;
        this.holdingsSnapshottedAt = snapshotAt;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Short seasonDay;
        private Long accountId;
    }
}
