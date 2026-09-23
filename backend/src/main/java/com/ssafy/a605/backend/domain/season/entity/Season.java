package com.ssafy.a605.backend.domain.season.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시즌 하나. 지금은 3일짜리 하나뿐이지만 편성값(시드·수수료율·추첨 비율)을 코드 상수가
 * 아니라 행으로 두어, 운영자가 시즌마다 바꿀 수 있게 한다.
 */
@Entity
@Table(name = "seasons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "seed_krw", nullable = false, precision = 20, scale = 4)
    private BigDecimal seedKrw;

    // 요율은 퍼센트 값이다. 0.15 는 0.15% 를 뜻하며 0.0015 가 아니다.
    @Column(name = "trade_fee_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal tradeFeeRate;

    @Column(name = "exchange_fee_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal exchangeFeeRate;

    @Column(name = "ipo_winner_ratio", nullable = false, precision = 9, scale = 4)
    private BigDecimal ipoWinnerRatio;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @param name            시즌 표시 이름
     * @param startDate       운영 시작일
     * @param endDate         운영 종료일
     * @param seedKrw         시드 머니 (v0.7 기준 1억 원)
     * @param tradeFeeRate    거래 수수료율(%). 무료 타임에는 이 값 대신 0 이 적용된다
     * @param exchangeFeeRate 환전 수수료율(%). 무료 타임 대상이 아니다
     * @param ipoWinnerRatio  IPO 당첨 예정 인원 비율(%)
     */
    public Season(String name, LocalDate startDate, LocalDate endDate, BigDecimal seedKrw,
                  BigDecimal tradeFeeRate, BigDecimal exchangeFeeRate, BigDecimal ipoWinnerRatio) {
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.seedKrw = seedKrw;
        this.tradeFeeRate = tradeFeeRate;
        this.exchangeFeeRate = exchangeFeeRate;
        this.ipoWinnerRatio = ipoWinnerRatio;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
