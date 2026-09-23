package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.market.repository.MarketPriceQueryRepository;
import com.ssafy.a605.backend.domain.market.repository.MarketPriceQueryRepository.CloseRow;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.domain.ranking.service.SettlementAggregator;
import com.ssafy.a605.backend.domain.report.service.DailySettlementCalculator.SettlementAggregate;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 폐장 스냅샷의 복기 집계를 채운다 ({@code rpt.md} §리포트는 스냅샷이다, 기획서 13.1).
 *
 * <p><b>조회 횟수가 계좌 수와 무관하다.</b> 체결 한 번, 일차의 마지막 거래일 한 번, 종가 한
 * 번으로 끝난다. 계좌마다 물으면 폐장에서 공개까지 20 분에 100 명분을 돌려야 하는 경로가
 * 계좌 수만큼 늘어난다.
 *
 * <p><b>패턴 태그의 기준은 그 일차 폐장 시점의 종가다.</b> 판정 규칙은
 * {@code DailySettlementCalculator.tag} 에 있다. 여기서는 그 종가를 랭킹 평가와 <b>같은
 * 이월 규칙</b>으로 읽는다 ({@code findLatestClosesAsOf}) — "폐장에 그 종목이 얼마였나"의 답이
 * 평가액과 달라야 할 이유가 없고, 그날 휴장이던 종목도 직전 종가를 갖는다.
 *
 * <p><b>실패를 기본값으로 덮지 않는다.</b> 예외가 나면 폐장 확정 트랜잭션이 함께 롤백된다.
 * 여기서 잡아 0 으로 저장하면 거래한 날이 "거래 없음"으로 굳고, 그 행이 확정값이라
 * {@code DailySettlementService} 의 멱등 가드가 재실행을 막아 되돌릴 수도 없다. 반대로 롤백된
 * 순위는 스냅샷이 프로세스 메모리에만 있어 재현이 불가능하지만, 복기 컬럼은 {@code executions}
 * 가 남아 있어 원인을 고친 뒤 그 일차만 다시 계산해 채울 수 있다 — 잃는 쪽이 더 작다.
 *
 * <p>환율은 {@code game_ticks.usd_krw} 가 {@code NOT NULL} 이고 체결이 그 테이블을 참조하므로
 * 없을 수 없고, 종가 부재는 정상 경로로 다룬다. 방어할 조건 자체가 없다.
 */
@Slf4j
@Component
public class DailySettlementAggregator implements SettlementAggregator {

    private final ExecutionRepository executionRepository;
    private final GameTickRepository gameTickRepository;
    private final MarketPriceQueryRepository priceQueryRepository;
    private final ObjectMapper json;
    private final long seasonId;

    public DailySettlementAggregator(ExecutionRepository executionRepository,
                                     GameTickRepository gameTickRepository,
                                     MarketPriceQueryRepository priceQueryRepository,
                                     ObjectMapper json,
                                     @Value("${app.replay.season-id:1}") long seasonId) {
        this.executionRepository = executionRepository;
        this.gameTickRepository = gameTickRepository;
        this.priceQueryRepository = priceQueryRepository;
        this.json = json;
        this.seasonId = seasonId;
    }

    @Override
    public Map<Long, Aggregate> aggregate(short seasonDay, List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        List<DailyTrade> trades = executionRepository.findDailyTrades(accountIds, seasonDay);
        if (trades.isEmpty()) {
            // 아무도 거래하지 않은 일차다. 호출 측이 빠진 계좌를 기본값으로 채운다.
            return Map.of();
        }
        return DailySettlementCalculator.aggregate(trades, dayCloses(trades, seasonDay)).entrySet()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> row(entry.getValue())));
    }

    /**
     * 거래한 종목의 그 일차 폐장 시점 종가.
     *
     * <p>기준일은 그 일차의 마지막 거래일이다. 종가는 시즌 시작 전에 전 구간이 적재되어 있어
     * ({@code _common.md} §9) 기준일을 명시하지 않으면 다음 일차의 종가를 집는다.
     */
    private Map<Long, BigDecimal> dayCloses(List<DailyTrade> trades, short seasonDay) {
        LocalDate lastGameDate = gameTickRepository.findGameDateRange(seasonId, seasonDay).toGameDate();
        if (lastGameDate == null) {
            // 편성에 없는 일차다. 순위는 있는데 틱이 없는 상태이며, 체결이 있는데 이 값이 비는
            // 것은 편성이 깨진 것이다 - 조용히 태그만 빼면 원인이 드러나지 않는다.
            throw new IllegalStateException(
                    "체결이 있는 일차가 편성에 없습니다: day=%d".formatted(seasonDay));
        }
        Set<Long> instrumentIds = trades.stream().map(DailyTrade::instrumentId)
                .collect(Collectors.toSet());
        return priceQueryRepository.findLatestClosesAsOf(instrumentIds, lastGameDate).stream()
                .collect(Collectors.toMap(CloseRow::instrumentId, CloseRow::close));
    }

    /**
     * 계산 결과를 저장 형태로 옮긴다. 태그만 JSON 문자열이 된다.
     *
     * <p>직렬화 실패를 잡지 않는다. Jackson 3 의 예외는 비검사 예외이고, 실패한다면 그것은
     * 레코드 모양이 잘못된 것이라 폐장이 아니라 테스트에서 드러나야 한다.
     */
    private Aggregate row(SettlementAggregate aggregate) {
        return new Aggregate(aggregate.executionCount(), aggregate.totalFeeKrw(),
                aggregate.realizedPnlKrw(), aggregate.bestExecutionId(),
                aggregate.worstExecutionId(), json.writeValueAsString(aggregate.patternTags()));
    }
}
