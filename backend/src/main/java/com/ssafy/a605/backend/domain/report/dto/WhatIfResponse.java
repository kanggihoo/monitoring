package com.ssafy.a605.backend.domain.report.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * '만약에' 시뮬레이터 ({@code rpt.md} §GET /api/v1/reports/season/what-if, 기획서 13.3.4).
 *
 * <p>"이 종목을 전액 매수해 들고만 있었다면"의 가상 수익률이다. <b>단순 계산이다</b> —
 * 수수료와 환전 수수료를 반영하지 않고 시작 잔고 전액이 그 종목에 들어갔다고 본다. 거래
 * 비용이 끼면 "가만히 있었다면"이라는 비교의 뜻이 흐려진다.
 *
 * <p><b>시즌과 일차를 함께 낸다.</b> 기획서는 시즌 전체만 말하지만 그러면 3일차 발표 전까지
 * 아무것도 볼 수 없다. 발표된 일차는 이미 과거이므로 가릴 이유가 없고, 하루 단위 비교가
 * 오히려 그날의 선택을 돌아보게 한다 ({@code rpt.md} §구현에서 확정).
 *
 * <p><b>같은 축끼리 비교한다.</b> 시즌은 누적 대 누적, 일차는 당일 대 당일이다. 섞으면
 * "내 시즌 84% 대 1일차 종목 38%" 처럼 비교가 성립하지 않는 짝이 나온다.
 *
 * @param instrumentId 비교한 종목
 * @param returnRate   시즌 전체를 들고 있었다면의 수익률(%). 3일차 발표 전에는 {@code null}
 * @param myReturnRate 내 시즌 누적 수익률(%). 3일차 발표 전에는 {@code null}
 * @param days         발표된 일차의 분해. 일차 오름차순이며 아직 발표된 일차가 없으면 이 조회
 *                     자체가 409 라 비어 있지 않다
 */
public record WhatIfResponse(Long instrumentId, BigDecimal returnRate, BigDecimal myReturnRate,
                             List<Day> days) {

    /**
     * 한 일차의 비교.
     *
     * @param seasonDay    일차
     * @param returnRate   그 일차 구간을 들고 있었다면의 수익률(%). 그 일차에 아직 상장 전이었던
     *                     IPO 종목은 {@code null} 이다 — 살 수 없었으므로 가정이 성립하지 않는다
     * @param myReturnRate 그 일차의 내 확정 일간 수익률(%). 그 일차에 참가하지 않았으면
     *                     {@code null}
     */
    public record Day(short seasonDay, BigDecimal returnRate, BigDecimal myReturnRate) {
    }
}
