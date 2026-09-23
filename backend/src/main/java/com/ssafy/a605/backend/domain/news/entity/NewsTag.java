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
 * 기사의 분류 태그 하나. 지수 전반·금리·환율처럼 표시만 하는 태그다 (기사당 0~N개).
 * 종목 상세로 이동하고 우선 노출 판정에 쓰이는 링크 태그는 {@link NewsInstrument} 다.
 */
@Entity
@Table(name = "news_tags")
@IdClass(NewsTag.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsTag {

    @Id
    @Column(name = "news_id", nullable = false)
    private Long newsId;

    @Id
    @Column(nullable = false, length = 50)
    private String tag;

    /**
     * @param newsId 기사
     * @param tag    태그 표시 문자열
     */
    public NewsTag(Long newsId, String tag) {
        this.newsId = newsId;
        this.tag = tag;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long newsId;
        private String tag;
    }
}
