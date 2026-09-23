package com.ssafy.a605.backend.domain.market.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관심종목 한 건 (기획서 5.4). 계좌가 아니라 사용자에 붙는다 - 시즌과 무관한 취향이다.
 * 상장 예정 종목에도 걸 수 있다.
 */
@Entity
@Table(name = "watchlists")
@IdClass(Watchlist.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Watchlist {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @param userId       관심종목을 등록한 사용자
     * @param instrumentId 대상 종목
     */
    public Watchlist(Long userId, Long instrumentId) {
        this.userId = userId;
        this.instrumentId = instrumentId;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long userId;
        private Long instrumentId;
    }
}
