package com.ssafy.a605.backend.domain.news.entity;

/**
 * 뉴스 피드의 채널 탭. 종목 태그가 없는 매크로 기사(금리·환율·지수)도 채널은 갖는다.
 */
public enum NewsChannel {
    US,
    KR,
    CRYPTO
}
