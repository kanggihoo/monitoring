package com.ssafy.a605.backend.domain.ipo.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

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
 * 청약 한 건. 1인 1청약이라 (공모, 계좌)가 곧 PK 이고, 그래서 신청 API 가 자연히 멱등이 된다.
 * 청약 창이 겹치는 서로 다른 공모에는 각각 청약할 수 있다.
 *
 * <p>추첨 결과는 전부 저장한다. 무작위라 재계산할 수 없고, 공모가나 증거금 편성값이
 * 나중에 바뀌면 과거 배정이 조용히 달라지기 때문이다.
 */
@Entity
@Table(name = "ipo_subscriptions")
@IdClass(IpoSubscription.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IpoSubscription {

    @Id
    @Column(name = "offering_id", nullable = false)
    private Long offeringId;

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    // 신청 시점의 공모 증거금을 복사한다.
    @Column(name = "deposit_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal depositAmount;

    // 추첨 전에는 null.
    @Column
    private Boolean won;

    @Column(name = "allocated_quantity")
    private Integer allocatedQuantity;

    // 배정 금액. 상장 시 포지션의 취득 총액이 되어 평단이 자동으로 공모가가 된다.
    @Column(name = "allocated_amount", precision = 20, scale = 4)
    private BigDecimal allocatedAmount;

    // 낙첨이면 증거금 전액. 추첨 확정 즉시 반환해 상장일 매매 자금으로 쓸 수 있게 한다.
    @Column(name = "refunded_amount", precision = 20, scale = 4)
    private BigDecimal refundedAmount;

    @Column(name = "subscribed_at", nullable = false)
    private Instant subscribedAt;

    /**
     * @param offeringId    청약한 공모
     * @param accountId     청약한 계좌
     * @param depositAmount 잠긴 증거금
     * @param subscribedAt  신청 실제 시각
     */
    public IpoSubscription(Long offeringId, Long accountId, BigDecimal depositAmount, Instant subscribedAt) {
        this.offeringId = offeringId;
        this.accountId = accountId;
        this.depositAmount = depositAmount;
        this.subscribedAt = subscribedAt;
    }

    /**
     * 추첨 결과를 확정한다. 낙첨은 배정이 없고 증거금 전액이 반환액이다 (기획서 8.4.4).
     *
     * <p><b>배정 수량을 저장한다.</b> {@code floor(증거금 ÷ 공모가)} 로 파생 가능해 보이지만,
     * 공모가나 청약 단위 편성값이 나중에 바뀌면 과거 배정이 조용히 다시 계산된다.
     *
     * @param drawn     당첨 여부. <b>수량으로 유추하지 않는다</b> — 증거금이 공모가에 못 미치면
     *                  당첨되고도 0 주가 되며, 그때도 낙첨과는 다른 사건이다
     * @param quantity  배정 수량. 낙첨이면 0
     * @param allocated 배정 금액 = 수량 x 공모가. 낙첨이면 0
     * @param refunded  반환액 = 증거금 - 배정 금액. 낙첨이면 증거금 전액
     * @throws IllegalStateException 이미 추첨 결과가 있는 청약이면
     */
    public void settle(boolean drawn, int quantity, BigDecimal allocated, BigDecimal refunded) {
        if (won != null) {
            throw new IllegalStateException(
                    "이미 결과가 정해진 청약입니다: offering=%d, account=%d".formatted(offeringId, accountId));
        }
        this.won = drawn;
        this.allocatedQuantity = quantity;
        this.allocatedAmount = allocated;
        this.refundedAmount = refunded;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long offeringId;
        private Long accountId;
    }
}
