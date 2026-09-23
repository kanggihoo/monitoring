package com.ssafy.a605.backend.domain.order.listener;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderFilledEvent;
import com.ssafy.a605.backend.domain.order.service.OrderFillService;
import com.ssafy.a605.backend.domain.order.service.OrderFillService.Filled;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종가 틱이 공개되면 그 종목의 미체결 예약을 판정하고 체결된 사람에게 알린다
 * (기획서 6.4, {@code ord.md} §체결 판정 규칙 · §실시간).
 *
 * <p><b>평가액 스윕보다 먼저 돌아야 한다.</b> 순서가 뒤집히면 틱 137 의
 * {@code account.valuation} 이 틱 137 의 체결을 빠뜨린 값으로 나가고, 프론트는 같은
 * {@code tickSeq} 의 조각을 모아 한 번에 그리므로 ({@code _common.md} §10) 그 화면은
 * "체결됐는데 잔고가 그대로"가 된다. 랭킹 재계산도 그 평가 결과를 물려받으니
 * ({@code AccountsValuedEvent}) 한 틱의 순위가 체결 이전 상태로 확정된다.
 *
 * <p>그래서 {@code @Order} 로 우선순위를 <b>명시</b>한다. 어노테이션이 없는 리스너는 최하위로
 * 취급되므로 지금은 값이 없어도 결과가 같지만, 그 순서가 남의 파일에 어노테이션이 없다는
 * 사실에 의존해서는 안 된다 — 계좌 쪽에도 같은 이유로 값을 적어 두었다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 틱 이벤트는 동기 발행이라 여기서 던지면
 * {@code ReplayService.progress()} 까지 올라가 재생 폴러가 멈춘다. 그런데 틱은 이미 커밋되고
 * 브로드캐스트된 뒤이므로 그 예외는 체결 실패가 아니라 <b>게임 진행 정지</b>가 된다. 같은
 * 판단을 {@code AccountValuationListener} 가 하고 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFillListener {

    /**
     * 틱 리스너 사이의 우선순위. {@code AccountValuationListener.PRECEDENCE} 보다 작은 값이라
     * 평가 스윕보다 먼저 돈다 — 값 자체는 임의이고 <b>순서만</b> 계약이다. 상수를 서로
     * 참조하지 않는 것은 리스너 클래스를 도메인 간에 끌어오지 않기 위해서다.
     *
     * <p><b>{@code @Order} 는 아래 메서드에 붙인다.</b> 클래스에 붙이면 {@code @EventListener}
     * 의 순서에 반영되지 않아 둘 다 최하위가 되고, 빈 등록 순서(패키지명 {@code account} <
     * {@code order})로 밀려 <b>계약과 정반대</b>로 돈다 (S15P21A605-191).
     */
    public static final int PRECEDENCE = 100;

    private static final String TYPE = "order.filled";

    private final OrderFillService fills;
    private final GameTickCatalog gameTicks;
    private final ReplayService replay;
    private final RealtimePublisher publisher;
    private final ApplicationEventPublisher events;

    /**
     * 공개된 틱으로 미체결 예약을 판정하고 체결된 항목을 개인 메시지로 보낸다.
     *
     * <p><b>계좌마다 트랜잭션이 나뉜다.</b> 후보를 먼저 모으고 계좌 단위로 확정하므로, 한
     * 계좌의 판정이 깨져도 나머지가 체결된다 — 스윕을 한 트랜잭션으로 묶으면 잘못된 예약
     * 하나가 매 틱 전원의 체결을 막는다.
     *
     * <p><b>무료 타임 여부를 한 번만 읽는다.</b> 무료 요율은 체결 시점 기준인데 (기획서 6.7.3)
     * 계좌마다 시계를 물으면 한 틱의 스윕 안에서 앞사람은 유료, 뒷사람은 무료가 되는 순간이
     * 생긴다. 후보가 없으면 시계를 아예 읽지 않는다.
     *
     * <p>시계를 다시 읽어도 되는 것은 폴러가 단일 스레드이고 항목을 하나씩 처리하기
     * 때문이다 ({@code @Scheduled(fixedDelay)}) — 이 리스너가 도는 동안 경계 커서가 넘어갈 수
     * 없어, 틱이 공개된 시점의 무료 구간 여부가 그대로 보인다.
     *
     * @param event 커밋된 틱. 재생 도메인이 발행한다
     */
    @EventListener
    @Order(PRECEDENCE)
    public void onTickPublished(TickPublishedEvent event) {
        try {
            GameTick tick = gameTicks.find(event.tickSeq()).orElse(null);
            if (tick == null) {
                log.error("Published tick {} is absent from game_ticks; skipped order fills",
                        event.tickSeq());
                return;
            }
            long open = fills.openOrders();
            long judgeStarted = System.nanoTime();
            List<Long> candidates = fills.accountsWithFills(tick);
            long judgeMs = (System.nanoTime() - judgeStarted) / 1_000_000;
            long fillStarted = System.nanoTime();
            int failed = 0;
            if (!candidates.isEmpty()) {
                boolean feeFree = replay.clock().feeFree();
                for (Long accountId : candidates) {
                    if (!fillAndNotify(accountId, tick, feeFree)) failed++;
                }
            }
            // 부하 측정용. 후보가 없어도 남긴다 — 미체결 600건을 판정만 하는 비용이 judgeMs 다.
            log.info("Order fill sweep: tickSeq={} openOrders={} candidates={} judgeMs={} fillMs={} filled={} failed={}",
                    tick.getTickSeq(), open, candidates.size(), judgeMs,
                    (System.nanoTime() - fillStarted) / 1_000_000, candidates.size() - failed, failed);
        } catch (RuntimeException e) {
            log.error("Order fill sweep failed at tick {}; replay continues", event.tickSeq(), e);
        }
    }

    /**
     * 한 계좌를 체결시키고 커밋된 뒤 보낸다.
     *
     * <p>실패를 그 계좌 선에서 끊는 이유는 순서 때문이다 — 후보 순서가 안정적이라 루프를
     * 중단하면 매 틱 같은 뒤쪽 계좌들만 반복해서 굶는다. {@code AccountValuationListener} 가
     * 같은 이유로 사람 단위로 끊는다.
     */
    private boolean fillAndNotify(Long accountId, GameTick tick, boolean feeFree) {
        Filled filled;
        try {
            filled = fills.fillFor(accountId, tick, feeFree);
        } catch (RuntimeException e) {
            log.error("Order fill failed for account {} at tick {}", accountId, tick.getTickSeq(), e);
            return false;
        }
        // 목록이 비면 후보가 그 사이 전부 취소된 것이다. 잠금 뒤 재판정이 걸러냈고 보낼 것도
        // 없으므로, 그 경우를 따로 분기하지 않는다.
        for (ExecutionResponse execution : filled.executions()) {
            send(filled.userId(), execution, tick);
        }
        // 체결은 이미 커밋됐다. 알림 쓰기가 이 트랜잭션에 합류하면 알림 실패가 체결을
        // 롤백시키므로, 신호만 보내고 기록은 알림 도메인이 자기 트랜잭션에서 남긴다.
        events.publishEvent(new OrderFilledEvent(accountId, filled.executions(), tick.getTickSeq()));
        return true;
    }

    /**
     * 체결 한 건을 보낸다. <b>체결마다 한 통</b>이다 — 페이로드가 체결 내역 항목과 같은 형태라야
     * 프론트가 목록에 그대로 이어 붙일 수 있고 ({@code ord.md} §실시간), 여러 건을 배열로 묶으면
     * 그 형태가 깨진다.
     *
     * <p>같은 틱의 {@code market.prices} 와 같은 {@code tickSeq} 를 싣는다
     * ({@code _common.md} §10).
     */
    private void send(Long userId, ExecutionResponse execution, GameTick tick) {
        try {
            publisher.sendToUser(userId, TYPE, tick.getTickSeq(), tick.getGameDate(), execution);
        } catch (RuntimeException e) {
            log.error("Failed to send {} to user {} at tick {}", TYPE, userId, tick.getTickSeq(), e);
        }
    }
}
