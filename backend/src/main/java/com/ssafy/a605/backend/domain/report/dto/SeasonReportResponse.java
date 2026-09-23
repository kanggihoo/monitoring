package com.ssafy.a605.backend.domain.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 시즌 리포트 ({@code rpt.md} §GET /api/v1/reports/season, 기획서 13.3).
 *
 * <p><b>지표는 저장값이고 비교 기준선은 파생이다.</b> {@code metrics} 는 3일차 폐장에 얼린
 * {@code season_reports} 를 그대로 읽는다 — 다시 계산하면 값이 달라질 수 있고({@code _common.md}
 * §6.3) 최대 낙폭은 애초에 시즌이 지나간 뒤에는 만들 수 없다. {@code benchmarks} 는 종가에서
 * 조회 시점에 만든다.
 *
 * <p><b>비교 기준선에 "나"를 함께 담는다</b> (기획서 13.3.3 "가만히 있었다면 vs 나"). 같은
 * 배열에 두면 프론트가 정렬·색칠을 한 번에 하고, {@code finalReturn} 과 따로 두면 두 값이
 * 어긋날 경로가 생긴다.
 *
 * @param finalizedAt       3일차 폐장 스냅샷 시각
 * @param metrics           시즌 핵심 지표 (기획서 13.3.2)
 * @param benchmarks        비교 기준선. 종가를 찾지 못한 기준선은 빠지고 "나"는 항상 있다
 * @param finalRank         최종 순위
 * @param totalParticipants 3일차에 확정된 계좌 수. 위 순위의 분모다
 * @param investorType      AI 가 판정한 투자자 유형 (기획서 13.4). 문구가 없으면 {@code null}
 * @param shareCard         결과 카드 (기획서 13.5, 선택). 같은 이유로 {@code null}
 */
public record SeasonReportResponse(
        Instant finalizedAt,
        Metrics metrics,
        List<Benchmark> benchmarks,
        Integer finalRank,
        long totalParticipants,
        InvestorType investorType,
        Void shareCard
) {

    /**
     * AI 가 판정한 투자자 유형 (AI 전처리 요청서 v5 §6, 기획서 13.4).
     *
     * <p><b>없을 수 있다.</b> 3일차 폐장 뒤 외부 호출이 성공해야 생기므로, 실패하면 이
     * 자리가 {@code null} 이고 화면은 그 영역을 비운 채 리포트를 연다.
     *
     * <p><b>유형 코드를 내보내지 않는다.</b> {@code season_report_texts.investor_type} 의
     * {@code MOMENTUM_CHASER} 같은 값은 AI 가 블록을 고르는 축이고, 화면이 쓰는 것은
     * 이름과 문장이다. 코드를 계약에 올리면 유형 목록이 바뀔 때마다 프론트가 함께 바뀐다.
     *
     * @param name        화면에 보일 유형 이름 (예: {@code 추격 매수형})
     * @param description 유형 설명 한 문장
     * @param verdict     그 사람의 시즌에 대한 총평
     */
    public record InvestorType(String name, String description, String verdict) {
    }

    /** 응답에 쓰는 "나"의 이름. 기획서 13.3.3 의 "가만히 있었다면 vs 나" 에서 온다. */
    public static final String ME = "나";

    /**
     * 시즌 핵심 지표 (기획서 13.3.2). 다섯 다 {@code season_reports} 의 저장값이다.
     *
     * @param finalReturn  최종 수익률(%)
     * @param maxDrawdown  최대 낙폭(%). 음수이며 0 이면 최고점에서 내려온 적이 없다
     * @param winRate      승률(%). 실현 손익이 양수인 매도 비율이고 매도가 없으면 0
     * @param turnover     회전율. 총 거래대금 ÷ 평균 자산
     * @param totalFeeKrw  시즌 총 거래 수수료 (원화 환산)
     */
    public record Metrics(BigDecimal finalReturn, BigDecimal maxDrawdown, BigDecimal winRate,
                          BigDecimal turnover, String totalFeeKrw) {
    }

    /**
     * 비교 기준선 한 줄 (기획서 13.3.3).
     *
     * @param label      화면에 그대로 실리는 이름
     * @param returnRate 재생 구간 전체를 단순 보유했을 때의 수익률(%)
     */
    public record Benchmark(String label, BigDecimal returnRate) {
    }
}
