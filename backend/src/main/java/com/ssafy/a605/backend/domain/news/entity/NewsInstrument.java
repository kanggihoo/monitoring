package com.ssafy.a605.backend.domain.news.entity;

import java.io.Serializable;

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
 * 기사와 관련 종목의 연결. 기사당 0~N개이며, 태그가 하나도 없는 기사는 매크로 뉴스다.
 * 피드의 우선 노출(보유 > 관심 > 일반)이 이 조인으로 결정된다.
 */
@Entity
@Table(name = "news_instruments")
@IdClass(NewsInstrument.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsInstrument {

    @Id
    @Column(name = "news_id", nullable = false)
    private Long newsId;

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    /**
     * @param newsId       기사
     * @param instrumentId 관련 종목
     */
    public NewsInstrument(Long newsId, Long instrumentId) {
        this.newsId = newsId;
        this.instrumentId = instrumentId;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long newsId;
        private Long instrumentId;
    }
}
