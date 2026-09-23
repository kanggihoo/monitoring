package com.ssafy.a605.backend.domain.replay.dto;

import com.ssafy.a605.backend.domain.replay.entity.ReplayState;

/**
 * 시계 경계({@link ReplayState#BOUNDARIES}) 하나가 커밋된 뒤 발행하는 프로세스 내부 신호.
 * WS 의 {@code game.phase} 봉투와는 별개다 — 폐장 스냅샷 확정처럼 경계에 얹히는 업무가
 * 이것을 듣는다.
 *
 * <p><b>Phase 를 바꾸지 않는 경계도 온다</b> ({@link ReplayState#FEE_FREE_START} ·
 * {@code FEE_FREE_END} · {@code BLACKOUT_START}). 무료 타임 시작을 알림으로 남기려면 그
 * 순간에 커밋된 신호가 필요하고, 그것을 낼 자리가 여기뿐이다. 듣는 쪽이 전부 자기 경계를
 * 먼저 확인하므로(아래 판별자들) 다른 경계가 늘어도 하는 일이 없다.
 *
 * <p>ponytail: 이름은 {@code PhaseBoundaryEvent} 그대로 두었다 — 이제 Phase 경계만 싣지
 * 않으므로 {@code ClockBoundaryEvent} 가 맞지만, 이름을 바꾸면 듣는 클래스 다섯과 테스트
 * 셋이 함께 움직인다. 이 이벤트에 새 청자가 붙을 때 같이 바꾼다.
 *
 * @param seasonDay 그 경계가 속한 일차
 * @param offset    도달한 경계 좌표 ({@link ReplayState#BOUNDARIES} 중 하나)
 */
public record PhaseBoundaryEvent(short seasonDay, int offset) {

    /** 폐장(16:40). 이 순간의 순위가 {@code daily_settlements} 로 얼려진다. */
    public boolean isClose() {
        return offset == ReplayState.CLOSE;
    }

    /** 브리핑 진입(11:00). 전날의 확정이 풀리고 일간 기준값이 바뀌는 순간이다. */
    public boolean isBriefing() {
        return offset == ReplayState.BRIEFING;
    }

    /** 수수료 무료 타임 시작(12:00). Phase 가 바뀌지 않는 경계다 — TRADING 안의 플래그다. */
    public boolean isFeeFreeStart() {
        return offset == ReplayState.FEE_FREE_START;
    }

    /**
     * 정산 발표(17:00). 폐장에 얼린 것이 사용자에게 열리는 순간이며, 폐장에서 20 분 뒤다.
     *
     * <p>폐장에 걸어 둔 뒷일이 실패했다면 <b>여기가 마지막 만회 지점</b>이다 — 이 뒤로는
     * 그 일차에 아무 경계도 남지 않는다.
     */
    public boolean isAnnounce() {
        return offset == ReplayState.END;
    }
}
