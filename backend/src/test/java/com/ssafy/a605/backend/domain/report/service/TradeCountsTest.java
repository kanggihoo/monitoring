package com.ssafy.a605.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;

/**
 * 누적 집계가 <b>일차 경계를 넘어 짝짓지 않는지</b> 본다.
 *
 * <p>일차 경계마다 보유가 초기화되므로 1 일차에 산 것을 2 일차에 팔 수 없다. 구간 전체를 한
 * 번에 선입선출로 짝지으면 그 불가능한 짝이 생기고, 보유 기간이 이틀치로 늘어나 "금방
 * 팔았다" 판정과 AI 의 "며칠 만에" 문장이 함께 틀어진다.
 *
 * <p>DB 없이 돈다. 짝맞춤 자체는 {@link DailyFifoTest} 가 본다.
 */
class TradeCountsTest {

    private static final long ACCOUNT = 11L;
    private static final long INSTRUMENT = 1L;

    private static final LocalDate D1_EARLY = LocalDate.of(2020, 1, 2);
    private static final LocalDate D1_LATE = LocalDate.of(2020, 1, 3);
    private static final LocalDate D2_EARLY = LocalDate.of(2020, 1, 6);
    private static final LocalDate D2_LATE = LocalDate.of(2020, 1, 7);

    private static final Map<LocalDate, Long> TICKS =
            Map.of(D1_EARLY, 1L, D1_LATE, 2L, D2_EARLY, 3L, D2_LATE, 4L);

    /** 종가를 넣지 않는다 — 고점·저점 판정은 이 테스트의 관심이 아니고, 빈 창은 빈 값을 준다. */
    private static final PricePosition NO_POSITION = PricePosition.of(List.of());

    @Test
    @DisplayName("누적 집계_1일차 매수와 2일차 매도는_짝지어지지 않는다")
    void of_acrossSeasonDays_doesNotPair() {
        List<DailyTrade> trades = List.of(
                buy(1L, "10000", D1_EARLY, (short) 1),
                sell(2L, "11000", D2_LATE, "1000", (short) 2));

        TradeCounts counts = TradeCounts.of(trades, TICKS, NO_POSITION);

        assertThat(counts.buyCount).isEqualTo(1);
        assertThat(counts.sellCount).isEqualTo(1);
        // 매도 자체는 세지만 짝이 없으므로 보유 기간 계열은 비어 있다.
        assertThat(counts.closedPositionCount).isZero();
        assertThat(counts.holdingDaysSum).isZero();
        assertThat(counts.quickProfitCount).isZero();
    }

    @Test
    @DisplayName("누적 집계_같은 일차 안의 매수·매도는_평소처럼 짝지어진다")
    void of_withinOneDay_pairsAsUsual() {
        List<DailyTrade> trades = List.of(
                buy(1L, "10000", D1_EARLY, (short) 1),
                sell(2L, "10200", D1_LATE, "200", (short) 1));

        TradeCounts counts = TradeCounts.of(trades, TICKS, NO_POSITION);

        assertThat(counts.closedPositionCount).isEqualTo(1);
        assertThat(counts.holdingDaysSum).isEqualTo(1);
        // +2.00% 이고 보유 1 거래일이라 빠른 익절이다.
        assertThat(counts.quickProfitCount).isEqualTo(1);
    }

    @Test
    @DisplayName("누적 집계_일차마다 같은 종목을 사고팔면_일차별로 각각 짝지어진다")
    void of_sameInstrumentOnBothDays_pairsWithinEachDay() {
        List<DailyTrade> trades = List.of(
                buy(1L, "10000", D1_EARLY, (short) 1),
                sell(2L, "10200", D1_LATE, "200", (short) 1),
                buy(3L, "20000", D2_EARLY, (short) 2),
                sell(4L, "20400", D2_LATE, "400", (short) 2));

        TradeCounts counts = TradeCounts.of(trades, TICKS, NO_POSITION);

        assertThat(counts.closedPositionCount).isEqualTo(2);
        // 일차마다 1 거래일씩이다. 경계를 넘겨 짝지으면 3 거래일짜리가 섞여 값이 커진다.
        assertThat(counts.holdingDaysSum).isEqualTo(2);
    }

    private static DailyTrade buy(long id, String price, LocalDate gameDate, short seasonDay) {
        return trade(id, OrderSide.BUY, price, gameDate, null, seasonDay);
    }

    private static DailyTrade sell(long id, String price, LocalDate gameDate, String realizedPnl,
                                   short seasonDay) {
        return trade(id, OrderSide.SELL, price, gameDate, realizedPnl, seasonDay);
    }

    private static DailyTrade trade(long id, OrderSide side, String price, LocalDate gameDate,
                                    String realizedPnl, short seasonDay) {
        BigDecimal executedPrice = new BigDecimal(price);
        return new DailyTrade(id, ACCOUNT, INSTRUMENT, side, 1, executedPrice, Currency.KRW,
                executedPrice, BigDecimal.ZERO,
                realizedPnl == null ? null : new BigDecimal(realizedPnl), gameDate,
                new BigDecimal("1200"), seasonDay);
    }
}
