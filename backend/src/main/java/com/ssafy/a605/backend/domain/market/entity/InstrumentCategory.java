package com.ssafy.a605.backend.domain.market.entity;

/**
 * 종목 목록의 탭 구분. 결제 통화(Currency)와 지금은 1:1 로 대응하지만,
 * 화면 분류와 주문 검증은 서로 다른 관심사이므로 합치지 않는다.
 */
public enum InstrumentCategory {
    NASDAQ,
    KRX,
    CRYPTO
}
