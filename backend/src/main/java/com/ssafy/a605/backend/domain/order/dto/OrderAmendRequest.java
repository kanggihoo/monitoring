package com.ssafy.a605.backend.domain.order.dto;

import java.math.BigDecimal;

/**
 * 미체결 예약 수정 요청 (기획서 6.3.3, {@code ord.md}).
 *
 * <p><b>두 필드 모두 선택이며 최소 하나는 있어야 한다.</b> 비운 쪽은 지금 값을 그대로 쓴다 —
 * 수량만 고치려는 사용자가 지정가를 다시 보내야 한다면, 그 사이에 값을 잘못 옮겨 적는 순간
 * 의도하지 않은 조건으로 예약이 바뀐다.
 *
 * <p><b>방향과 종목은 바꿀 수 없다.</b> 그것을 바꾸는 것은 다른 예약이지 같은 예약의 수정이
 * 아니고, 매수를 매도로 뒤집으면 잠그는 대상 자체가 현금에서 수량으로 달라진다.
 *
 * <p>"최소 하나"와 수량의 하한, 지정가의 자릿수는 서비스가 본다. 필드 사이의 관계와 종목별
 * {@code priceScale} 은 Bean Validation 으로 표현되지 않는다 — {@link OrderRequest} 가 같은
 * 이유로 같은 판단을 하고 있다.
 *
 * @param quantity   새 수량. 비우면 그대로 둔다
 * @param limitPrice 새 지정가. 비우면 그대로 둔다
 */
public record OrderAmendRequest(Integer quantity, BigDecimal limitPrice) {
}
