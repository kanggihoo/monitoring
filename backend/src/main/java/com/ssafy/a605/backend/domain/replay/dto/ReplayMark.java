package com.ssafy.a605.backend.domain.replay.dto;

/**
 * 관리자가 고를 수 있는 이동 지점 하나 (Replay Mark).
 *
 * <p><b>서버가 시즌 편성에서 계산한다.</b> 월 → Tick 매핑과 "당일 마지막 가격 Tick"은 거래일
 * 달력에서 파생되므로 화면이 표를 복제하면 편성을 바꿀 때 두 곳이 갈라진다.
 *
 * @param key       이동 요청에 그대로 실리는 식별자
 * @param kind      지점의 갈래. 화면이 종류별로 걸러 보는 데 쓴다
 * @param label     화면 표시 이름
 * @param seasonDay 그 지점이 속한 일차
 * @param offsetSec 그 일차의 가상 초 위치
 * @param tickSeq   가격 Tick 인 경우의 틱 순번. 시간 경계는 {@code null}
 * @param passed    현재 진행 기준으로 이미 지나갔는가
 */
public record ReplayMark(String key, ReplayMarkKind kind, String label, short seasonDay, int offsetSec,
        Long tickSeq, boolean passed) {
}
