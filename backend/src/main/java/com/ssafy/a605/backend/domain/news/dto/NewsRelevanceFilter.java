package com.ssafy.a605.backend.domain.news.dto;

/**
 * 피드의 관련도 필터 ({@code nws.md}). 화면의 "보유·관심" 칩이 보내는 값이다.
 *
 * <p>값이 하나뿐인 enum 인 이유는 <b>관련도 자체({@link NewsRelevance})와 필터를 섞지 않기
 * 위해서</b>다. {@code relevance=HOLDING} 처럼 개별 등급으로 거르는 화면 요구가 없고, 그것을
 * 열면 "관심만" 이라는 세 번째 페이지네이션이 생긴다. 필요해지면 값을 더한다.
 *
 * <p><b>개인 데이터다.</b> 보유·관심은 세션 사용자의 것이라 미인증 요청에는 401 이다 — 공개
 * 경로인 피드에서 유일하게 로그인을 요구하는 파라미터다.
 */
public enum NewsRelevanceFilter {
    /** 보유 종목 또는 관심 종목이 링크된 기사만. */
    PERSONAL
}
