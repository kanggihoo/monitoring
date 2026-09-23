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
import com.ssafy.a605.backend.domain.report.service.DailyFifo.Paired;

/**
 * 선입선출 짝맞춤만 본다. DB 가 없으므로 Docker 없이 돌고, 여기서 보유 기간과 수익률이
 * 검증되면 나머지는 배선이다 ({@code DailySettlementCalculatorTest} 와 같은 배치다).
 *
 * <p>보는 것은 <b>짝이 맞지 않는 경우들</b>이다. 하루의 체결만 보는 자리라 그 일차 밖에서
 * 넘어온 포지션은 짝지을 매수가 없고, 그때 값을 지어내면 AI 가 "며칠 만에"를 틀리게 말한다.
 */
class DailyFifoTest {

    private static final long ACCOUNT = 11L;
    private static final long INSTRUMENT_A = 1L;
    private static final long INSTRUMENT_B = 2L;

    private static final LocalDate DAY_1 = LocalDate.of(2020, 1, 2);
    private static final LocalDate DAY_2 = LocalDate.of(2020, 1, 3);
    private static final LocalDate DAY_3 = LocalDate.of(2020, 1, 6);
    private static final LocalDate DAY_4 = LocalDate.of(2020, 1, 7);

    /** 편성에 없는 거래일. 틱을 찾을 수 없는 체결을 만들 때 쓴다. */
    private static final LocalDate UNSCHEDULED = LocalDate.of(2019, 12, 31);

    /** 거래일 -> 틱. 틱 하나가 게임 거래일 하나이므로 이것이 보유 기간을 재는 자다. */
    private static final Map<LocalDate, Long> TICKS =
            Map.of(DAY_1, 1L, DAY_2, 2L, DAY_3, 3L, DAY_4, 4L);

