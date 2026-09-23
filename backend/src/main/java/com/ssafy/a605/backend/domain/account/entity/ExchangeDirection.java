package com.ssafy.a605.backend.domain.account.entity;

/**
 * 환전 방향. 금액은 항상 보내는 통화 기준으로 기록한다 —
 * 받는 통화 기준으로 두면 수수료 역산과 반올림 방향이 애매해진다 (기획서 7.2).
 */
public enum ExchangeDirection {
    KRW_TO_USD,
    USD_TO_KRW
}
