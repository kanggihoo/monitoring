package com.ssafy.a605.backend.domain.order.dto;

import java.util.List;

/**
 * 폐장에 미체결 예약이 자동 취소됐다는 프로세스 내부 신호 ({@code noti.md} §ORDER_EXPIRED,
 * 기획서 6.2.4).
 *
 * <p><b>사람당 한 번이다.</b> 폐장 취소는 한 순간에 일어난 하나의 사건이므로 사라진 예약을
 * 모아 한 통으로 알린다 — 다섯 건을 다섯 통으로 알리면 사용자에게는 다섯 번의 사고로 보인다.
 * 체결이 건마다 한 통인 것과 갈리는 지점이며, {@code OrderExpiryListener} 의 실시간 메시지가
 * 같은 판단을 하고 있다.
 *
 * <p>틱을 담지 않는다. 폐장은 16:40 이고 그 시각은 틱 경계와 일치하지 않는다 — 억지로
 * 마지막 틱 번호를 붙이면 복기가 폐장 취소를 그 틱의 사건으로 그린다 ({@code noti.md}).
 *
 * @param accountId 취소가 일어난 계좌
 * @param orders    자동 취소된 예약. 비어 있지 않다
 */
public record OrdersExpiredEvent(Long accountId, List<OpenOrderResponse> orders) {
}
