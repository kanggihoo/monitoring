package com.ssafy.a605.backend.domain.market.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

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
 * 액면분할·역분할 이력. 런타임에 참조하지 않는다 - daily_closes 는 이미 조정된 값이다.
 * 적재 전처리의 입력이자 그 계산의 감사 근거로 남긴다.
 */
@Entity
@Table(name = "instrument_splits")
@IdClass(InstrumentSplit.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstrumentSplit {

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    // 분할 후 첫 거래일. 그날 종가는 이미 분할된 값이다.
    @Id
    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    // 1주가 몇 주가 되는가. 4:1 분할은 4, 1:5 역분할은 0.2 다.
    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal ratio;

    /**
     * @param instrumentId 대상 종목
     * @param exDate       분할 후 첫 거래일
     * @param ratio        1주가 몇 주가 되는지의 비율
     */
    public InstrumentSplit(Long instrumentId, LocalDate exDate, BigDecimal ratio) {
        this.instrumentId = instrumentId;
        this.exDate = exDate;
        this.ratio = ratio;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long instrumentId;
        private LocalDate exDate;
    }
}
