package com.ssafy.a605.backend.domain.ranking.dto;

/**
 * 한 일차의 폐장 스냅샷이 {@code daily_settlements} 에 커밋됐다는 프로세스 내부 신호.
 * {@code ranking.finalized} WS 발행과 시상은 이것을 듣고 시작한다 ({@code rank.md} §실시간) —
 * 커밋 뒤에만 발행되므로 듣는 쪽은 그 일차의 행이 있다고 믿어도 된다.
 *
 * @param seasonDay    확정된 일차
 * @param accountCount 저장된 행 수. 순위에 들었던 계좌 수와 같다
 */
public record DailySettlementFinalizedEvent(short seasonDay, int accountCount) {
}
