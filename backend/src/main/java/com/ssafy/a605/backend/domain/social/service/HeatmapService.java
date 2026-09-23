package com.ssafy.a605.backend.domain.social.service;

import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.social.dto.HeatmapResponse;
import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot;

import lombok.RequiredArgsConstructor;

/**
 * 히트맵 조회 ({@code soc.md} §GET /api/v1/social/heatmap). 집계하지 않는다 —
 * {@link HeatmapAggregator} 가 매 틱 만들어 둔 것을 응답 형태로 바꾼다.
 *
 * <p>트랜잭션이 없다. 읽는 것이 DB 가 아니라 프로세스 안의 집계뿐이다.
 */
@Service
@RequiredArgsConstructor
public class HeatmapService {

    private final HeatmapAggregator aggregator;

    /**
     * 마지막 틱의 히트맵.
     *
     * @return 집계. 아직 어떤 틱도 순회되지 않았으면 기준 틱이 {@code null} 이고 버킷이 빈 배열
     */
    public HeatmapResponse heatmap() {
        HeatmapSnapshot snapshot = aggregator.current();
        return snapshot == null ? HeatmapResponse.empty() : HeatmapResponse.from(snapshot);
    }
}
