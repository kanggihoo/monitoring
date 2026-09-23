package com.ssafy.a605.backend.domain.ranking.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /api/v1/rankings/awards} 의 {@code data}. 실시간 {@code ranking.awarded} 의 payload 도
 * 이 모양이다.
 *
 * <p><b>정산 발표(17:00) 전의 일차는 들어 있지 않다.</b> 폐장(16:40)에 확정돼 리더보드는 이미
 * {@code finalized: true} 를 내려주지만, 시상 연출은 발표에 맞춘다 (기획서 11.4.1).
 *
 * <p><b>동점은 같은 순위다</b> (1, 1, 3). 그래서 {@code final} 도 목록이다 — 시즌 순위 1위가 여럿이면 전원이
 * 우승자이고 임의로 한 명을 고르지 않는다.
 *
 * @param daily       발표된 일차별 1위. 동점이면 같은 일차의 항목이 여럿이다
 * @param finalAwards 시즌 우승자. 동점이면 여럿, 마지막 일차 발표 전에는 빈 배열. JSON 이름은 {@code final} 이다
 */
public record AwardsResponse(List<AwardResponse> daily, @JsonProperty("final") List<AwardResponse> finalAwards) {
}
