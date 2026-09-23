package com.ssafy.a605.backend.domain.replay.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일반 사용자용 시계 ({@code GET /api/v1/game/clock}, {@code _common.md §7.3}).
 * {@code game.phase} 메시지의 payload 와 같은 모양이다.
 *
 * <p><b>{@link ReplaySnapshot} 과 일부러 다른 타입이다.</b> 저쪽은 앵커·배속·시작 예약처럼
 * 재생을 <b>조작</b>하는 데 필요한 값을 담고, 그것은 ADMIN 과 개발·테스트 환경에서만 볼 것이다.
 * 같은 DTO 를 두 경로가 공유하면 관리자 필드가 일반 사용자 응답으로, 그것도 운영에서 새어
 * 나간다 — 이 경로는 인증만 통과하면 누구나 읽는다.
 *
 * @param seasonDay           진행 중인 일차
 * @param phase               현재 구간
 * @param phaseEndsAt         다음 구간 전환 예정 실제 시각. 정지 중이면 {@code null}
 * @param serverTime          실제 서버 시각. 카운트다운의 기준점이다
 * @param tickSeq             공개 기준 틱. 최초 공개 전에는 {@code null}
 * @param gameDate            공개 기준 게임 거래일. 최초 공개 전에는 {@code null}
 * @param gameMonth           공개 기준 게임 월. 최초 공개 전에는 {@code null}
 * @param nextTickAt          다음 가격 공개 예정 실제 시각. 정지 중이거나 당일 가격이
 *                            끝났으면 {@code null} — 둘은 다른 상태다
 * @param feeFree             수수료 무료 구간 여부. 별도 Phase 가 아니라 플래그다
 * @param feeFreeEndsAt       무료 구간 종료 예정 실제 시각. 구간 밖에서는 {@code null}
 * @param leaderboardBlackout 랭킹 암전 여부
 * @param replayEpoch         재생 실행 세대
 */
public record GameClockSnapshot(short seasonDay, String phase, Instant phaseEndsAt, Instant serverTime,
        Long tickSeq, LocalDate gameDate, String gameMonth, Instant nextTickAt,
        boolean feeFree, Instant feeFreeEndsAt, boolean leaderboardBlackout, long replayEpoch) {

    /** 같은 트랜잭션에서 만든 관리자 스냅샷에서 일반 사용자에게 보일 값만 추린다. */
    public static GameClockSnapshot from(ReplaySnapshot snapshot) {
        return new GameClockSnapshot(snapshot.seasonDay(), snapshot.phase(), snapshot.phaseEndsAt(),
                snapshot.serverTime(), snapshot.tickSeq(), snapshot.gameDate(), snapshot.gameMonth(),
                snapshot.nextTickAt(), snapshot.feeFree(), snapshot.feeFreeEndsAt(),
                snapshot.leaderboardBlackout(), snapshot.replayEpoch());
    }
}
