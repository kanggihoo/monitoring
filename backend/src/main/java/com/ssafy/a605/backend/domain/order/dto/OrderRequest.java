package com.ssafy.a605.backend.domain.order.dto;

import java.math.BigDecimal;

import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderType;

import jakarta.validation.constraints.NotNull;

/**
 * 주문 생성 요청 (기획서 6.1, {@code ord.md}). 시장가와 예약이 <b>한 경로</b>다 —
 * 예상 금액 표시·잔고 검증·수수료 계산이 둘에 동일하게 적용되므로 경로를 나누면 그 로직이
 * 두 곳에 복제된다.
 *
 * <p>수량은 <b>1주 단위 정수</b>다 (기획서 6.5.2). 소수 보유가 없어 금액 지정 주문도 없다 —
 * "150만원어치"가 아니라 "3주"다.
 *
 * <p>수량의 하한과 지정가의 자릿수 검증은 서비스가 한다. 허용 자릿수가 종목의
 * {@code priceScale} 에 달려 있어 Bean Validation 으로는 표현할 수 없고, 지정가의 필수 여부도
 * {@code orderType} 에 달려 있다.
 *
 * @param orderType    시장가는 즉시 전량 체결되고, 예약은 다음 틱부터 조건을 볼 때 체결된다
 * @param side         주문 방향
 * @param instrumentId 대상 종목
 * @param quantity     주문 수량. 1 이상이어야 한다
 * @param limitPrice   지정가. {@code LIMIT} 일 때만 쓰이며 시장가면 무시한다
 */
public record OrderRequest(
        @NotNull(message = "주문 유형은 필수입니다.") OrderType orderType,
        @NotNull(message = "주문 방향은 필수입니다.") OrderSide side,
        @NotNull(message = "종목은 필수입니다.") Long instrumentId,
        @NotNull(message = "주문 수량은 필수입니다.") Integer quantity,
        BigDecimal limitPrice
) {
}
