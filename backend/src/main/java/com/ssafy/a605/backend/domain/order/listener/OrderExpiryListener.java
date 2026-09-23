package com.ssafy.a605.backend.domain.order.listener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.order.dto.ExpiredOrdersResponse;
import com.ssafy.a605.backend.domain.order.dto.OrdersExpiredEvent;
import com.ssafy.a605.backend.domain.order.service.OrderExpiryService;
import com.ssafy.a605.backend.domain.order.service.OrderExpiryService.Expired;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 폐장에 미체결 예약을 소멸시키고 주인에게 알린다 (기획서 6.2.4, {@code ord.md} §실시간).
 *
 * <p><b>{@code order.expired} 의 {@code tickSeq} 는 {@code null} 이다.</b> 폐장(16:40)은 틱
 * 경계와 일치하지 않아 자동 취소가 어떤 틱에도 속하지 않는다. 마지막 틱 번호를 억지로 붙이면
 * <i>"그 틱에서 체결됐다"</i> 와 구분이 사라져 복기가 폐장 취소를 그 틱의 사건으로 그린다.
 * 시점은 payload 의 {@code occurredAt} 이 담는다.
 *
 * <p><b>{@code @Order} 를 두지 않았다.</b> 이 경계에는 폐장 스냅샷 확정과 랭킹이 함께 붙어
 * 있지만 순서 제약이 없다 — 취소는 <b>잠금만</b> 풀고 {@code krwCash} 를 건드리지 않는데,
 * 총평가액은 잠금을 포함한 총 현금으로 계산되므로 ({@code AccountValuations.totalValuationKrw})
 * 확정되는 값이 취소 전후로 같다. 체결 판정이 평가 스윕보다 <b>반드시</b> 앞서야 했던 것과
 * 다른 상황이라, 없는 제약을 어노테이션으로 선언해 두면 다음 사람이 있는 줄 안다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 경계 이벤트는 동기 발행이라 여기서 던지면
 * {@code ReplayService.progress()} 까지 올라가 재생 폴러가 멈춘다. 경계는 이미 커밋된
 * 뒤이므로 그 예외는 취소 실패가 아니라 <b>게임 진행 정지</b>가 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryListener {

    private static final String TYPE = "order.expired";

    private final OrderExpiryService expiries;
    private final RealtimePublisher publisher;
    private final ApplicationEventPublisher events;

    /** 실제 시각. 게임 시계와 헷갈리지 않게 이름을 나눠 뒀다. */
    private final Clock wallClock;

    /**
     * 폐장 경계에 미체결 예약을 전부 닫는다.
     *
     * <p><b>시각을 한 번만 읽어 전원에게 같은 값을 쓴다.</b> 계좌마다 읽으면 같은 폐장에
     * 사라진 예약들의 {@code closedAt} 이 몇 밀리초씩 갈리고, 복기가 그것을 서로 다른 사건으로
     * 그린다. 폐장은 한 순간이다.
     *
     * @param event 커밋된 phase 경계. 재생 도메인이 발행한다
     */
    @EventListener
    public void onPhaseBoundary(PhaseBoundaryEvent event) {
        if (!event.isClose()) {
            return;
        }
        try {
            List<Long> candidates = expiries.accountsWithOpenOrders();
            if (candidates.isEmpty()) {
                return;
            }
            Instant at = Instant.now(wallClock);
            for (Long accountId : candidates) {
                expireAndNotify(accountId, at);
            }
        } catch (RuntimeException e) {
            log.error("Order expiry sweep failed at close of day {}; replay continues",
                    event.seasonDay(), e);
        }
    }

    /**
     * 한 계좌를 닫고 커밋된 뒤 보낸다.
     *
     * <p>실패를 그 계좌 선에서 끊는 이유는 순서 때문이다 — 후보 순서가 안정적이라 루프를
     * 중단하면 매일 같은 뒤쪽 계좌들만 잠금을 못 돌려받는다.
     */
    private void expireAndNotify(Long accountId, Instant at) {
        Expired expired;
        try {
            expired = expiries.expireFor(accountId, at);
        } catch (RuntimeException e) {
            log.error("Order expiry failed for account {}", accountId, e);
            return;
        }
        if (expired.orders().isEmpty()) {
            return;
        }
        send(expired, at);
        // 취소는 이미 커밋됐다. 알림 실패가 잠금 반환을 롤백시키면 안 된다.
        events.publishEvent(new OrdersExpiredEvent(accountId, expired.orders()));
    }

    /**
     * 한 사람에게 한 통을 보낸다. <b>사라진 예약을 모아 한 통</b>인 것은 폐장 취소가 한 순간에
     * 일어난 하나의 사건이기 때문이다 — 다섯 건을 다섯 통으로 알리면 사용자에게는 다섯 번의
     * 사고로 보인다. 체결이 건마다 한 통인 것과 갈리는 지점이다.
     */
    private void send(Expired expired, Instant at) {
        try {
            publisher.sendToUser(expired.userId(), TYPE, null, null,
                    new ExpiredOrdersResponse(at, expired.orders()));
        } catch (RuntimeException e) {
            log.error("Failed to send {} to user {}", TYPE, expired.userId(), e);
        }
    }
}
