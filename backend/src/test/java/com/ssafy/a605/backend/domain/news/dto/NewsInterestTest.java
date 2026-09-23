package com.ssafy.a605.backend.domain.news.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관련도 판정 규칙만 본다. DB 도 시계도 없다. */
class NewsInterestTest {

    private final NewsInterest interest = new NewsInterest(Set.of(13L), Set.of(1L, 13L));

    @Test
    @DisplayName("보유 종목이 링크되면_관심이기도 해도_HOLDING")
    void held_winsOverWatched() {
        assertThat(interest.relevanceOf(List.of(1L, 13L))).isEqualTo(NewsRelevance.HOLDING);
        assertThat(interest.relevanceOf(List.of(13L))).isEqualTo(NewsRelevance.HOLDING);
    }

    @Test
    @DisplayName("관심 종목만 링크되면_WATCHLIST")
    void watchedOnly_isWatchlist() {
        assertThat(interest.relevanceOf(List.of(1L, 2L))).isEqualTo(NewsRelevance.WATCHLIST);
    }

    @Test
    @DisplayName("둘 다 아니거나 매크로 기사면_MARKET")
    void neither_isMarket() {
        assertThat(interest.relevanceOf(List.of(2L))).isEqualTo(NewsRelevance.MARKET);
        assertThat(interest.relevanceOf(List.of())).isEqualTo(NewsRelevance.MARKET);
    }

    @Test
    @DisplayName("미인증_전부 MARKET 이고 개인 필터 기준이 비어 있다")
    void none_isAllMarket() {
        assertThat(NewsInterest.NONE.relevanceOf(List.of(1L, 13L))).isEqualTo(NewsRelevance.MARKET);
        assertThat(NewsInterest.NONE.isEmpty()).isTrue();
        assertThat(NewsInterest.NONE.personal()).isEmpty();
    }

    @Test
    @DisplayName("개인 필터 기준_보유와 관심의 합집합")
    void personal_isUnion() {
        assertThat(interest.personal()).containsExactlyInAnyOrder(1L, 13L);
        assertThat(interest.isEmpty()).isFalse();
    }
}
