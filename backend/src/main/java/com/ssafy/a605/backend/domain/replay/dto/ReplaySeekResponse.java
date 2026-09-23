package com.ssafy.a605.backend.domain.replay.dto;

/**
 * 지점 이동의 <b>즉시</b> 응답. 도착을 기다리지 않는다 — 도착은 관리자 화면의 주기적
 * 재조회가 관찰한다.
 *
 * @param targetSeasonDay 목표 일차
 * @param targetOffsetSec 목표 가상 초 위치
 * @param targetTickSeq   목표가 가격 Tick 이면 그 순번. 시간 경계면 {@code null}
 * @param reseeded        이 요청이 시즌을 초기화했는가. 뒤로 가는 이동에서만 참이다
 * @param clock           요청 직후의 시계. 아직 목표에 도달하지 않았을 수 있다
 */
public record ReplaySeekResponse(short targetSeasonDay, int targetOffsetSec, Long targetTickSeq,
        boolean reseeded, ReplaySnapshot clock) {
}
