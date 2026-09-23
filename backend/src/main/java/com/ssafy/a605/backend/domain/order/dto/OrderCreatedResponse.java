package com.ssafy.a605.backend.domain.order.dto;

import com.ssafy.a605.backend.domain.order.entity.OrderType;

/**
 * 주문 생성 결과 (기획서 6.1, {@code ord.md}). <b>둘 중 하나만 채워진다</b> — 시장가는 미체결
 * 상태를 절대 거치지 않으므로 {@code execution} 만, 예약은 체결을 등록 시점에 판정하지 않으므로
 * {@code order} 만 온다.
 *
 * <p>한쪽이 항상 {@code null} 인 응답은 일반적으로 피할 형태지만 <b>생성 응답에 한해</b>
 * 허용한다. {@code orderType} 이 어느 쪽이 채워지는지를 결정론적으로 말해 주고, 대안(경로를 둘로
 * 나눔)은 공통 검증·수수료 로직을 복제하게 만든다 ({@code ord.md} §리소스 경계).
 *
 * @param orderType 어느 필드가 채워졌는지를 말한다
 * @param execution 시장가 체결. 예약이면 {@code null}
 * @param order     등록된 미체결 예약. 시장가면 {@code null}
 */
public record OrderCreatedResponse(
        OrderType orderType,
        ExecutionResponse execution,
        OpenOrderResponse order
) {

    /**
     * @param execution 즉시 전량 체결된 시장가 (기획서 6.1.2)
     * @return {@code order} 가 비어 있는 생성 응답
     */
    public static OrderCreatedResponse market(ExecutionResponse execution) {
        return new OrderCreatedResponse(OrderType.MARKET, execution, null);
    }

    /**
     * <b>{@code execution} 이 비어 있는 것이 정상이다.</b> 등록 시점에는 체결을 판정하지 않으며
     * (기획서 6.4.1) 현재 종가보다 유리한 지정가를 걸어도 다음 틱을 기다린다 — 즉시 체결을
     * 원하는 요구는 시장가가 전담한다 ({@code ord.md} §등록 즉시 판정하지 않는다).
     *
     * @param order 등록된 미체결 예약
     * @return {@code execution} 이 비어 있는 생성 응답
     */
    public static OrderCreatedResponse limit(OpenOrderResponse order) {
        return new OrderCreatedResponse(OrderType.LIMIT, null, order);
    }
}
