package com.ssafy.a605.backend.domain.order.dto;

import java.util.List;

/**
 * 예약 한 건 이상이 체결됐다는 프로세스 내부 신호. 알림 센터가 이 신호로 개인 알림 행을
 * 남긴다 ({@code noti.md} §ORDER_FILLED, 기획서 6.4.6).
 *
 * <p><b>실시간 메시지와 별개다.</b> {@code order.filled} 는 접속 중인 사람에게 그 순간
 * 보이는 것이고, 알림은 나중에 열어 보는 기록이다 — 둘의 수명이 다르므로 한쪽이 다른 쪽을
 * 대신하지 못한다.
 *
 * <p><b>체결 트랜잭션이 커밋된 뒤에 발행된다.</b> 안에서 발행하면 알림 쓰기가 그 트랜잭션에
 * 합류해, 알림 실패가 체결을 롤백시킨다. 알림이 거래를 되돌리는 것은 방향이 거꾸로다.
 *
 * @param accountId  체결된 계좌
 * @param executions 그 틱에 체결된 항목. 비어 있지 않다
 * @param tickSeq    체결이 일어난 틱
 */
public record OrderFilledEvent(Long accountId, List<ExecutionResponse> executions, Long tickSeq) {
}
