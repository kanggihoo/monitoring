package com.ssafy.a605.backend.domain.report.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 열람 가능한 일차 목록 ({@code rpt.md} §GET /api/v1/reports/daily, 기획서 13.1.5).
 *
 * <p><b>여기서 재생 구간이 처음 공개된다.</b> 브리핑은 당일 시작 시점만 알리고 종료 지점을
 * 숨기지만(기획서 3.5.2), 정산이 끝난 일차는 미래 차단의 필요가 사라진다. 아직 오지 않은
 * 일차의 구간은 여전히 {@code null} 이다 — 3일차 구간을 2일차 화면에서 보여주면 차단이 뚫린다.
 *
 * @param items 시즌의 모든 일차. 일차 오름차순이며 미발표 일차도 자리를 지킨다
 */
public record DailyReportListResponse(List<Item> items) {

    /**
     * 한 일차의 열람 가능 여부.
     *
     * <p><b>{@code available} 은 발표 여부다.</b> 내 복기 행이 있는지는 보지 않는다 —
     * {@code rpt.md} 가 "{@code available: false} 인 일차는 아직 정산 발표 전"이라고 정의한다.
     * 그래서 중간 합류자는 발표된 일차에 {@code available: true} 이면서 {@code dailyReturn}
     * 이 {@code null} 인 항목을 볼 수 있고, 그 일차의 상세는 404 다.
     *
     * @param seasonDay     일차
     * @param fromGameMonth 그 일차가 재생한 구간의 시작 월. 미발표면 {@code null}
     * @param toGameMonth   재생 구간의 끝 월. 미발표면 {@code null}
     * @param dailyReturn   그 일차의 확정 일간 수익률(%). 미발표거나 내 행이 없으면 {@code null}
     * @param available     정산 발표가 끝났는가
     */
    public record Item(short seasonDay, String fromGameMonth, String toGameMonth,
                       BigDecimal dailyReturn, boolean available) {

        /** 아직 발표되지 않은 일차. 구간과 수익률을 담지 않는다. */
        public static Item unavailable(short seasonDay) {
            return new Item(seasonDay, null, null, null, false);
        }
    }
}
