package com.ssafy.a605.backend.domain.replay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 관리자용 시계. REST 응답과 {@code game.phase} 메시지가 공유한다.
 *
 * <p>앵커 일차를 따로 담지 않는다 — 앵커는 항상 현재 진행 일차이므로 {@code seasonDay} 와
 * 같은 값이고, 둘을 나란히 두면 다를 수 있다는 오해를 만든다.
 *
 * <p>일반 사용자용은 {@link GameClockSnapshot} 이며 앵커·배속·시작 예약이 없다.
 *
 * <p>{@code firstTickSeq} 는 이 시즌 첫 Tick 의 순번이다. <b>1 이 아닐 수 있다</b> — 시즌마다
 * 겹치지 않는 {@code tick_seq} 구간을 쓰기 때문이다. 재생 조작 화면이 "이 지점보다 앞선 Tick 이
 * 몇 개인가" 를 셀 때 기준으로 삼는다. 아직 틱이 적재되지 않았으면 {@code null} 이다.
 */
public record ReplaySnapshot(long seasonId, short seasonDay, BigDecimal offsetSec,
        BigDecimal anchorOffsetSec, Instant anchorAt, BigDecimal speed,
        String phase, Instant serverTime, Long tickSeq, LocalDate gameDate, String gameMonth,
        Instant nextTickAt, Instant phaseEndsAt, boolean feeFree, Instant feeFreeEndsAt,
        boolean leaderboardBlackout, long replayEpoch, Instant scheduledStartAt, boolean warping,
        Long firstTickSeq) { }
