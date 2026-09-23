package com.ssafy.a605.backend.domain.order.entity;

/**
 * 주문 방향. 공매도가 없으므로 SELL 은 항상 보유 수량 한도 안이다 (기획서 6.5.3).
 */
public enum OrderSide {
    BUY,
    SELL
}
