package com.ssafy.a605.backend.domain.replay.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.replay.controller.api.GameClockApi;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 게임 시계 조회. 화면이 남은 시간을 그리는 근거이므로 운영에서도 열려 있다.
 *
 * <p><b>{@code 공개} 다</b> (ADR-0005). 공개 토픽 {@code /topic/event} 의 {@code game.phase} 가
 * 이미 {@code gameDate}·{@code gameMonth}·{@code tickSeq}·{@code phase} 를 전부 싣고 나가므로,
 * WS 를 여는 순간 REST 만 막는 것은 의미가 없다.
 *
 * <p>문서는 {@link GameClockApi} 가 갖는다. 관리자 조작은 {@link ReplayController} 이고,
 * 여기는 <b>조회 전용이며 응답도 좁다</b> —
 * 앵커·배속·시작 예약은 재생을 조작하는 값이라 ADMIN·개발 환경 밖으로 내보내지 않는다.
 */
@RestController
@ConditionalOnExpression("'${app.replay.mode:disabled}' != 'disabled'")
@RequiredArgsConstructor
public class GameClockController implements GameClockApi {

    private final ReplayService replay;

    @Override
    @GetMapping("/api/v1/game/clock")
    public ApiResponse<GameClockSnapshot> get() {
        return ApiResponse.ok(replay.clock());
    }
}
