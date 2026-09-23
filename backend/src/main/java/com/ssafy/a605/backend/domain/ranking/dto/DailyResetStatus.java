package com.ssafy.a605.backend.domain.ranking.dto;

/**
 * 일차 초기화의 개장 전 점검 결과.
 *
 * <p><b>"돌았는가" 가 아니라 "지금 열어도 되는가" 를 답한다.</b> {@code pending} 이 0 이면 그
 * 일차의 모든 계좌가 시드에서 출발했다는 뜻이고, {@code missingSnapshots} 가 0 이면 전날
 * 기록이 보존됐다는 뜻이다. 둘 중 하나라도 0 이 아니면 개장하면 안 된다.
 *
 * @param seasonDay         점검한 일차
 * @param accounts          그 시즌의 계좌 수
 * @param pending           아직 초기화되지 않은 계좌 수. 0 이어야 한다
 * @param previousSettled   전일 확정 정산 수
 * @param missingSnapshots  전일 확정 중 마감 보유 스냅샷이 없는 수. 0 이어야 한다
 */
public record DailyResetStatus(
        short seasonDay,
        long accounts,
        long pending,
        long previousSettled,
        long missingSnapshots
) {
}
