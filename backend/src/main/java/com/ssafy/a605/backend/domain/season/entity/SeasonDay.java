package com.ssafy.a605.backend.domain.season.entity;

import java.io.Serializable;
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
 * 일자별 재생 편성. 운영 내부 자료이며, 재생 종료 지점은 그날 정산 발표 전까지
 * 사용자에게 노출하지 않는다 (기획서 3.1.2 · 3.5.2).
 */
@Entity
@Table(name = "season_days")
@IdClass(SeasonDay.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonDay {

    @Id
    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Id
    @Column(name = "season_day", nullable = false)
    private Short seasonDay;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "from_game_month", nullable = false, length = 7)
    private String fromGameMonth;

    // 브리핑 응답에 절대 담기지 않는 값이다. 담으면 그날의 시장 국면을 아는 것과 같다.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "to_game_month", nullable = false, length = 7)
    private String toGameMonth;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    // null 이면 아직 순위가 확정되지 않았다.
    @Column(name = "settled_at")
    private Instant settledAt;

    /**
     * @param seasonId      소속 시즌
     * @param seasonDay     1 · 2 · 3
     * @param fromGameMonth 재생 시작 게임 월 (YYYY-MM). 브리핑이 공개하는 유일한 편성값
     * @param toGameMonth   재생 종료 게임 월 (YYYY-MM)
     * @param opensAt       재생·거래 시작 실제 시각
     */
    public SeasonDay(Long seasonId, Short seasonDay, String fromGameMonth, String toGameMonth,
                     Instant opensAt) {
        this.seasonId = seasonId;
        this.seasonDay = seasonDay;
        this.fromGameMonth = fromGameMonth;
        this.toGameMonth = toGameMonth;
        this.opensAt = opensAt;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long seasonId;
        private Short seasonDay;
    }
}
