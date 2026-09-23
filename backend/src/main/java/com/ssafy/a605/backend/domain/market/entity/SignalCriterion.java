package com.ssafy.a605.backend.domain.market.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신호등 판정 기준 한 줄 (기획서 9.2.4). 서버 설정값으로 관리하고 게임 내 도움말에
 * 그대로 공개한다. 판정은 서버가 하며, 프론트가 판정하면 기준 변경이 배포를 요구한다.
 */
@Entity
@Table(name = "signal_criteria",
        uniqueConstraints = @UniqueConstraint(name = "uk_signal_criteria",
                columnNames = {"kind", "level"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignalCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SignalKind kind;

    @Column(name = "metric_key", nullable = false, length = 30)
    private String metricKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SignalLevel level;

    // 경계는 하한 포함, 상한 미포함이다. null 은 그쪽 경계가 없다는 뜻이며
    // 서버 판정에만 쓰고 응답에는 내리지 않는다.
    @Column(name = "lower_bound", precision = 24, scale = 6)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 24, scale = 6)
    private BigDecimal upperBound;

    // 숫자가 아니라 문장으로 둔다. 항목마다 표현 방식이 달라 템플릿으로 만들 수 없다.
    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    /**
     * @param kind        세 가지 질문 중 하나
     * @param metricKey   판정에 쓰는 지표. 응답의 basisKeys 가 이 값이다
     * @param level       이 구간의 판정 결과
     * @param lowerBound  경계 하한(포함). 없으면 null
     * @param upperBound  경계 상한(미포함). 없으면 null
     * @param description 도움말에 그대로 노출되는 설명 문장
     * @param sortOrder   도움말에서의 표시 순서
     */
    public SignalCriterion(SignalKind kind, String metricKey, SignalLevel level, BigDecimal lowerBound,
                           BigDecimal upperBound, String description, Short sortOrder) {
        this.kind = kind;
        this.metricKey = metricKey;
        this.level = level;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    /**
     * 이 구간이 그 값을 담는가. <b>하한 포함, 상한 미포함</b>이다 — 인접한 두 구간이 경계에서
     * 겹치거나 비면 같은 값이 두 등급으로 판정되거나 어느 등급에도 속하지 않는다.
     *
     * <p>비교가 서버에만 있는 이유는 기준이 편성값이라 시즌 중 바뀔 수 있기 때문이다. 프론트가
     * 판정하면 기준 변경이 배포를 요구한다 ({@code info.md}).
     *
     * @param value 판정할 지표 값. 산출 불가({@code null})는 호출 측이 먼저 걸러낸다
     * @return 이 구간에 속하는가
     */
    public boolean contains(BigDecimal value) {
        return (lowerBound == null || value.compareTo(lowerBound) >= 0)
                && (upperBound == null || value.compareTo(upperBound) < 0);
    }
}
