package com.ssafy.a605.backend.domain.market.service;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 공개 유니버스를 틱당 한 번 조회해 공유한다. 브라우저별 재조회를 만들지 않는다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstrumentRealtimeListener {
    private final InstrumentQueryService instruments;
    private final RealtimePublisher publisher;

    /** 공시·상장 처리 뒤의 공개 상태를 전달한다. */
    @EventListener
    @Order(40)
    public void onTick(TickPublishedEvent event) {
        try {
            publisher.broadcast(WsDestination.TICK, "market.instruments", event.tickSeq(), event.gameDate(),
                    instruments.publicListAt(event.tickSeq(), event.gameDate()));
        } catch (RuntimeException error) {
            log.error("Instrument broadcast failed at tick {}", event.tickSeq(), error);
        }
    }
}
