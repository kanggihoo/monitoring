package com.ssafy.a605.backend.domain.season.dto;

import java.time.Instant;
import java.util.List;

/**
 * 당일 개장 브리핑 (기획서 3.5, {@code sea.md}). 브리핑 시간(11:00~11:05)에 표시하고
 * <b>개장 이후에도 다시 열람할 수 있다</b>(3.5.3) — 그래서 허용 phase 가 {@code ALWAYS} 다.
 *
 * <p><b>담지 않는 것 세 가지</b> — 전부 기획서 3.5.2 의 명시 금지다.
 * <ol>
 *   <li>당일 재생의 <b>종료 지점이 없다</b>. 구간 전체를 알면 그날의 시장 국면을 아는 것과 같다.</li>
 *   <li>구간 내용·국면 설명·주요 이벤트 요약이 없다.</li>
 *   <li>청약 종목명이 없다 — {@code ipoWindows[]} 에 {@code instrumentId} 도 {@code name} 도
 *       없고 <b>시각만</b> 있다. 종목명은 공시 시점에 공모 영역이 공개한다.</li>
 * </ol>
 *
 * @param seasonDay            진행 중인 일차
 * @param replayStartGameMonth <b>당일 재생 시작 시점만</b> ({@code YYYY-MM})
 * @param schedule             당일 일정의 절대 시각
 * @param ipoWindows           당일 청약 일정. 청약이 없는 날은 빈 배열이다
 */
public record SeasonBriefingResponse(
        Short seasonDay,
        String replayStartGameMonth,
        Schedule schedule,
        List<IpoWindow> ipoWindows
) {

    /**
     * 당일 일정. 전부 <b>절대 시각</b>이다 — 하루 시간표의 반복 규칙과 달리 오늘의 사실이다.
     *
     * @param tradingStartsAt  거래 시작
     * @param feeFreeStartsAt  수수료 무료 시작
     * @param feeFreeEndsAt    수수료 무료 종료
     * @param blackoutStartsAt 랭킹 암전 시작
     * @param closesAt         폐장
     * @param settlementAt     정산 발표
     */
    public record Schedule(
            Instant tradingStartsAt,
            Instant feeFreeStartsAt,
            Instant feeFreeEndsAt,
            Instant blackoutStartsAt,
            Instant closesAt,
            Instant settlementAt
    ) {
    }

    /**
     * 당일 청약 한 건의 시각.
     *
     * <p>종목을 특정하지 않는데도 배열인 이유는 <b>하루에 청약이 여러 건</b>이기 때문이다
     * (1일차 4건, 2·3일차 각 2건). 프론트는 "오늘 12:17 · 13:41 에 청약이 있습니다"를 그린다.
     *
     * @param disclosureAt          공시 시각. 이때 종목명이 공개된다
     * @param subscriptionClosesAt  청약 마감 시각. 마감 즉시 추첨한다
     */
    public record IpoWindow(Instant disclosureAt, Instant subscriptionClosesAt) {
    }
}
