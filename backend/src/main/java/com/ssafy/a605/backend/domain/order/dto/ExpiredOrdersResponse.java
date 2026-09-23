package com.ssafy.a605.backend.domain.order.dto;

import java.time.Instant;
import java.util.List;

/**
 * 폐장 자동 취소 통보 ({@code order.expired}, {@code ord.md} §실시간). 취소된 예약과 그때
 * 반환된 잠금을 담는다.
 *
 * <p><b>봉투의 {@code tickSeq} 는 {@code null} 이다.</b> 폐장(16:40)은 틱 경계와 일치하지 않아
 * 자동 취소가 어떤 틱에도 속하지 않는다. 마지막 틱 번호를 억지로 붙이면 <i>"그 틱에서
 * 체결됐다"</i> 와 구분이 사라져, 복기 리포트가 폐장 취소를 마지막 틱의 사건으로 그린다.
 * 그래서 시점을 {@code occurredAt} 이 대신 담는다.
 *
 * <p><b>항목이 {@link OpenOrderResponse} 인 것은 의도다.</b> 프론트가 지우려는 대상이 방금까지
 * 미체결 목록에 있던 바로 그 항목이라, 같은 형태여야 {@code orderId} 로 짝지어 걷어낼 수 있다.
 * 그 안의 {@code lockedAmount}·{@code lockedQuantity} 가 곧 <b>반환된 잠금</b>이다 — 취소는
 * 잠긴 것을 그대로 되돌리므로 별도 필드를 두면 같은 값이 두 번 실린다.
 *
 * <p><b>한 통에 여러 건을 담는다.</b> 체결({@code order.filled})이 건마다 한 통인 것과 다른데,
 * 폐장 취소는 <b>한 순간에 일어난 하나의 사건</b>이라서다 — 예약 다섯 건이 사라진 것을 다섯
 * 통으로 알리면 사용자에게는 다섯 번의 사고로 보인다.
 *
 * @param occurredAt 폐장 실제 시각. 봉투에 틱이 없으므로 이 값이 시점이다
 * @param orders     취소된 예약과 반환된 잠금. 등록 순서다
 */
public record ExpiredOrdersResponse(Instant occurredAt, List<OpenOrderResponse> orders) {
}
