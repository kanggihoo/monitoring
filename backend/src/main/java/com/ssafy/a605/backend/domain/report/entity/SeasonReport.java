package com.ssafy.a605.backend.domain.report.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시즌 리포트. 3일차 정산 발표 이후에만 열람할 수 있다.
 * 계좌가 시즌에 종속되므로 accountId 하나가 PK 다.
 *
 * <p>MDD·승률·회전율은 전부 종가 틱 시계열을 순회해야 나오고 시즌 종료 후 한 번만
 * 계산하면 되므로 저장한다.
 */
@Entity
@Table(name = "season_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonReport {

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "final_return", nullable = false, precision = 38, scale = 4)
    private BigDecimal finalReturn;

    // 최대 낙폭(%). 참가한 일차의 daily_settlements.max_drawdown 중 가장 깊은 값이다 —
    // 초기화가 accounts.max_drawdown 을 매일 0 으로 되돌리므로 그쪽은 마지막 일차만 남는다.
    @Column(name = "max_drawdown", nullable = false, precision = 9, scale = 4)
    private BigDecimal maxDrawdown;

    // 승률(%). 실현 손익이 양수인 매도 체결의 비율.
    @Column(name = "win_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal winRate;

    // 회전율. 총 거래대금을 평균 자산으로 나눈 값.
    @Column(nullable = false, precision = 9, scale = 4)
    private BigDecimal turnover;

    @Column(name = "total_fee_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal totalFeeKrw;

    @Column(name = "final_rank", nullable = false)
    private Integer finalRank;

    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    /**
     * @param accountId    대상 계좌
     * @param finalReturn  최종 수익률(%)
     * @param maxDrawdown  최대 낙폭(%)
     * @param winRate      승률(%)
     * @param turnover     회전율
     * @param totalFeeKrw  시즌 총 수수료(원화 환산)
     * @param finalRank    최종 순위
     * @param finalizedAt  3일차 폐장 스냅샷 시각
     */
    public SeasonReport(Long accountId, BigDecimal finalReturn, BigDecimal maxDrawdown, BigDecimal winRate,
                        BigDecimal turnover, BigDecimal totalFeeKrw, Integer finalRank, Instant finalizedAt) {
        this.accountId = accountId;
        this.finalReturn = finalReturn;
        this.maxDrawdown = maxDrawdown;
        this.winRate = winRate;
        this.turnover = turnover;
        this.totalFeeKrw = totalFeeKrw;
        this.finalRank = finalRank;
        this.finalizedAt = finalizedAt;
    }
}
