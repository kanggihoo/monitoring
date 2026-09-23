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
import com.ssafy.a605.backend.domain.report.dto.PatternTag;
import com.ssafy.a605.backend.domain.report.service.DailySettlementCalculator.SettlementAggregate;

/**
 * 복기 집계 규칙만 본다. DB 가 없으므로 Docker 없이 돌고, 환산·상하위·태그 판정이 여기서
 * 검증되면 나머지는 배선이다.
 */
class DailySettlementCalculatorTest {

    private static final long ACCOUNT = 11L;
    private static final long OTHER_ACCOUNT = 12L;
    private static final long KRW_INSTRUMENT = 1L;
    private static final long USD_INSTRUMENT = 2L;

    private static final LocalDate DAY_1 = LocalDate.of(2020, 1, 2);
    private static final LocalDate DAY_2 = LocalDate.of(2020, 1, 3);
    private static final LocalDate DAY_3 = LocalDate.of(2020, 1, 6);

    private static final BigDecimal USD_KRW = new BigDecimal("1200");

    @Test
    @DisplayName("집계_원화 체결만 있으면_건수와 수수료 합이 그대로 더해진다")
    void aggregate_krwOnly_sumsFeeAsIs() {
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1),
                buy(2L, KRW_INSTRUMENT, "10000", "150", DAY_1)), noCloses());

        assertThat(result.executionCount()).isEqualTo(2);
        assertThat(result.totalFeeKrw()).isEqualByComparingTo("300");
        assertThat(result.realizedPnlKrw()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("집계_달러 체결은_그 틱의 환율로 원화 환산된다")
    void aggregate_usdTrade_isConvertedWithTickRate() {
        SettlementAggregate result = one(List.of(
                sell(1L, USD_INSTRUMENT, "100.00", "0.15", "20.00", DAY_1, Currency.USD)),
                noCloses());

        // 수수료 0.15 x 1200 = 180, 실현 손익 20.00 x 1200 = 24,000
        assertThat(result.totalFeeKrw()).isEqualByComparingTo("180");
        assertThat(result.realizedPnlKrw()).isEqualByComparingTo("24000");
    }

    @Test
    @DisplayName("집계_작은 달러 수수료가 여러 건이면_건수만큼 반올림되지 않는다")
    void aggregate_manySmallUsdFees_isRoundedOnce() {
        // 0.0004 x 1200 = 0.48 원. 체결마다 원 단위로 끊으면 0 원이 다섯 번 더해져 0 이 된다.
        List<DailyTrade> trades = List.of(
                buy(1L, USD_INSTRUMENT, "10.00", "0.0004", DAY_1, Currency.USD),
                buy(2L, USD_INSTRUMENT, "10.00", "0.0004", DAY_1, Currency.USD),
                buy(3L, USD_INSTRUMENT, "10.00", "0.0004", DAY_1, Currency.USD),
                buy(4L, USD_INSTRUMENT, "10.00", "0.0004", DAY_1, Currency.USD),
                buy(5L, USD_INSTRUMENT, "10.00", "0.0004", DAY_1, Currency.USD));

        assertThat(one(trades, noCloses()).totalFeeKrw()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("상하위_매수만 한 날은_실현 손익이 0 이고 상하위가 없다")
    void aggregate_buysOnly_hasNoBestOrWorst() {
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1)), noCloses());

        assertThat(result.realizedPnlKrw()).isEqualByComparingTo("0");
        assertThat(result.bestExecutionId()).isNull();
        assertThat(result.worstExecutionId()).isNull();
    }

    @Test
    @DisplayName("상하위_매도가 여럿이면_실현 손익 상위와 하위가 갈린다")
    void aggregate_multipleSells_picksBestAndWorst() {
        SettlementAggregate result = one(List.of(
                sell(1L, KRW_INSTRUMENT, "10000", "150", "50000", DAY_1, Currency.KRW),
                sell(2L, KRW_INSTRUMENT, "10000", "150", "-30000", DAY_2, Currency.KRW),
                sell(3L, KRW_INSTRUMENT, "10000", "150", "10000", DAY_3, Currency.KRW)),
                noCloses());

        assertThat(result.bestExecutionId()).isEqualTo(1L);
        assertThat(result.worstExecutionId()).isEqualTo(2L);
        assertThat(result.realizedPnlKrw()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("상하위_매도가 한 건이면_상위와 하위가 같은 체결을 가리킨다")
    void aggregate_singleSell_isBothBestAndWorst() {
        SettlementAggregate result = one(List.of(
                sell(7L, KRW_INSTRUMENT, "10000", "150", "50000", DAY_1, Currency.KRW)),
                noCloses());

        assertThat(result.bestExecutionId()).isEqualTo(7L);
        assertThat(result.worstExecutionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("상하위_통화가 섞이면_원화 환산으로 비교한다")
    void aggregate_mixedCurrency_comparesInKrw() {
        // 달러 30 x 1200 = 36,000 원이 원화 50,000 원보다 작다. 환산 없이 비교하면 뒤집힌다.
        SettlementAggregate result = one(List.of(
                sell(1L, USD_INSTRUMENT, "100.00", "0.15", "30.00", DAY_1, Currency.USD),
                sell(2L, KRW_INSTRUMENT, "10000", "150", "50000", DAY_1, Currency.KRW)),
                noCloses());

        assertThat(result.bestExecutionId()).isEqualTo(2L);
        assertThat(result.worstExecutionId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("태그_체결가가 폐장 종가보다 기준만큼 높으면_고점 매수가 붙는다")
    void tag_boughtAboveDayEndClose_isPanicBuyHigh() {
        // 11,000 에 사고 그 일차 폐장 종가가 9,800 이면 -10.91%
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "11000", "150", DAY_1)), closes("9800"));

        assertThat(result.patternTags()).hasSize(1);
        PatternTag tag = result.patternTags().get(0);
        assertThat(tag.kind()).isEqualTo(PatternTag.PANIC_BUY_HIGH);
        assertThat(tag.executionId()).isEqualTo(1L);
        assertThat(tag.instrumentId()).isEqualTo(KRW_INSTRUMENT);
        assertThat(tag.changeRate()).isEqualByComparingTo("-10.91");
    }

    @Test
    @DisplayName("태그_체결가가 폐장 종가보다 기준만큼 낮으면_저점 매도가 붙는다")
    void tag_soldBelowDayEndClose_isPanicSellLow() {
        SettlementAggregate result = one(List.of(
                sell(1L, KRW_INSTRUMENT, "10000", "150", "5000", DAY_1, Currency.KRW)),
                closes("12000"));

        assertThat(result.patternTags()).hasSize(1);
        assertThat(result.patternTags().get(0).kind()).isEqualTo(PatternTag.PANIC_SELL_LOW);
        assertThat(result.patternTags().get(0).changeRate()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("태그_변화율이 정확히 기준이면_붙는다")
    void tag_exactlyAtThreshold_isTagged() {
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1)), closes("9000"));

        assertThat(result.patternTags()).hasSize(1);
        assertThat(result.patternTags().get(0).changeRate()).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("태그_이익 쪽도 정확히 기준이면_붙는다")
    void tag_exactlyAtThresholdOnTheProfitSide_isTagged() {
        // 부호만 뒤집은 같은 계산이므로 경계도 대칭이어야 한다.
        SettlementAggregate bought = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1)), closes("11000"));
        assertThat(bought.patternTags().get(0).kind()).isEqualTo(PatternTag.LUCKY_BUY_LOW);

        SettlementAggregate sold = one(List.of(
                sell(1L, KRW_INSTRUMENT, "10000", "150", "5000", DAY_1, Currency.KRW)),
                closes("9000"));
        assertThat(sold.patternTags().get(0).kind()).isEqualTo(PatternTag.LUCKY_SELL_HIGH);
    }

    @Test
    @DisplayName("태그_이익 쪽도 기준에 못 미치면_붙지 않는다")
    void tag_belowThresholdOnTheProfitSide_isNotTagged() {
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1)), closes("10900"));

        assertThat(result.patternTags()).isEmpty();
    }

    @Test
    @DisplayName("태그_기준에 못 미치는 변화는_붙지 않는다")
    void tag_belowThreshold_isNotTagged() {
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1)), closes("9100"));

        assertThat(result.patternTags()).isEmpty();
    }

    @Test
    @DisplayName("태그_체결가가 폐장 종가보다 기준만큼 낮으면_저점 매수가 붙는다")
    void tag_boughtBelowDayEndClose_isLuckyBuyLow() {
        // 잘한 거래도 태그가 붙는다. 붙지 않으면 리포트가 질책만 하게 된다 (요청서 v5 §1.1).
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1)), closes("12000"));

        assertThat(result.patternTags()).hasSize(1);
        assertThat(result.patternTags().get(0).kind()).isEqualTo(PatternTag.LUCKY_BUY_LOW);
        assertThat(result.patternTags().get(0).changeRate()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("태그_체결가가 폐장 종가보다 기준만큼 높으면_고점 매도가 붙는다")
    void tag_soldAboveDayEndClose_isLuckySellHigh() {
        SettlementAggregate result = one(List.of(
                sell(1L, KRW_INSTRUMENT, "10000", "150", "5000", DAY_1, Currency.KRW)),
                closes("8000"));

        assertThat(result.patternTags()).hasSize(1);
        assertThat(result.patternTags().get(0).kind()).isEqualTo(PatternTag.LUCKY_SELL_HIGH);
        assertThat(result.patternTags().get(0).changeRate()).isEqualByComparingTo("-20.00");
    }

    @Test
    @DisplayName("태그_같은 움직임에서_매수와 매도가 서로 반대 태그를 받는다")
    void tag_sameMoveOppositeSides_areTaggedApart() {
        // 오르는 장에서 산 것은 저점 매수이고 판 것은 저점 매도다. 부호가 아니라 방향이 가른다.
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1),
                sell(2L, KRW_INSTRUMENT, "10000", "150", "5000", DAY_1, Currency.KRW)),
                closes("12000"));

        assertThat(result.patternTags()).extracting(PatternTag::kind)
                .containsExactly(PatternTag.LUCKY_BUY_LOW, PatternTag.PANIC_SELL_LOW);
    }

    @Test
    @DisplayName("태그_폐장 종가에 체결한 건은_변화율이 0 이라 붙지 않는다")
    void tag_tradedAtDayEndClose_isNotTagged() {
        // 일차의 마지막 거래일 체결이다. 체결가가 곧 기준 종가라 따로 걸러낼 필요가 없다.
        SettlementAggregate result = one(List.of(
                buy(1L, KRW_INSTRUMENT, "9600", "150", DAY_3)), closes("9600"));

        assertThat(result.patternTags()).isEmpty();
    }

    @Test
    @DisplayName("태그_폐장 종가를 모르는 종목은_태그만 빠지고 나머지 집계는 남는다")
    void tag_withoutDayClose_stillAggregatesTheRest() {
        SettlementAggregate result = one(List.of(
                buy(1L, USD_INSTRUMENT, "100.00", "0.15", DAY_1, Currency.USD)), closes("9000"));

        assertThat(result.patternTags()).isEmpty();
        assertThat(result.executionCount()).isEqualTo(1);
        assertThat(result.totalFeeKrw()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("집계_여러 계좌가 섞여 오면_계좌별로 갈린다")
    void aggregate_multipleAccounts_isSplitByAccount() {
        DailyTrade mine = buy(1L, KRW_INSTRUMENT, "10000", "150", DAY_1);
        DailyTrade theirs = new DailyTrade(2L, OTHER_ACCOUNT, KRW_INSTRUMENT, OrderSide.BUY, 1,
                new BigDecimal("10000"), Currency.KRW, new BigDecimal("10000"),
                new BigDecimal("300"), null, DAY_1, USD_KRW, SEASON_DAY);

        Map<Long, SettlementAggregate> result =
                DailySettlementCalculator.aggregate(List.of(mine, theirs), noCloses());

        assertThat(result).containsOnlyKeys(ACCOUNT, OTHER_ACCOUNT);
        assertThat(result.get(ACCOUNT).totalFeeKrw()).isEqualByComparingTo("150");
        assertThat(result.get(OTHER_ACCOUNT).totalFeeKrw()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("집계_체결이 없으면_계좌 키가 아예 없다")
    void aggregate_withoutTrades_isEmpty() {
        assertThat(DailySettlementCalculator.aggregate(List.of(), noCloses())).isEmpty();
    }

    /** 이 집계는 일차를 쓰지 않는다. 한 일차분만 들어오는 자리라 값을 고정해 둔다. */
    private static final Short SEASON_DAY = 1;

    private static SettlementAggregate one(List<DailyTrade> trades, Map<Long, BigDecimal> closes) {
        return DailySettlementCalculator.aggregate(trades, closes).get(ACCOUNT);
    }

    private static DailyTrade buy(long id, long instrumentId, String price, String fee,
                                  LocalDate gameDate) {
        return buy(id, instrumentId, price, fee, gameDate, Currency.KRW);
    }

    private static DailyTrade buy(long id, long instrumentId, String price, String fee,
                                  LocalDate gameDate, Currency currency) {
        return new DailyTrade(id, ACCOUNT, instrumentId, OrderSide.BUY, 1, new BigDecimal(price),
                currency, new BigDecimal(price), new BigDecimal(fee), null, gameDate, USD_KRW,
                SEASON_DAY);
    }

    private static DailyTrade sell(long id, long instrumentId, String price, String fee,
                                   String realizedPnl, LocalDate gameDate, Currency currency) {
        return new DailyTrade(id, ACCOUNT, instrumentId, OrderSide.SELL, 1, new BigDecimal(price),
                currency, new BigDecimal(price), new BigDecimal(fee), new BigDecimal(realizedPnl),
                gameDate, USD_KRW, SEASON_DAY);
    }

    private static Map<Long, BigDecimal> noCloses() {
        return Map.of();
    }

    /** 원화 종목의 폐장 종가만 준다. 달러 종목은 일부러 비워 둔다. */
    private static Map<Long, BigDecimal> closes(String krwInstrumentClose) {
        return Map.of(KRW_INSTRUMENT, new BigDecimal(krwInstrumentClose));
    }
}
