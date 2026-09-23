package com.ssafy.a605.backend.domain.account.dto;

import java.math.BigDecimal;

import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;

import jakarta.validation.constraints.NotNull;

/**
 * 환전 요청 (기획서 7.2.2, {@code acc.md}).
 *
 * <p><b>{@code amount} 는 보내는 통화 기준이다.</b> "달러 100 을 받고 싶다"가 아니라 "원화
 * 150 만을 보낸다"다. 받는 통화 기준으로 하면 수수료를 역산해야 하고 반올림 방향이 애매해지며,
 * 가진 것을 다 보내는 경우(전액 환전)를 표현하기도 어렵다.
 *
 * <p>부족분 환전은 {@code ord.md} 의 {@code shortfallExchange} 를 그대로 본문에 넣으면 된다 —
 * 그 객체가 {@code direction} 과 {@code sendAmount} 를 이미 담고 있어 프론트가 역산하지 않는다.
 *
 * <p>금액의 자릿수 검증은 서비스가 한다. 허용 자릿수가 {@code direction} 에 따라 달라(원화 0,
 * 달러 2) Bean Validation 으로는 표현할 수 없다.
 *
 * @param direction 환전 방향
 * @param amount    보내는 통화 기준 금액
 */
public record ExchangeRequest(
        @NotNull(message = "환전 방향은 필수입니다.") ExchangeDirection direction,
        @NotNull(message = "환전 금액은 필수입니다.") BigDecimal amount
) {
}
