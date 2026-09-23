package com.ssafy.a605.backend.domain.account.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환전 한 건 (기획서 7.2). 체결 내역과 합치지 않는다 - 종목·수량·손익이 없어 필드가 전혀
 * 겹치지 않으며, 화면에서의 통합은 프론트가 두 목록을 시간순으로 병합해 그린다.
 */
@Entity
@Table(name = "exchanges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ExchangeDirection direction;

    // 적용 환율. 그 게임 거래일의 값이며 하루 안에서는 변하지 않는다.
    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal rate;

    // 보내는 통화 기준 금액. 받는 통화 기준으로 두면 수수료 역산과 반올림 방향이 애매해지고,
    // 전액 환전을 표현하기도 어렵다.
    @Column(name = "send_amount", nullable = false, precision = 38, scale = 4)
    private BigDecimal sendAmount;

    // 항상 0.05(%). 환전 수수료는 무료 타임 대상이 아니다.
    @Column(name = "fee_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal feeRate;

    @Column(nullable = false, precision = 38, scale = 4)
    private BigDecimal fee;

    @Column(name = "receive_amount", nullable = false, precision = 38, scale = 4)
    private BigDecimal receiveAmount;

    @Column(name = "tick_seq", nullable = false)
    private Long tickSeq;

    @Column(name = "exchanged_at", nullable = false)
    private Instant exchangedAt;

    /**
     * @param accountId     환전한 계좌
     * @param direction     환전 방향
     * @param rate          적용 환율
     * @param sendAmount    보내는 통화 기준 금액
     * @param feeRate       적용된 환전 수수료율(%)
     * @param fee           보내는 통화로 부과된 수수료
     * @param receiveAmount 받는 통화 기준 수령액
     * @param tickSeq       환전 시점의 게임 시각
     * @param exchangedAt   환전 실제 시각
     */
    public Exchange(Long accountId, ExchangeDirection direction, BigDecimal rate, BigDecimal sendAmount,
                    BigDecimal feeRate, BigDecimal fee, BigDecimal receiveAmount,
                    Long tickSeq, Instant exchangedAt) {
        this.accountId = accountId;
        this.direction = direction;
        this.rate = rate;
        this.sendAmount = sendAmount;
        this.feeRate = feeRate;
        this.fee = fee;
        this.receiveAmount = receiveAmount;
        this.tickSeq = tickSeq;
        this.exchangedAt = exchangedAt;
    }
}
