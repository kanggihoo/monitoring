package com.ssafy.a605.backend.domain.news.dto;

/**
 * 요청자 기준의 기사 관련도. 피드 정렬과 강조의 근거다 (nws.md).
 *
 * <p>서버가 요청자의 보유·관심 종목과 기사의 링크 종목을 대조해 정한다. 미인증 요청과 실시간
 * 브로드캐스트에서는 전부 {@link #MARKET} 이다 — 전자는 대조할 목록이 없고, 후자는 전원에게 같은
 * 페이로드를 보내기 때문이다.
 *
 * <p><b>선언 순서가 우선 노출 순서다</b> (기획서 10.4.2: 보유 &gt; 관심 &gt; 일반). 피드 정렬의 첫
 * 키와 커서의 첫 값이 {@link #priority()} 이고, SQL 의 {@code CASE} 가 같은 숫자를 낸다.
 */
public enum NewsRelevance {
    HOLDING,
    WATCHLIST,
    MARKET;

    /** @return 정렬 우선순위. 작을수록 먼저다 — 보유 0, 관심 1, 일반 2 */
    public int priority() {
        return ordinal();
    }
}
