package com.ssafy.a605.backend.domain.account.listener;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.account.dto.AccountValuation;
import com.ssafy.a605.backend.domain.account.dto.AccountsValuedEvent;
import com.ssafy.a605.backend.domain.account.service.AccountValuationService;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종가 틱이 공개되면 각 사용자에게 자기 계좌 평가액을 보낸다 ({@code acc.md} §실시간).
 *
 * <p><b>이벤트가 준 틱을 그대로 쓴다.</b> 시계를 다시 묻지 않는 이유는 그 사이에 틱이 넘어갈 수
 * 있기 때문이다 — 그러면 이 메시지의 {@code tickSeq} 가 같은 틱의 {@code market.prices} 와
 * 달라지고, 프론트는 137번 조각이 다 모였을 때 한 번에 렌더링한다는 규칙을 지킬 수 없다
 * ({@code _common.md} §10). 그 화면이 곧 "주가는 올랐는데 내 평가액은 그대로"다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 틱 이벤트는 동기 발행이라 여기서 던지면
 * {@code ReplayService.progress()} 까지 올라가 재생 폴러가 멈춘다. 그런데 틱은 <b>이미 커밋되고
 * 브로드캐스트된 뒤</b>이므로, 그 예외는 갱신 실패가 아니라 게임 진행 정지가 된다. 평가액 한
 * 번을 못 보내는 것과 재생이 멈추는 것은 비교할 수 있는 손해가 아니다.
 *
 * <p><b>체결 판정보다 뒤에 돈다.</b> 그러지 않으면 틱 137 의 이 메시지가 틱 137 의 체결을
 * 빠뜨린 값으로 나가고, 프론트는 같은 {@code tickSeq} 의 조각을 모아 한 번에 그리므로 그
 * 화면이 "체결됐는데 잔고가 그대로"가 된다. {@code AccountsValuedEvent} 를 타고 랭킹까지 그
 * 값을 물려받으므로 한 틱의 순위도 체결 이전 상태로 확정된다. 순서를 <b>명시</b>하는 이유는
 * 어노테이션이 없을 때의 최하위 취급에 기대지 않기 위해서다 — 남의 파일에 어노테이션이 없다는
 * 사실은 계약이 아니다.
 *
 * <p>포지션은 매 틱 밀지 않는다. 28종 중 보유는 소수이지만 페이로드가 계좌 요약보다 크고,
 * 프론트가 브로드캐스트된 시세와 보유 수량으로 다시 그릴 수 있다 ({@code acc.md} §실시간).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountValuationListener {

    /**
     * 틱 리스너 사이의 우선순위. {@code OrderFillListener} 보다 큰 값이라 체결 뒤에 돈다 —
     * 값 자체는 임의이고 <b>순서만</b> 계약이다. 상수를 서로 참조하지 않는 것은 리스너 클래스를
     * 도메인 간에 끌어오지 않기 위해서다.
     *
     * <p><b>{@code @Order} 는 아래 메서드에 붙인다.</b> 클래스에 붙이면 {@code @EventListener}
     * 의 순서에 반영되지 않는다 (S15P21A605-191).
     */
    public static final int PRECEDENCE = 200;

    private static final String TYPE = "account.valuation";

    private final AccountValuationService valuations;
    private final GameTickCatalog gameTicks;
    private final RealtimePublisher publisher;
    private final ApplicationEventPublisher events;

    /**
     * 공개된 틱으로 전 계좌를 평가해 개인 메시지로 보낸다.
     *
     * @param event 커밋된 틱. 재생 도메인이 발행한다
     */
    @EventListener
    @Order(PRECEDENCE)
    public void onTickPublished(TickPublishedEvent event) {
        try {
            GameTick tick = gameTicks.find(event.tickSeq()).orElse(null);
            if (tick == null) {
                log.error("Published tick {} is absent from game_ticks; skipped account valuation",
                        event.tickSeq());
                return;
            }
            long valueStarted = System.nanoTime();
            List<AccountValuation> valued = valuations.valueAll(tick);
            long valueMs = (System.nanoTime() - valueStarted) / 1_000_000;
            // 같은 순회에 얹히는 쪽(랭킹)에 결과를 넘긴다. 각자 전 계좌를 다시 돌지 않는다.
            long downstreamStarted = System.nanoTime();
            events.publishEvent(new AccountsValuedEvent(tick.getTickSeq(), tick.getGameDate(),
                    tick.getSeasonDay(), valued));
            long downstreamMs = (System.nanoTime() - downstreamStarted) / 1_000_000;
            int sendFailed = 0;
            for (AccountValuation valuation : valued) {
                if (!send(valuation, tick)) sendFailed++;
            }
            boolean recorded = record(valued, tick);
            // 부하 측정용. 평가·랭킹(downstream)·전송·낙폭 기록이 이 틱에서 실제로 성공했는지를 남긴다 —
            // 이 리스너는 실패해도 재생을 멈추지 않으므로, 복구 뒤 업무 성공 여부는 이 줄로만 확인할 수 있다.
            log.info("Account valuation: tickSeq={} accounts={} valueMs={} downstreamMs={} sendFailed={} drawdownRecorded={}",
                    tick.getTickSeq(), valued.size(), valueMs, downstreamMs, sendFailed, recorded);
        } catch (RuntimeException e) {
            log.error("Account valuation failed at tick {}; replay continues", event.tickSeq(), e);
        }
    }

    /**
     * 그 틱의 평가액을 계좌의 최고점·낙폭에 남긴다. 시즌 리포트의 최대 낙폭이 이것으로
     * 만들어진다 ({@code rpt.md} 열린 질문 1).
     *
     * <p><b>실시간 발행 뒤에, 그리고 따로 실패한다.</b> 낙폭은 시즌이 끝나야 읽히는 값이고
     * 화면은 매 틱 갱신돼야 한다 — 쓰기 한 번이 실패했다고 그 틱의 화면까지 멈출 이유가
     * 없다. 한 틱을 놓치면 그 틱의 최고점 갱신만 빠지고 다음 틱이 이어 받는다.
     */
    private boolean record(List<AccountValuation> valued, GameTick tick) {
        try {
            valuations.recordDrawdowns(valued);
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to record drawdowns at tick {}", tick.getTickSeq(), e);
            return false;
        }
    }

    /**
     * 한 사람에게 보낸다. 실패를 그 사람 선에서 끝내는 이유는 순서 때문이다 — 계좌 순서가
     * 안정적이라, 루프를 중단하면 매 틱 같은 뒤쪽 사용자들만 반복해서 굶는다.
     */
    private boolean send(AccountValuation valuation, GameTick tick) {
        try {
            publisher.sendToUser(valuation.userId(), TYPE,
                    tick.getTickSeq(), tick.getGameDate(), valuation.summary());
            publisher.sendToUser(valuation.userId(), "account.positions",
                    tick.getTickSeq(), tick.getGameDate(), valuation.positions());
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to send {} to user {} at tick {}",
                    TYPE, valuation.userId(), tick.getTickSeq(), e);
            return false;
        }
    }
}
