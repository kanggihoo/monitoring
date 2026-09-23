package com.ssafy.a605.backend.domain.ranking.entity;

import java.io.Serializable;
import java.math.BigDecimal;

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
 * 일차 폐장 시점의 보유 종목 한 줄 = (일차, 계좌, 종목) (V42).
 *
 * <p><b>{@link com.ssafy.a605.backend.domain.account.entity.Position} 의 사본이 아니라
 * 확정 기록이다.</b> 다음 일차 개장에 {@code positions} 가 통째로 초기화되므로, 폐장에
 * 찍어 두지 않으면 그 일차에 무엇을 들고 끝냈는지가 영구히 사라진다. 매도 체결을 만들지
 * 않는 초기화라 {@code executions} 로 되짚을 수도 없다.
 *
 * <p><b>평가액을 함께 얼린다.</b> 나중에 다시 계산하려면 그 일차 마지막 틱의 종가와 환율이
 * 필요한데, 그것을 매번 되짚는 것보다 확정값을 그대로 두는 편이 싸고 흔들리지 않는다 —
 * {@link DailySettlement} 이 순위를 다루는 방식과 같다.
 *
 * <p>수량 0 인 포지션은 담지 않는다. 평가액에 아무것도 더하지 않고 DB CHECK 가 막는다.
 */
@Entity
@Table(name = "daily_holdings")
@IdClass(DailyHolding.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyHolding {

    @Id
    @Column(name = "season_day", nullable = false)
    private Short seasonDay;

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(nullable = false)
    private Integer quantity;

    // 취득 총액은 종목의 결제 통화 기준이다 (positions.total_cost 를 그대로 옮긴다).
    // 원화로 환산하지 않는 이유는 평단이 그 통화로 표시되기 때문이다.
    @Column(name = "total_cost", nullable = false, precision = 38, scale = 4)
    private BigDecimal totalCost;

    @Column(name = "valuation_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal valuationKrw;

    /**
     * @param seasonDay    폐장한 일차
     * @param accountId    보유 계좌
     * @param instrumentId 보유 종목
     * @param quantity     마감 보유 수량. 1 이상이다
     * @param totalCost    마감 취득 총액 (결제 통화)
     * @param valuationKrw 그 일차 마지막 공개 틱 기준 원화 평가액
     */
    public DailyHolding(Short seasonDay, Long accountId, Long instrumentId, Integer quantity,
                        BigDecimal totalCost, BigDecimal valuationKrw) {
        this.seasonDay = seasonDay;
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.totalCost = totalCost;
        this.valuationKrw = valuationKrw;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Short seasonDay;
        private Long accountId;
        private Long instrumentId;
    }
}
