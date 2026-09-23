package com.ssafy.a605.backend.domain.ipo.dto;

import java.math.BigDecimal;

/**
 * 실시간 경쟁률 ({@code ipo.competition}, 기획서 8.3). 청약 창이 열린 동안 <b>매 틱</b> 나간다.
 *
 * <p><b>자체 주기를 만들지 않는다.</b> 기획서는 "실시간 공개"라 하지만 별도 주기를 두면 화면의
 * 다른 숫자들과 시점이 갈린다 — 청약 창은 약 20틱이라 그 안에서 스무 번 갱신되며, 이 게임의
 * 나머지 값들과 같은 리듬이다.
 *
 * <p>경쟁률과 확률을 둘 다 싣는 이유는 조회 응답과 같다 — 프론트가 한쪽을 뒤집으면 반올림이
 * 갈려 서로 안 맞는 두 숫자가 한 화면에 뜬다.
 *
 * @param offeringId          대상 공모
 * @param subscriberCount     현재 청약자 수
 * @param expectedWinnerCount 당첨 예정 인원. 청약자가 없으면 0
 * @param competitionRate     경쟁률. 청약자가 없으면 {@code null}
 * @param winProbability      예상 당첨 확률(%). 청약자가 없으면 {@code null}
 */
public record IpoCompetitionResponse(Long offeringId, int subscriberCount, int expectedWinnerCount,
                                     BigDecimal competitionRate, BigDecimal winProbability) {
}
