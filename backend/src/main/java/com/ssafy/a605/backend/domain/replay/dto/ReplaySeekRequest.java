package com.ssafy.a605.backend.domain.replay.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * 지점 이동 요청. 목표는 <b>둘 중 하나</b>로만 지정한다 — 둘을 함께 보내면 같은 목표를 두
 * 좌표계로 가리키므로 어느 쪽이 이기는지 정할 근거가 없어 거절한다.
 *
 * <p>초 단위 미세 이동은 받지 않는다. Tick 사이에는 가격도 체결 판정도 공모 발동도 없다.
 *
 * @param markKey     이동 지점 식별자. {@code GET /api/v1/admin/replay/marks} 의 {@code key}
 * @param ticksBefore 그 지점보다 몇 Tick 앞에 설지. 생략하면 3. {@code markKey} 와만 쓴다.
 *                    시간 경계 지점에서는 "그 경계보다 앞선 Tick 들에서 N 개 뒤로"다
 * @param tickSeq     그 Tick 으로 직접 이동한다. 자동 테스트의 결정론을 위한 입구다
 */
public record ReplaySeekRequest(
        String markKey,

        @PositiveOrZero(message = "Tick 수는 0 이상이어야 합니다.")
        Integer ticksBefore,

        Long tickSeq
) {

    /** {@code ticksBefore} 를 생략했을 때의 값. 이벤트가 일어나는 것을 세팅을 마친 뒤 보기 위한 여유다. */
    public static final int DEFAULT_TICKS_BEFORE = 3;
}
