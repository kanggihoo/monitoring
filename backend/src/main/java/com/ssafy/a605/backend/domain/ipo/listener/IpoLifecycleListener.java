package com.ssafy.a605.backend.domain.ipo.listener;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.ipo.dto.IpoAnnouncedResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoCompetitionResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoListedResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingResponse;
import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService;
import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService.Awarded;
import com.ssafy.a605.backend.domain.ipo.dto.IpoDrawnEvent;
import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService.Drawn;
import com.ssafy.a605.backend.domain.ipo.service.IpoListingService;
import com.ssafy.a605.backend.domain.ipo.service.IpoListingService.Listed;
import com.ssafy.a605.backend.domain.ipo.service.IpoRealtimeService;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공모의 한 틱 (기획서 8장, {@code ipo.md} §실시간). 공시·경쟁률·추첨·상장이 모두 종가 틱에
 * 붙어 있어 한 리스너가 순서대로 처리한다.
 *
 * <p><b>넷을 한 자리에 둔 것은 순서 때문이다.</b> 추첨이 상장보다 앞서야 마감과 상장 틱을 함께
 * 놓쳤을 때 한 틱에서 둘 다 복구된다 — 리스너를 나누면 그 순서를 {@code @Order} 로 선언해야
 * 하고, 그것은 두 클래스에 흩어진 암묵적 계약이 된다.
 *
 * <p><b>DB 를 바꾸는 일과 보내는 일을 나눈다.</b> 서비스가 자기 트랜잭션에서 커밋한 뒤 리스너가
 * 보낸다 — 전송이 실패해도 추첨과 전환은 남아야 하고, 반대로 전송이 커밋을 붙들고 있으면
 * 잠금이 그만큼 길어진다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 틱 이벤트는 동기 발행이라 여기서 던지면
 * {@code ReplayService.progress()} 까지 올라가 재생 폴러가 멈춘다. 틱은 이미 커밋된 뒤이므로
 * 그 예외는 IPO 실패가 아니라 <b>게임 진행 정지</b>가 된다. 단계마다 끊어, 공시 실패가 추첨을
 * 막지 않는다.
 *
 * <p><b>{@code @Order} 를 두지 않았다.</b> 이 틱에는 체결 판정과 평가 스윕이 함께 붙어 있지만
 * 순서 제약이 없다 — 추첨과 상장 전환은 그 계좌의 총평가액을 바꾸지 않는다. 추첨은 잠금만
 * 풀고, 전환은 잠긴 현금을 같은 값의 자산으로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpoLifecycleListener {

    private static final String DISCLOSED = "ipo.disclosed";
    private static final String COMPETITION = "ipo.competition";
    private static final String ANNOUNCED = "ipo.announced";
    private static final String RESULT = "ipo.result";
    private static final String LISTED = "ipo.listed";

    private final IpoRealtimeService realtime;
    private final IpoDrawService draws;
    private final IpoListingService listings;
    private final RealtimePublisher publisher;
    private final ApplicationEventPublisher events;

    /** 실제 시각. 게임 시계와 헷갈리지 않게 이름을 나눠 뒀다. */
    private final Clock wallClock;

    /**
     * 이번 틱의 공모 일을 순서대로 한다.
     *
     * <p><b>시각을 한 번만 읽어 그 틱의 모든 사건에 쓴다.</b> 같은 틱에 추첨과 상장이 함께
     * 일어나는 편성은 없지만, 읽는 자리를 나누면 같은 틱의 사건들이 몇 밀리초씩 갈린다.
     *
     * @param event 커밋된 틱. 재생 도메인이 발행한다
     */
    @EventListener
    public void onTickPublished(TickPublishedEvent event) {
        Instant at = Instant.now(wallClock);
        disclose(event);
        publishCompetition(event);
        draw(event, at);
        list(event);
    }

    /** 공시 틱에 종목명이 처음 공개된다 (기획서 8.1.1). 프론트는 이 신호로 유니버스를 재조회한다. */
    private void disclose(TickPublishedEvent event) {
        try {
            for (IpoOfferingResponse offering : realtime.disclosedAt(event.tickSeq())) {
                send(WsDestination.EVENT, DISCLOSED, event, offering);
            }
        } catch (RuntimeException e) {
            log.error("IPO disclosure broadcast failed at tick {}", event.tickSeq(), e);
        }
    }

    /** 청약 창이 열린 동안 매 틱 (기획서 8.3.1). 창이 없으면 아무것도 보내지 않는다. */
    private void publishCompetition(TickPublishedEvent event) {
        try {
            for (IpoCompetitionResponse competition : realtime.openCompetition(event.tickSeq())) {
                send(WsDestination.TICK, COMPETITION, event, competition);
            }
        } catch (RuntimeException e) {
            log.error("IPO competition broadcast failed at tick {}", event.tickSeq(), e);
        }
    }

    /**
     * 마감이 지난 공모를 뽑고 알린다. 전체 공지는 인원만, 당락은 그 사람에게만 간다 (8.4.3).
     */
    private void draw(TickPublishedEvent event, Instant at) {
        try {
            for (Long offeringId : draws.offeringsToDraw(event.tickSeq())) {
                drawOne(offeringId, at, event);
            }
        } catch (RuntimeException e) {
            log.error("IPO draw sweep failed at tick {}; replay continues", event.tickSeq(), e);
        }
    }

    /**
     * 한 공모를 뽑는다. 실패를 그 공모 선에서 끊는 이유는 순서 때문이다 — 후보 순서가 안정적이라
     * 루프를 중단하면 마감이 늦은 공모만 반복해서 밀린다.
     */
    private void drawOne(Long offeringId, Instant at, TickPublishedEvent event) {
        Drawn drawn;
        try {
            drawn = draws.draw(offeringId, at);
        } catch (RuntimeException e) {
            log.error("IPO draw failed for offering {} at tick {}", offeringId, event.tickSeq(), e);
            return;
        }
        if (drawn == null) {
            return;
        }
        log.info("IPO offering {} drawn at tick {}: {} winners", offeringId, event.tickSeq(),
                drawn.winnerCount());
        send(WsDestination.EVENT, ANNOUNCED, event,
                new IpoAnnouncedResponse(drawn.offeringId(), drawn.winnerCount()));
        for (Awarded awarded : drawn.awarded()) {
            var result = awarded.result();
            sendToUser(awarded.userId(), event, java.util.Map.of(
                    "offeringId", drawn.offeringId(), "won", result.won(),
                    "allocatedQuantity", result.allocatedQuantity(), "allocatedAmount", result.allocatedAmount(),
                    "refundedAmount", result.refundedAmount(), "drawnAt", result.drawnAt()));
        }
        // 추첨은 이미 커밋됐다. 알림 실패가 추첨을 롤백시키면 그 공모가 다시 뽑혀
        // 당첨자가 바뀐다.
        events.publishEvent(new IpoDrawnEvent(drawn.offeringId(), drawn.awarded()));
    }

    /** 상장 틱에 배정 물량이 포지션이 된다 (기획서 8.5.2). 유니버스가 늘어나는 순간이다. */
    private void list(TickPublishedEvent event) {
        try {
            for (Long offeringId : listings.offeringsToList(event.tickSeq())) {
                listOne(offeringId, event);
            }
        } catch (RuntimeException e) {
            log.error("IPO listing sweep failed at tick {}; replay continues", event.tickSeq(), e);
        }
    }

    private void listOne(Long offeringId, TickPublishedEvent event) {
        Listed listed;
        try {
            listed = listings.list(offeringId);
        } catch (RuntimeException e) {
            log.error("IPO listing failed for offering {} at tick {}", offeringId, event.tickSeq(), e);
            return;
        }
        if (listed == null) {
            return;
        }
        log.info("IPO offering {} listed at tick {} as instrument {}", offeringId, event.tickSeq(),
                listed.instrumentId());
        send(WsDestination.EVENT, LISTED, event,
                new IpoListedResponse(listed.offeringId(), listed.instrumentId()));
    }

    /**
     * 전송 실패를 그 메시지 선에서 끝낸다. 브로커가 흔들려도 이미 커밋된 추첨·전환을 되돌릴
     * 방법은 없고, 다음 조회가 같은 사실을 알려준다.
     */
    private void send(WsDestination destination, String type, TickPublishedEvent event,
                      Object payload) {
        try {
            publisher.broadcast(destination, type, event.tickSeq(), gameDate(event), payload);
        } catch (RuntimeException e) {
            log.error("Failed to broadcast {} at tick {}", type, event.tickSeq(), e);
        }
    }

    private void sendToUser(Long userId, TickPublishedEvent event, Object payload) {
        try {
            publisher.sendToUser(userId, RESULT, event.tickSeq(), gameDate(event), payload);
        } catch (RuntimeException e) {
            log.error("Failed to send {} to user {}", RESULT, userId, e);
        }
    }

    private static LocalDate gameDate(TickPublishedEvent event) {
        return event.gameDate();
    }
}
