package com.ssafy.a605.backend.domain.replay.controller.api;

import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 게임 시계 조회. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p>로그인만 필요하다 — 화면이 남은 시간과 현재 구간을 그리는 근거이므로 운영에서도 열려 있다.
 * 그래서 <b>응답이 좁다</b>: 앵커·배속·시작 예약은 재생을 조작하는 값이라 여기로 내보내지 않고
 * {@link ReplayApi} 에만 있다.
 *
 * <p>같은 값이 {@code game.phase} WS 메시지의 payload 로도 온다. 화면은 그쪽을 받으면 이 조회를
 * 다시 하지 않아도 되고, {@code game.clock} 알림을 받으면 여기로 재조회한다.
 */
@Tag(name = "GameClock", description = "게임 시계 API")
public interface GameClockApi {

    @Operation(
            summary = "게임 시계 조회",
            description = "현재 일차·구간과 다음 전환·다음 가격 공개 예정 시각을 돌려준다. "
                    + "카운트다운은 남은 초를 세어 내려가는 것이 아니라 serverTime 과 예정 시각의 차이로 "
                    + "매번 다시 계산한다 — 백그라운드 탭에서 타이머가 스로틀되면 세어 내린 값이 거짓이 된다. "
                    + "최초 공개 전에는 tickSeq·gameDate·gameMonth 가 null 이다. "
                    + "nextTickAt 은 정지 중과 당일 가격 종료 모두 null 이므로 둘을 같은 뜻으로 읽지 않는다. "
                    + "feeFree·leaderboardBlackout 은 별도 구간이 아니라 TRADING 안의 플래그다."
    )
    ApiResponse<GameClockSnapshot> get();
}
