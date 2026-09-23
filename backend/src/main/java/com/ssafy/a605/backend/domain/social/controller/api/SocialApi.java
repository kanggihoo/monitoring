package com.ssafy.a605.backend.domain.social.controller.api;

import com.ssafy.a605.backend.domain.social.dto.HeatmapResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 소셜·군중심리 (기획서 12장, {@code docs/projects/market-replay-api/soc.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다.
 *
 * <p>히트맵 조회는 로그인이 필요하고 허용 phase 는 {@code ALWAYS} 다. 채팅과 달리 미인증에 열지
 * 않는다 — 이것은 게임 내부 상태의 집계이고, 기획서 12.4 에 미인증 허용 언급이 없다.
 */
@Tag(name = "Social", description = "소셜·군중심리 API")
public interface SocialApi {

    @Operation(
            summary = "포지션 히트맵",
            description = "지금 참가자들이 어디에 자금을 두고 있는지를 카테고리 단위 익명 집계로 돌려준다. "
                    + "버킷은 NASDAQ · KRX · CRYPTO · CASH 넷이고 순서가 고정이다. CASH 는 원화·달러 현금의 합이며 "
                    + "통화를 나누지 않는다 — 나누면 '환전했다'가 '팔았다'처럼 보인다. "
                    + "weight 는 전체 평가액 대비 비중(%), weightDelta 는 직전 틱 대비 비중 변화(%p) 다. "
                    + "집계는 종가 틱마다 갱신되며 실시간 social.heatmap 으로 같은 본문이 브로드캐스트된다. "
                    + "네 버킷의 합은 totalValuationKrw 와 정확히 같다. "
                    + "종목 단위 집계는 내려주지 않는다 — 특정 종목의 집단 매도가 보이면 상위권 미러링과 같은 성질의 정보가 된다 (기획서 11.6.2). "
                    + "개인 식별 정보와 참가자 수는 어떤 필드로도 나가지 않는다 (기획서 12.4.3). "
                    + "아직 어떤 틱도 순회되지 않았으면 (시즌 첫 틱 전, 서버 재시작 직후 첫 틱 전) "
                    + "asOfTickSeq · asOfGameDate 가 null 이고 buckets 가 빈 배열이다. "
                    + "순회했지만 참가자가 없었던 틱은 기준 틱이 있고 네 버킷이 모두 0 이다."
    )
    ApiResponse<HeatmapResponse> getHeatmap();
}
