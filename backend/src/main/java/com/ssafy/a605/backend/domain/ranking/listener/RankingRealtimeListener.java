package com.ssafy.a605.backend.domain.ranking.listener;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.ranking.dto.AwardsAnnouncedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.MyRankingResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingRecalculatedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot.Entry;
import com.ssafy.a605.backend.domain.ranking.service.AwardService;
import com.ssafy.a605.backend.domain.ranking.service.AwardService.WinnerUsers;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 랭킹 실시간 메시지 4종 ({@code rank.md} §실시간, {@code ws.md}).
 *
 * <p><b>리더보드 전체 목록은 밀지 않는다.</b> 수천 행을 30초 남짓마다 보내는 대신 개인 순위
 * {@code ranking.me} 만 보내고, 클라이언트가 보고 있는 페이지를 REST 로 다시 읽는다.
 * <b>{@code ranking.awarded} 도 같은 패턴이다</b> — 공개 토픽에 개인 정보를 싣지 않기 위해
 * 신호만 보낸다 (ADR-0005 결정 5).
 *
 * <p><b>암전 중에는 순위를 싣지 않는다.</b> {@code ranking.me} 는 REST 와 같은 규칙으로 순위·등락이
 * {@code null} 이고, {@code ranking.top10Changed} 는 빈 객체다 — 무엇이든 실으면 암전이 뚫린다
 * (기획서 11.3.3).
 *
 * <p>전부 커밋·재계산이 끝난 뒤의 신호를 듣고, 전송 실패는 여기서 끝낸다. 메시지 하나를 못 보내는
 * 것과 재생이 멈추는 것은 비교할 수 있는 손해가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRealtimeListener {

    static final String ME = "ranking.me";
    static final String TOP10_CHANGED = "ranking.top10Changed";
    static final String FINALIZED = "ranking.finalized";
    static final String AWARDED = "ranking.awarded";

    private static final int TOP = 10;

    private final ReplayService replay;
    private final AwardService awards;
    private final RealtimePublisher publisher;
    private final ApplicationEventPublisher events;

    /**
     * 순위가 교체되면 순위에 든 모든 사용자에게 자기 순위를 보내고, 암전 중 TOP 10 구성이 바뀌었으면
     * 변동 사실만 알린다.
     *
     * @param event 이 틱의 순위와 직전 순위
     */
    @EventListener
    public void on(RankingRecalculatedEvent event) {
        RankingSnapshot current = event.current();
        boolean blackout;
        try {
            blackout = replay.clock().leaderboardBlackout();
        } catch (RuntimeException e) {
            log.error("Ranking realtime skipped at tick {}: clock unavailable", current.tickSeq(), e);
            return;
        }
        for (Entry entry : current.bySeasonRank()) {
            send(entry, current, blackout);
        }
        if (blackout && event.previous() != null && topChanged(event.previous(), current)) {
            broadcast(TOP10_CHANGED, current.tickSeq(), current.gameDate(), Map.of());
        }
    }

    /**
     * 폐장 스냅샷이 커밋되면 확정 사실을 알린다.
     *
     * @param event 커밋된 폐장 스냅샷
     */
    @EventListener
    public void on(DailySettlementFinalizedEvent event) {
        broadcast(FINALIZED, null, null, Map.of("seasonDay", event.seasonDay(), "finalized", true));
    }

    /**
     * 정산 발표(17:00) 경계가 커밋되면 시상 결과를 보내고, 알림 도메인을 위한 훅을 남긴다.
     *
     * @param event 커밋된 Phase 경계
     */
    @EventListener
    public void on(PhaseBoundaryEvent event) {
        if (event.offset() != ReplayState.END) {
            return;
        }
        try {
            // 신호만 보내고 상세는 REST 재조회다 (ADR-0005 결정 5) — `/topic/event` 는 미인증도
            // 구독하므로 수상자의 닉네임·아바타·수익률을 여기 실으면 그대로 브로드캐스트된다.
            // `ranking.top10Changed` 가 빈 객체를 보내는 것과 같은 패턴이다.
            broadcast(AWARDED, null, null, Map.of());
            WinnerUsers winners = awards.winnerUsers(event.seasonDay());
            events.publishEvent(new AwardsAnnouncedEvent(event.seasonDay(),
                    winners.dailyWinners(), winners.finalWinnerUserIds()));
        } catch (RuntimeException e) {
            log.error("Awards announcement failed for season day {}; replay continues", event.seasonDay(), e);
        }
    }

    /** 실패를 그 사람 선에서 끝낸다 — 루프를 중단하면 매 틱 같은 뒤쪽 사용자들만 굶는다. */
    private void send(Entry entry, RankingSnapshot snapshot, boolean blackout) {
        try {
            publisher.sendToUser(entry.userId(), ME, snapshot.tickSeq(), snapshot.gameDate(),
                    MyRankingResponse.of(snapshot, entry, blackout));
        } catch (RuntimeException e) {
            log.error("Failed to send {} to user {} at tick {}", ME, entry.userId(), snapshot.tickSeq(), e);
        }
    }

    private void broadcast(String type, Long tickSeq, java.time.LocalDate gameDate, Object payload) {
        try {
            publisher.broadcast(WsDestination.EVENT, type, tickSeq, gameDate, payload);
        } catch (RuntimeException e) {
            log.error("Failed to broadcast {}", type, e);
        }
    }

    /** 두 트랙 중 하나라도 TOP 10 의 구성원이 달라졌는가. 순서 변화는 구성 변화가 아니다. */
    private static boolean topChanged(RankingSnapshot previous, RankingSnapshot current) {
        return !top(previous.bySeasonRank()).equals(top(current.bySeasonRank()))
                || !top(previous.byDailyRank()).equals(top(current.byDailyRank()));
    }

    private static Set<Long> top(List<Entry> ordered) {
        return ordered.stream().limit(TOP).map(Entry::accountId).collect(Collectors.toSet());
    }
}
