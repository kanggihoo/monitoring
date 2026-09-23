package com.ssafy.a605.backend.domain.market.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ssafy.a605.backend.domain.market.entity.CardType;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.entity.InstrumentMetric;
import com.ssafy.a605.backend.domain.market.entity.MetricUnit;
import com.ssafy.a605.backend.domain.market.entity.SignalCriterion;
import com.ssafy.a605.backend.domain.market.entity.SignalKind;
import com.ssafy.a605.backend.domain.market.entity.SignalLevel;

/**
 * 종목 정보 카드 (기획서 9장, {@code info.md}).
 *
 * <p><b>{@code cardType} 이 세 카드 필드 중 어느 것이 채워지는지를 결정론적으로 말해 준다.</b>
 * 자산 유형마다 경로를 나누지 않은 이유는 딥링크·새로고침으로 상세에 바로 들어오면 프론트가
 * {@code assetType} 을 모르기 때문이다.
 *
 * @param instrumentId 종목 식별자
 * @param cardType     카드 종류. {@code assetType} 에서 파생된다
 * @param summary      사업·상품·프로젝트 개요 1~2줄. 종목 메타 상세와 같은 값이다
 * @param stock        주식 카드. 그 밖의 유형에서는 {@code null}
 * @param product      상품 구조 카드. 그 밖의 유형에서는 {@code null}
 * @param project      프로젝트 개요 카드. 그 밖의 유형에서는 {@code null}
 */
