package com.ssafy.a605.backend.domain.social.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 한 틱의 히트맵 집계 ({@code soc.md} §갱신 주기는 매 틱이다).
 *
 * <p><b>저장하지 않는다.</b> {@code RankingSnapshot} 과 같은 성질의 파생값이라 매 틱 전체가 다시
 * 만들어진다. 서버가 재시작되면 다음 틱에 복구되며 그 틱의 {@code weightDelta} 는 0 이다 —
 * 비교할 직전 값이 없다.
 *
 * @param tickSeq           이 집계의 기준 틱
 * @param gameDate          그 틱의 게임 거래일
 * @param totalValuationKrw 순위에 든 전 계좌의 평가액 합
 * @param buckets           네 버킷. 순서가 고정이라 화면이 매 틱 같은 모양으로 그린다
 */
public record HeatmapSnapshot(long tickSeq, LocalDate gameDate, BigDecimal totalValuationKrw,
                              List<Bucket> buckets) {

    /**
     * 한 버킷의 집계.
     *
     * @param category    버킷
     * @param valuationKrw 그 버킷의 원화 환산 합계
     * @param weight       전체 대비 비중(%)
     * @param weightDelta  직전 틱 대비 비중 변화(%p). 첫 틱·재시작 직후는 0
     */
    public record Bucket(HeatmapBucket category, BigDecimal valuationKrw, BigDecimal weight,
                         BigDecimal weightDelta) {
    }
}
