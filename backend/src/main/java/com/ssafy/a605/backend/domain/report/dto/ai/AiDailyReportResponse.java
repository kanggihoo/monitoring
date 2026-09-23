package com.ssafy.a605.backend.domain.report.dto.ai;

import java.util.List;

import com.ssafy.a605.backend.domain.report.dto.Review;

/**
 * AI 가 돌려주는 일일 문구 (AI 요청서 §5). 봉투가 백엔드 응답 규약과 같은 모양이라
 * {@code data} 만 벗겨 쓴다.
 *
 * @param seasonDay 그 일차
 * @param reports   계좌별 문구. <b>요청한 계좌가 다 오지 않을 수 있다</b>
 */
public record AiDailyReportResponse(short seasonDay, List<Report> reports) {

    /**
     * @param accountId    문구의 주인
     * @param headline     머리말 한 줄
     * @param sceneAliases 결정적 장면 별칭 0~3개. 거래가 없으면 빈 배열
     * @param blockIds     고른 문구 블록. 다음 일차의 변주를 피하는 데 쓰이므로 저장한다
     * @param review       복기 결과지. <b>요청에 누적 집계({@code cumulative})를 싣지 않으면
     *                     {@code null}</b> 이고, 그때는 머리말과 장면 별칭만 온다 — 화면은 그
     *                     자리를 비운 채 나머지 복기를 연다
     */
    public record Report(long accountId, String headline, List<String> sceneAliases,
                         List<String> blockIds, Review review) {
    }
}
