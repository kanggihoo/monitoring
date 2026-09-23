package com.ssafy.a605.backend.domain.social.dto;

import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot.Bucket;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * {@code GET /api/v1/social/heatmap} 의 {@code data}. 실시간 {@code social.heatmap} 의 payload 도
 * 이 모양이다 ({@code soc.md} §실시간).
 *
 * <p><b>개인 식별 정보가 어떤 형태로도 없다</b> (기획서 12.4.3). 참가자 수도 담지 않는다 —
 * {@code rank.md} 의 {@code totalParticipants} 가 이미 준다.
 *
 * @param asOfTickSeq       집계 기준 틱. 아직 어떤 틱도 순회되지 않았으면 {@code null}
 * @param asOfGameDate      그 틱의 게임 거래일
 * @param totalValuationKrw 전체 평가액 합. 금액이라 문자열이다 ({@code _common.md} §6.1)
 * @param buckets           네 버킷. 순회 전에는 빈 배열
 */
public record HeatmapResponse(Long asOfTickSeq, LocalDate asOfGameDate, String totalValuationKrw,
                              List<BucketResponse> buckets) {

    /** 아직 어떤 틱도 순회되지 않았을 때. 순회했고 참가자가 없었던 경우와 구별된다(그때는 0 인 네 버킷이 온다). */
    public static HeatmapResponse empty() {
        return new HeatmapResponse(null, null, Amounts.of(java.math.BigDecimal.ZERO, Amounts.KRW_SCALE),
                List.of());
    }

    /**
     * @param snapshot 마지막 틱의 집계
     * @return 응답 형태로 바꾼 집계
     */
    public static HeatmapResponse from(HeatmapSnapshot snapshot) {
        return new HeatmapResponse(snapshot.tickSeq(), snapshot.gameDate(),
                Amounts.of(snapshot.totalValuationKrw(), Amounts.KRW_SCALE),
                snapshot.buckets().stream().map(BucketResponse::from).toList());
    }

    /**
     * @param category     버킷
     * @param valuationKrw 원화 환산 합계
     * @param weight       비중(%)
     * @param weightDelta  직전 틱 대비 비중 변화(%p) (기획서 12.4.2)
     */
    public record BucketResponse(HeatmapBucket category, String valuationKrw, java.math.BigDecimal weight,
                                 java.math.BigDecimal weightDelta) {

        static BucketResponse from(Bucket bucket) {
            return new BucketResponse(bucket.category(), Amounts.of(bucket.valuationKrw(), Amounts.KRW_SCALE),
                    bucket.weight(), bucket.weightDelta());
        }
    }
}
