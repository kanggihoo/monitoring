package com.ssafy.a605.backend.domain.ranking.dto;

import java.math.BigDecimal;

/**
 * 포인트 합산 순위 한 줄 ({@code S15P21A605-250}).
 *
 * <p>확정된 일차의 일간 순위를 점수로 바꿔 더한 값이다. 실시간 순위와 달리 <b>폐장 정산에만
 * 바뀐다</b> — 진행 중인 오늘은 아직 확정 행이 없어 셈에 들어가지 않는다.
 *
 * @param accountId      계좌
 * @param rank           포인트 순위. 1 부터, 동점은 같은 순위이고 다음 순위를 건너뛴다
 * @param points         일간 순위 포인트 합
 * @param wins           일간 1 등 횟수. 첫 번째 동점 기준이다
 * @param dailyReturnSum 일간 수익률의 <b>단순 합</b>(%). 두 번째 동점 기준이며
 *                       <b>누적 수익률이 아니다</b> — 100 % 뒤 −50 % 면 이 값은 50 % 지만
 *                       실제 누적은 0 % 다
 */
public record PointStanding(long accountId, int rank, long points, long wins, BigDecimal dailyReturnSum) {
}
