package com.ssafy.a605.backend.domain.market.entity;

/**
 * 신호등 판정 결과 3단계 (기획서 9.2.2).
 * 근거 지표를 산출할 수 없는 경우는 이 중 어느 값도 아니며, 판정 자체를 하지 않는다.
 */
public enum SignalLevel {
    GOOD,
    NORMAL,
    CAUTION
}
