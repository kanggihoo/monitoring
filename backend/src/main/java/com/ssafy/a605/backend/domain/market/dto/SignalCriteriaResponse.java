package com.ssafy.a605.backend.domain.market.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ssafy.a605.backend.domain.market.entity.SignalCriterion;
import com.ssafy.a605.backend.domain.market.entity.SignalKind;
import com.ssafy.a605.backend.domain.market.entity.SignalLevel;

/**
 * 신호등 판정 기준 (기획서 9.2.4, {@code info.md}). 게임 내 도움말이 그대로 그린다.
 *
 * <p><b>종목 무관 전역 조회다.</b> 기준이 전 종목 공통이고 시즌 중 바뀌지 않으므로, 종목마다
 * 내려주면 28번 같은 값을 보낸다. 재무 지표가 하나도 적재되지 않은 상태에서도 정상 응답한다.
 *
 * @param criteria 세 질문의 기준. 도움말 표시 순서다
 */
public record SignalCriteriaResponse(List<Criterion> criteria) {

    /**
     * 종류별로 묶는다. 정렬은 {@code sort_order} 가 정하므로 삽입 순서를 유지하는 맵을 쓴다 —
     * 종류의 등장 순서와 종류 안 등급의 순서가 모두 그 값에서 나온다.
     *
     * @param rows 표시 순서로 읽은 판정 기준 행
     * @return 세 질문으로 묶은 응답
     */
    public static SignalCriteriaResponse from(List<SignalCriterion> rows) {
        Map<SignalKind, List<SignalCriterion>> byKind = new LinkedHashMap<>();
        for (SignalCriterion row : rows) {
            byKind.computeIfAbsent(row.getKind(), kind -> new ArrayList<>()).add(row);
        }
        return new SignalCriteriaResponse(byKind.entrySet().stream()
                .map(entry -> Criterion.of(entry.getKey(), entry.getValue()))
                .toList());
    }

    /**
     * 한 질문의 기준.
     *
     * @param kind       세 질문 중 하나
     * @param question   화면에 그대로 나가는 질문 문장. {@link SignalKind} 상수다
     * @param metricKey  판정에 쓰는 지표. 종목 카드의 {@code basisKeys} 가 이 값이다
     * @param thresholds 등급별 기준. 3단계다
     */
    public record Criterion(SignalKind kind, String question, String metricKey,
                            List<Threshold> thresholds) {

        static Criterion of(SignalKind kind, List<SignalCriterion> rows) {
            return new Criterion(kind, kind.getQuestion(), rows.getFirst().getMetricKey(),
                    rows.stream().map(Threshold::from).toList());
        }
    }

    /**
     * 등급 하나의 기준.
     *
     * @param level       판정 결과
     * @param description 숫자가 아니라 <b>완성된 문장</b>이다 — 항목마다 표현 방식이 달라
     *                    ("20% 이상", "역성장") 프론트의 단일 템플릿으로 조립할 수 없고, 기준이
     *                    바뀔 때 클라이언트를 고치게 된다. 경계값 비교는 서버에만 있다
     */
    public record Threshold(SignalLevel level, String description) {

        static Threshold from(SignalCriterion criterion) {
            return new Threshold(criterion.getLevel(), criterion.getDescription());
        }
    }
}
