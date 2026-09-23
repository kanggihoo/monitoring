package com.ssafy.a605.backend.domain.order.entity;

/**
 * 주문 유형. MARKET 은 미체결 상태를 거치지 않고 즉시 전량 체결되므로
 * orders 테이블에 남지 않고 executions 에만 기록된다 (기획서 6.1.2).
 */
public enum OrderType {
    MARKET,
    LIMIT
}