    @Test
    @DisplayName("짝맞춤_한 매수에 한 매도면_틱 차이가 보유 기간이고 단가 차이가 수익률이다")
    void pair_oneBuyOneSell_measuresTickGapAndPriceGap() {
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 1, "10000", DAY_1),
                sell(2L, INSTRUMENT_A, 1, "12000", DAY_3)), TICKS);

        assertThat(paired).containsOnlyKeys(2L);
        assertThat(paired.get(2L).holdingDays()).isEqualTo(2);
        assertThat(paired.get(2L).returnRate()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("짝맞춤_여러 묶음에 걸친 매도는_수량 가중 평균으로 낸다")
    void pair_acrossLots_weightsByQuantity() {
        // 1주 10,000 (1틱) + 3주 20,000 (3틱) = 평단 17,500, 가중 틱 2.5
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 1, "10000", DAY_1),
                buy(2L, INSTRUMENT_A, 3, "20000", DAY_3),
                sell(3L, INSTRUMENT_A, 4, "30000", DAY_4)), TICKS);

        // (30,000 - 17,500) / 17,500 = 71.43%
        assertThat(paired.get(3L).returnRate()).isEqualByComparingTo("71.43");
        // 묶음별 차이가 3틱 1주 · 1틱 3주라 가중 평균이 1.5 -> 반올림해 2.
        // 평균 매수 틱(2.5)을 먼저 반올림하면 3이 되어 보유 기간이 1로 짧아진다.
        assertThat(paired.get(3L).holdingDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("짝맞춤_남은 수량은_다음 매도가 이어 쓴다")
    void pair_partiallyConsumedLot_isReusedByTheNextSell() {
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 3, "10000", DAY_1),
                sell(2L, INSTRUMENT_A, 1, "11000", DAY_2),
                sell(3L, INSTRUMENT_A, 2, "12000", DAY_3)), TICKS);

        assertThat(paired).containsOnlyKeys(2L, 3L);
        assertThat(paired.get(2L).returnRate()).isEqualByComparingTo("10.00");
        assertThat(paired.get(3L).returnRate()).isEqualByComparingTo("20.00");
        assertThat(paired.get(3L).holdingDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("짝맞춤_수량이 남는 매도는_짝짓지 않는다")
    void pair_sellBeyondTheDaysBuys_isSkipped() {
        // 2주를 팔았는데 그 일차에 산 것은 1주다. 나머지 1주는 이전 일차에서 넘어온
        // 포지션이라 보유 기간이 뜻을 잃는다 - 절반만 짝지어 평균을 내면 값을 지어내는 것이다.
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 1, "10000", DAY_1),
                sell(2L, INSTRUMENT_A, 2, "12000", DAY_3)), TICKS);

        assertThat(paired).isEmpty();
    }

    @Test
    @DisplayName("짝맞춤_매수보다 먼저 온 매도는_짝짓지 않는다")
    void pair_sellBeforeAnyBuy_isSkipped() {
        // 들고 있던 것을 먼저 팔고 그 뒤에 다시 산 하루다. 뒤에 온 매수는 그 매도의 짝이 아니다.
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                sell(1L, INSTRUMENT_A, 1, "12000", DAY_1),
                buy(2L, INSTRUMENT_A, 1, "10000", DAY_2)), TICKS);

        assertThat(paired).isEmpty();
    }

    @Test
    @DisplayName("짝맞춤_종목이 섞여 있어도_서로의 묶음을 먹지 않는다")
    void pair_multipleInstruments_keepsLotsApart() {
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 1, "10000", DAY_1),
                buy(2L, INSTRUMENT_B, 1, "500", DAY_2),
                sell(3L, INSTRUMENT_B, 1, "600", DAY_3),
                sell(4L, INSTRUMENT_A, 1, "9000", DAY_4)), TICKS);

        assertThat(paired.get(3L).returnRate()).isEqualByComparingTo("20.00");
        assertThat(paired.get(3L).holdingDays()).isEqualTo(1);
        assertThat(paired.get(4L).returnRate()).isEqualByComparingTo("-10.00");
        assertThat(paired.get(4L).holdingDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("짝맞춤_같은 틱에 사고 팔면_보유 기간이 0 이다")
    void pair_boughtAndSoldOnTheSameTick_isZeroDays() {
        // 같은 게임 거래일이라 체결가가 둘 다 그 날의 종가다 - 수익률도 0 이다.
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 1, "10000", DAY_1),
                sell(2L, INSTRUMENT_A, 1, "10000", DAY_1)), TICKS);

        assertThat(paired.get(2L).holdingDays()).isZero();
        assertThat(paired.get(2L).returnRate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("짝맞춤_통화가 달라도_수익률에 환율이 필요 없다")
    void pair_usdTrade_needsNoExchangeRate() {
        // 매수가와 매도가가 같은 통화라 비율에서 약분된다. 달러 손익의 원화 환산은 리포트
        // payload 가 따로 한다.
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                usdBuy(1L, INSTRUMENT_A, 1, "100.00", DAY_1),
                usdSell(2L, INSTRUMENT_A, 1, "125.00", DAY_2)), TICKS);

        assertThat(paired.get(2L).returnRate()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("짝맞춤_편성에 없는 거래일의 체결은_셈에서 빠진다")
    void pair_tradeOutsideTheSchedule_isIgnored() {
        // 틱을 찾을 수 없으면 보유 기간을 잴 자가 없다. 그 매수를 0틱으로 치면 보유 기간이
        // 통째로 틀어지므로 묶음에 넣지 않고, 짝을 못 찾은 매도는 결과에서 빠진다.
        Map<Long, Paired> paired = DailyFifo.pair(List.of(
                buy(1L, INSTRUMENT_A, 1, "10000", UNSCHEDULED),
                sell(2L, INSTRUMENT_A, 1, "12000", DAY_3)), TICKS);

        assertThat(paired).isEmpty();
    }

    @Test
    @DisplayName("짝맞춤_체결이 없으면_빈 결과다")
    void pair_withoutTrades_isEmpty() {
        assertThat(DailyFifo.pair(List.of(), TICKS)).isEmpty();
    }

    private static DailyTrade buy(long id, long instrumentId, int quantity, String price,
                                  LocalDate gameDate) {
        return trade(id, instrumentId, OrderSide.BUY, quantity, price, gameDate, Currency.KRW,
                null);
    }

    private static DailyTrade sell(long id, long instrumentId, int quantity, String price,
                                   LocalDate gameDate) {
        return trade(id, instrumentId, OrderSide.SELL, quantity, price, gameDate, Currency.KRW,
                "1000");
    }

    private static DailyTrade usdBuy(long id, long instrumentId, int quantity, String price,
                                     LocalDate gameDate) {
        return trade(id, instrumentId, OrderSide.BUY, quantity, price, gameDate, Currency.USD,
                null);
    }

    private static DailyTrade usdSell(long id, long instrumentId, int quantity, String price,
                                      LocalDate gameDate) {
        return trade(id, instrumentId, OrderSide.SELL, quantity, price, gameDate, Currency.USD,
                "10.00");
    }

    /** 짝짓기는 일차를 보지 않는다 — 일차로 끊는 것은 TradeCounts 의 몫이다. */
    private static final Short DAY = 1;

    private static DailyTrade trade(long id, long instrumentId, OrderSide side, int quantity,
                                    String price, LocalDate gameDate, Currency currency,
                                    String realizedPnl) {
        BigDecimal executedPrice = new BigDecimal(price);
        return new DailyTrade(id, ACCOUNT, instrumentId, side, quantity, executedPrice, currency,
                executedPrice.multiply(BigDecimal.valueOf(quantity)), BigDecimal.ZERO,
                realizedPnl == null ? null : new BigDecimal(realizedPnl), gameDate,
                new BigDecimal("1200"), DAY);
    }
}
