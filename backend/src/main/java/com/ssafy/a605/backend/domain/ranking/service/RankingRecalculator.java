package com.ssafy.a605.backend.domain.ranking.service;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.account.dto.AccountsValuedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.RankingRecalculatedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종가 틱마다 순위를 다시 만든다 ({@code rank.md} §매 틱 전 계좌 순회, 기획서 11.1.3).
 *
 * <p><b>전 계좌를 스스로 순회하지 않는다.</b> 계좌 도메인의 매 틱 스윕
 * ({@code AccountValuationService.valueAll}) 이 이미 전 계좌를 한 번 돌고, 그 결과를
 * {@link AccountsValuedEvent} 로 넘긴다. 랭킹이 따로 돌면 같은 순회가 두 번 돌고 두 결과가 다른
 * 틱을 볼 수 있으며, 무엇보다 리더보드 수익률과 계좌 요약 수익률이 어긋날 길이 생긴다.
 *
 * <p>평가하지 못해 스윕이 건너뛴 계좌(보유 종목의 종가 없음)는 그 틱의 순위에도 없다. 그 판단은
 * 스윕의 것이고, 랭킹은 받은 것만 세운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingRecalculator {

    private final RankingCalculator calculator = new RankingCalculator();

    private final ApplicationEventPublisher events;

    /** 마지막으로 만든 순위. 재시작 직후에는 다음 틱까지 {@code null} 이다. */
    private final AtomicReference<RankingSnapshot> current = new AtomicReference<>();

    /**
     * @return 마지막 틱의 순위. 아직 어떤 틱도 평가되지 않았으면 {@code null}
     */
    public RankingSnapshot current() {
        return current.get();
    }

    /**
     * 스냅샷을 비운다. 개발·테스트 전용 시즌 초기화만 부른다.
     *
     * <p>비우지 않으면 지워진 계좌의 순위가 계속 응답에 나가고, 더 나쁘게는 초기화 뒤 첫
     * 폐장에서 일차 정산이 그 스냅샷을 그대로 얼려 저장한다.
     */
    public void clear() {
        current.set(null);
    }

    /**
     * 스윕이 낸 평가로 순위를 교체한다.
     *
     * @param event 한 틱의 전 계좌 평가 결과
     */
    @EventListener
    public void on(AccountsValuedEvent event) {
        try {
            RankingSnapshot previous = current.get();
            RankingSnapshot next = calculator.rank(event.tickSeq(), event.gameDate(), event.seasonDay(),
                    event.valuations(), previous);
            current.set(next);
            // 실시간 발행은 재계산이 끝난 뒤 별도 리스너가 한다. 여기서는 결과가 바뀌었다는 것만 알린다.
            events.publishEvent(new RankingRecalculatedEvent(previous, next));
        } catch (RuntimeException e) {
            // 스냅샷은 직전 틱 것으로 남는다. 폴러로 던지면 재생이 멈춘 것처럼 기록된다.
            log.error("Ranking recalculation failed for tick {}; previous snapshot retained", event.tickSeq(), e);
        }
    }

    /**
     * 폐장 스냅샷이 커밋되면 지금 순위를 확정된 것으로 표시한다. 리더보드의 {@code finalized} 가
     * 이 값이다 ({@code rank.md} — 폐장 확정 후부터 다음 개장 전까지 참).
     *
     * @param event 커밋된 폐장 스냅샷
     */
    @EventListener
    public void on(DailySettlementFinalizedEvent event) {
        current.updateAndGet(snapshot -> snapshot == null || snapshot.seasonDay() != event.seasonDay()
                ? snapshot : snapshot.withFinalized(true));
    }

    /**
     * 다음 날 브리핑에 들어가면 확정 표시를 푼다. 순위 자체는 전날 마지막 틱 것으로 남고,
     * 그날 첫 틱이 새 순위로 바꾼다.
     *
     * @param event 커밋된 Phase 경계
     */
    @EventListener
    public void on(PhaseBoundaryEvent event) {
        if (event.isBriefing()) {
            current.updateAndGet(snapshot -> snapshot == null ? null : snapshot.withFinalized(false));
        }
    }
}
