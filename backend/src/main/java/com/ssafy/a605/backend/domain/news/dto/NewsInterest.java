package com.ssafy.a605.backend.domain.news.dto;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 요청자가 관심을 둔 종목 — 보유와 관심 두 집합. 기사의 관련도(보유 &gt; 관심 &gt; 일반)를
 * 이것과 대조해 정한다 (기획서 10.4.2, {@code nws.md} §우선 노출).
 *
 * <p>미인증이면 {@link #NONE} 이고 모든 기사가 {@code MARKET} 이다. 로그인했지만 시즌 계좌가
 * 없으면 보유만 빈 집합이다 — 공개 경로라 404 를 내지 않는다.
 *
 * <p>피드 질의({@code NewsFeedQueryRepository})가 같은 두 집합으로 우선순위를 매기므로 정렬과
 * 라벨의 출처가 하나다.
 *
 * @param held    수량이 남아 있는 보유 종목
 * @param watched 관심 종목
 */
public record NewsInterest(Set<Long> held, Set<Long> watched) {

    public static final NewsInterest NONE = new NewsInterest(Set.of(), Set.of());

    /**
     * 기사 한 건의 관련도. 링크된 종목 중 하나라도 보유면 {@code HOLDING}, 아니고 하나라도
     * 관심이면 {@code WATCHLIST}, 아니면 {@code MARKET}. 매크로 기사(링크 없음)는 항상 {@code MARKET}.
     *
     * @param instrumentIds 그 기사에 링크된 종목
     * @return 관련도
     */
    public NewsRelevance relevanceOf(Collection<Long> instrumentIds) {
        if (instrumentIds.stream().anyMatch(held::contains)) {
            return NewsRelevance.HOLDING;
        }
        if (instrumentIds.stream().anyMatch(watched::contains)) {
            return NewsRelevance.WATCHLIST;
        }
        return NewsRelevance.MARKET;
    }

    /** @return 보유와 관심의 합집합. {@code relevance=PERSONAL} 필터가 거르는 기준이다 */
    public Set<Long> personal() {
        Set<Long> union = new HashSet<>(held);
        union.addAll(watched);
        return union;
    }

    /** @return 보유도 관심도 없는가. 그러면 관련도 계산과 종목 링크 조회를 건너뛸 수 있다 */
    public boolean isEmpty() {
        return held.isEmpty() && watched.isEmpty();
    }
}
