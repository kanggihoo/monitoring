package com.ssafy.a605.backend.domain.order.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.service.OrderQuote;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 미체결 예약 한 건 ({@code ord.md}).
 *
 * <p>{@code lockedAmount} 와 {@code lockedQuantity} 중 하나가 항상 {@code null} 인 것은
 * 매수·매도가 서로 다른 것을 잠그기 때문이다 — 매수는 현금, 매도는 수량. 하나로 합치면 금액과
 * 주 수가 한 필드에 섞인다.
 *
 * @param orderId         예약 식별자
 * @param side            주문 방향
 * @param instrumentId    대상 종목
 * @param quantity        주문 수량
 * @param limitPrice      지정가. 자릿수는 종목 메타의 {@code priceScale} 을 따른다
 * @param currency        결제 통화
 * @param lockedAmount    <b>매수만.</b> {@code 지정가 × 수량} — 매수에 수수료가 없어 잠금액이 곧
 *                        체결 금액이다 ({@code ord.md} §수수료)
 * @param lockedQuantity  <b>매도만.</b> 잠긴 보유 수량
 * @param expiresAt       당일 폐장 시각. 예약의 유효기간은 당일뿐이다 (6.2.4)
 * @param createdGameDate 등록 시점의 게임 거래일
 * @param createdAt       등록 실제 시각
 */
public record OpenOrderResponse(
        Long orderId,
        OrderSide side,
        Long instrumentId,
        Integer quantity,
        String limitPrice,
        Currency currency,
        String lockedAmount,
        Integer lockedQuantity,
        Instant expiresAt,
        LocalDate createdGameDate,
        Instant createdAt
) {

    /**
     * <b>{@code createdGameDate} 를 받아야 하는 이유.</b> 예약 행은 게임 시각을 틱으로만 갖고
     * 있어 ({@code orders.created_tick_seq}) 거래일을 스스로 말할 수 없다. 체결이 자기 거래일을
     * 저장하는 것과 다른 것은 목적이다 — 체결은 영구 기록이라 편성 재적재에도 흔들리지 않아야
     * 하지만, 미체결 예약은 당일 폐장에 소멸하므로 재적재를 넘겨 살아남지 않는다.
     *
     * @param order           등록된 예약
     * @param priceScale      지정가의 표현 자릿수. 종목 메타의 값이다
     * @param createdGameDate 등록 시점 틱의 게임 거래일
     * @return 미체결 예약 응답
     */
    public static OpenOrderResponse of(Order order, short priceScale, LocalDate createdGameDate) {
        int scale = OrderQuote.scale(order.getCurrency());
        return new OpenOrderResponse(
                order.getId(),
                order.getSide(),
                order.getInstrumentId(),
                order.getQuantity(),
                Amounts.of(order.getLimitPrice(), priceScale),
                order.getCurrency(),
                order.getLockedAmount() == null ? null : Amounts.of(order.getLockedAmount(), scale),
                order.getLockedQuantity(),
                order.getExpiresAt(),
                createdGameDate,
                order.getCreatedAt());
    }
}