public record InstrumentInfoResponse(
        Long instrumentId,
        CardType cardType,
        String summary,
        StockCard stock,
        ProductCard product,
        ProjectCard project
) {

    /**
     * 주식·공모주 카드.
     *
     * @param instrument 종목 메타
     * @param disclosed  공개 기준 거래일까지 공시된 지표 행. 아직 없으면 빈 목록
     * @param criteria   신호등 판정 기준 전부
     * @return 주식 카드가 채워진 응답
     */
    public static InstrumentInfoResponse stock(Instrument instrument, List<InstrumentMetric> disclosed,
                                               List<SignalCriterion> criteria) {
        return new InstrumentInfoResponse(instrument.getId(), CardType.STOCK, instrument.getSummary(),
                StockCard.of(disclosed, criteria), null, null);
    }

    /**
     * ETF·인버스 카드.
     *
     * @param instrument 종목 메타
     * @param product    {@code instruments.info_card} 에서 읽은 상품 구조. 아직 없으면 {@code null}
     * @return 상품 카드가 채워진 응답
     */
    public static InstrumentInfoResponse product(Instrument instrument, ProductCard product) {
        return new InstrumentInfoResponse(instrument.getId(), CardType.PRODUCT, instrument.getSummary(),
                null, product, null);
    }

    /**
     * 코인 카드.
     *
     * @param instrument 종목 메타
     * @param project    {@code instruments.info_card} 에서 읽은 프로젝트 개요. 아직 없으면 {@code null}
     * @return 프로젝트 카드가 채워진 응답
     */
    public static InstrumentInfoResponse project(Instrument instrument, ProjectCard project) {
        return new InstrumentInfoResponse(instrument.getId(), CardType.PROJECT, instrument.getSummary(),
                null, null, project);
    }

    /**
     * 주식 카드. 공시된 가장 최근 분기 하나만 담는다 — 지표 시계열은 계약에 없다.
     *
     * @param asOfFiscalQuarter 이 지표가 속한 회계 분기. 공시된 분기가 없으면 {@code null}
     * @param disclosedGameDate 그 분기의 공시일. 공시된 분기가 없으면 {@code null}
     * @param metrics           지표. 공시된 분기가 없으면 빈 배열
     * @param signals           신호등 3종. 지표가 빈 배열이면 함께 빈 배열이다
     */
    public record StockCard(
            String asOfFiscalQuarter,
            LocalDate disclosedGameDate,
            List<Metric> metrics,
            List<Signal> signals
    ) {

        /**
         * 공시된 분기 중 가장 최근 것을 고른다. 분기 문자열이 {@code 2022Q1} 형식이라 사전순
         * 최대가 곧 최신 분기다.
         *
         * <p><b>지표가 없어도 카드 자체는 {@code null} 이 아니다</b> ({@code info.md}) — 카드
         * 종류는 결정되어 있고 내용만 아직 없다. 그때 분기와 공시일도 함께 비운다: 지표가 없는데
         * "2022Q1 기준"이라고 말하면 거짓이 된다. 프론트는 {@code metrics.length === 0} 으로
         * 안내 문구를 그린다.
         *
         * @param disclosed 공개 기준 거래일까지 공시된 지표 행
         * @param criteria  신호등 판정 기준 전부
         * @return 주식 카드
         */
        static StockCard of(List<InstrumentMetric> disclosed, List<SignalCriterion> criteria) {
            String quarter = disclosed.stream()
                    .map(InstrumentMetric::getFiscalQuarter)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (quarter == null) {
                return new StockCard(null, null, List.of(), List.of());
            }
            List<InstrumentMetric> latest = disclosed.stream()
                    .filter(metric -> quarter.equals(metric.getFiscalQuarter()))
                    .sorted(Comparator.comparing(InstrumentMetric::getMetricKey))
                    .toList();
            // 공시일은 분기의 사실이라 그 분기의 행들이 같은 값을 갖는다 (= 분기 종료일 + 45일,
            // NOT NULL). 그래서 어느 행에서 읽어도 같고, 정렬이 고정되어 있어 결과도 고정된다.
            return new StockCard(quarter, latest.getFirst().getDisclosedGameDate(),
                    latest.stream().map(Metric::from).toList(), signals(latest, criteria));
        }

        /**
         * 신호등 3종을 판정한다. 판정을 서버가 하는 이유는 기준이 편성값이라 프론트 판정이
         * 배포를 요구하기 때문이다.
         *
         * <p><b>근거 지표를 산출할 수 없으면 {@code level} 은 {@code null} 이다</b> —
         * "판정할 수 없음"과 "보통"은 다른 사실이다 ({@code info.md}). 그 분기에 근거 지표 행이
         * 아예 없는 경우도 같게 다룬다.
         */
        private static List<Signal> signals(List<InstrumentMetric> latest,
                                            List<SignalCriterion> criteria) {
            Map<String, InstrumentMetric> byKey = latest.stream()
                    .collect(Collectors.toMap(InstrumentMetric::getMetricKey, Function.identity()));
            List<Signal> signals = new ArrayList<>();
            for (SignalKind kind : SignalKind.values()) {
                List<SignalCriterion> forKind = criteria.stream()
                        .filter(criterion -> criterion.getKind() == kind)
                        .toList();
                if (forKind.isEmpty()) {
                    continue;
                }
                // kind 당 metric_key 는 하나다 (UNIQUE (kind, level) 이 강제한다).
                String metricKey = forKind.getFirst().getMetricKey();
                InstrumentMetric basis = byKey.get(metricKey);
                BigDecimal value = basis == null ? null : basis.getValue();
                signals.add(new Signal(kind, value == null ? null : level(forKind, value),
                        List.of(metricKey)));
            }
            return signals;
        }

        private static SignalLevel level(List<SignalCriterion> forKind, BigDecimal value) {
            return forKind.stream()
                    .filter(criterion -> criterion.contains(value))
                    .map(SignalCriterion::getLevel)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 지표 한 줄.
     *
     * @param key   지표 식별자. 대문자 스네이크다
     * @param label 화면 표기
     * @param value 지표 값. <b>수치도 문자열</b>이다 ({@code _common.md} §6.1). 산출 불가면 {@code null}
     * @param unit  값의 단위
     * @param na    산출 불가 여부 (기획서 9.1.3)
     */
    public record Metric(String key, String label, String value, MetricUnit unit, boolean na) {

        /**
         * <b>저장된 자릿수를 그대로 내리지 않는다.</b> 컬럼이 {@code NUMERIC(24, 6)} 이라
         * 그대로 내면 PER 이 {@code "48.200000"} 이 되어 화면이 다시 다듬어야 한다.
         *
         * @param metric 지표 행
         * @return 지표 한 줄
         */
        static Metric from(InstrumentMetric metric) {
            BigDecimal value = metric.getValue();
            return new Metric(metric.getMetricKey(), metric.getLabel(),
                    value == null ? null : value.stripTrailingZeros().toPlainString(),
                    metric.getUnit(), value == null);
        }
    }

    /**
     * 신호등 한 칸 (기획서 9.2).
     *
     * @param kind      세 질문 중 하나
     * @param level     판정 결과. 근거 지표를 산출할 수 없으면 {@code null}
     * @param basisKeys 판정에 쓴 지표의 {@code key}. 프론트가 어느 지표를 펼칠지 추측하지 않게 한다
     */
    public record Signal(SignalKind kind, SignalLevel level, List<String> basisKeys) {
    }

    /**
     * 상품 구조 카드 (기획서 9.3). {@code instruments.info_card} JSON 이 이 모양이다.
     *
     * <p><b>인버스 배수를 말하는 유일한 곳이다.</b> 종목 유형({@code assetType})은 배수를 담지
     * 않기로 했으므로({@code unv.md}) 여기가 배수의 단일 노출 지점이다.
     *
     * @param trackingIndex 추종 지수
     * @param direction     {@code LONG} · {@code INVERSE}
     * @param leverage      배수. {@code 1} · {@code 2} · {@code 3}
     * @param expenseRatio  총보수(%)
     * @param riskNotice    위험 안내. <b>{@code direction} 이 {@code INVERSE} 이거나
     *                      {@code leverage > 1} 이면 반드시 채워진다</b> (기획서 9.3.2)
     */
    public record ProductCard(
            String trackingIndex,
            String direction,
            Integer leverage,
            BigDecimal expenseRatio,
            String riskNotice
    ) {
    }

    /**
     * 프로젝트 개요 카드 (기획서 9.4). {@code instruments.info_card} JSON 이 이 모양이다.
     *
     * @param purpose       코인의 용도
     * @param supplyModel   발행·소각 구조
     * @param ecosystemRole 생태계에서의 역할
     */
    public record ProjectCard(String purpose, String supplyModel, String ecosystemRole) {
    }
}
