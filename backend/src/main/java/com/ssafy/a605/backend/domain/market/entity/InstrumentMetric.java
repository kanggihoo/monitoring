package com.ssafy.a605.backend.domain.market.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주식 종목의 분기 재무 지표. 신호등 판정의 원값이기도 하다 (기획서 9.1).
 * 지표 종류가 열린 목록이라 metricKey 는 enum 이 아니라 문자열이다.
 */
@Entity
@Table(name = "instrument_metrics")
@IdClass(InstrumentMetric.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstrumentMetric {

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fiscal_quarter", nullable = false, length = 6)
    private String fiscalQuarter;

    @Id
    @Column(name = "metric_key", nullable = false, length = 30)
    private String metricKey;

    @Column(nullable = false, length = 30)
    private String label;

    // 공시일 = 분기 종료일 + 45일. 게임 시각이 이 날 이후여야 노출한다.
    // 이 지연이 있어야 실적 발표가 게임 안의 사건이 된다.
    @Column(name = "disclosed_game_date", nullable = false)
    private LocalDate disclosedGameDate;

    // null 이면 산출 불가이며 화면에 N/A 로 표시한다.
    @Column(precision = 24, scale = 6)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MetricUnit unit;

    /**
     * @param instrumentId      대상 종목. 주식만 행을 갖는다
     * @param fiscalQuarter     회계 분기 (2022Q1 형식)
     * @param metricKey         지표 식별자
     * @param label             화면 표기
     * @param disclosedGameDate 공시일
     * @param value             지표 값. 산출 불가면 null
     * @param unit              값의 단위
     */
    public InstrumentMetric(Long instrumentId, String fiscalQuarter, String metricKey, String label,
                            LocalDate disclosedGameDate, BigDecimal value, MetricUnit unit) {
        this.instrumentId = instrumentId;
        this.fiscalQuarter = fiscalQuarter;
        this.metricKey = metricKey;
        this.label = label;
        this.disclosedGameDate = disclosedGameDate;
        this.value = value;
        this.unit = unit;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long instrumentId;
        private String fiscalQuarter;
        private String metricKey;
    }
}
