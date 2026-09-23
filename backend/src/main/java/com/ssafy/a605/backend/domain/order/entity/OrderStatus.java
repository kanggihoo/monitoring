package com.ssafy.a605.backend.domain.order.entity;

/**
 * 예약 주문의 상태. 취소·만료된 주문도 행을 지우지 않는다 —
 * 사용자 취소(CANCELLED)와 폐장 자동 취소(EXPIRED)를 구분해 남겨야 한다 (기획서 6.2.4).
 */
public enum OrderStatus {
    OPEN,
    FILLED,
    CANCELLED,
    EXPIRED
}
