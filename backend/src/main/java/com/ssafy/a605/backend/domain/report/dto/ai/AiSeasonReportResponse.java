package com.ssafy.a605.backend.domain.report.dto.ai;

import java.util.List;

/**
 * AI 가 돌려주는 시즌 문구 (전처리 요청서 v5 §6 의 {@code season_report_texts}).
 * 봉투가 백엔드 응답 규약과 같은 모양이라 {@code data} 만 벗겨 쓴다.
 *
 * @param reports 계좌별 문구. <b>요청한 계좌가 다 오지 않을 수 있다</b>
 */
public record AiSeasonReportResponse(List<Report> reports) {

    /**
     * @param accountId       문구의 주인
     * @param investorType    투자자 유형 9종 + {@code UNDETERMINED}. 어느 조건에도 닿지 못하면
     *                        억지로 유형을 붙이지 않는다
     * @param typeName        화면에 보일 유형 이름
     * @param typeDescription 유형 설명 한 문장
     * @param verdict         그 사람의 시즌에 대한 총평
     * @param blockIds        고른 문구 블록
     */
    public record Report(long accountId, String investorType, String typeName,
                         String typeDescription, String verdict, List<String> blockIds) {
    }
}
