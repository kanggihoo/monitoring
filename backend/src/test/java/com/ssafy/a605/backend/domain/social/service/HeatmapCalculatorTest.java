package com.ssafy.a605.backend.domain.social.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.domain.social.dto.HeatmapBucket;
import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot;
import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot.Bucket;

/**
 * 히트맵 집계 규칙만 본다. DB·시계가 없으므로 Docker 없이 돌고, 비중·등락·현금 도출이 여기서
 * 검증되면 나머지는 배선이다.
 */
class HeatmapCalculatorTest {

    private static final long TICK = 137L;
    private static final LocalDate GAME_DATE = LocalDate.of(2022, 4, 15);

    private final HeatmapCalculator calculator = new HeatmapCalculator();

    @Test
    @DisplayName("집계_네 버킷이 고정 순서로 오고_합이 총평가액과 정확히 같다")
    void aggregate_fourBucketsInFixedOrder_summingToTotal() {
        HeatmapSnapshot snapshot = calculator.aggregate(TICK, GAME_DATE, total("302701000"),
                holdings("60600000", "101000", "0"), null);

        assertThat(snapshot.buckets()).extracting(Bucket::category)
                .containsExactly(HeatmapBucket.NASDAQ, HeatmapBucket.KRX, HeatmapBucket.CRYPTO,
                        HeatmapBucket.CASH);
        // 현금은 빼서 구한다 — 총평가액에서 종목 합을 뺀 것이다.
        assertThat(valuation(snapshot, HeatmapBucket.CASH)).isEqualByComparingTo("242000000");
        assertThat(snapshot.buckets().stream().map(Bucket::valuationKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("302701000");
        assertThat(snapshot.tickSeq()).isEqualTo(TICK);
        assertThat(snapshot.gameDate()).isEqualTo(GAME_DATE);
    }

    @Test
    @DisplayName("집계_비중은 총평가액 대비이고_합이 100 이다")
    void aggregate_weightsSumToHundred() {
        HeatmapSnapshot snapshot = calculator.aggregate(TICK, GAME_DATE, total("302701000"),
                holdings("60600000", "101000", "0"), null);

        assertThat(weight(snapshot, HeatmapBucket.NASDAQ)).isEqualByComparingTo("20.02");
        assertThat(weight(snapshot, HeatmapBucket.KRX)).isEqualByComparingTo("0.03");
        assertThat(weight(snapshot, HeatmapBucket.CRYPTO)).isEqualByComparingTo("0.00");
        assertThat(weight(snapshot, HeatmapBucket.CASH)).isEqualByComparingTo("79.95");
        assertThat(snapshot.buckets().stream().map(Bucket::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("첫 틱_비교할 직전 집계가 없으면_모든 등락이 0 이다")
    void aggregate_withoutPrevious_hasZeroDeltas() {
        HeatmapSnapshot snapshot = calculator.aggregate(TICK, GAME_DATE, total("302701000"),
                holdings("60600000", "101000", "0"), null);

        assertThat(snapshot.buckets()).allSatisfy(bucket ->
                assertThat(bucket.weightDelta()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("다음 틱_종목 비중이 내려가면_그만큼 현금 비중이 올라간다")
    void aggregate_nextTick_carriesWeightDelta() {
        HeatmapSnapshot previous = calculator.aggregate(TICK, GAME_DATE, total("302701000"),
                holdings("60600000", "101000", "0"), null);

        HeatmapSnapshot snapshot = calculator.aggregate(TICK + 1, GAME_DATE.plusDays(1), total("301488000"),
                holdings("59290000", "98000", "0"), previous);

        assertThat(weight(snapshot, HeatmapBucket.NASDAQ)).isEqualByComparingTo("19.67");
        assertThat(delta(snapshot, HeatmapBucket.NASDAQ)).isEqualByComparingTo("-0.35");
        assertThat(delta(snapshot, HeatmapBucket.CASH)).isEqualByComparingTo("0.35");
        assertThat(delta(snapshot, HeatmapBucket.KRX)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("참가자가 없는 틱_분모가 0 이면_나누지 않고 비중이 0 이다")
    void aggregate_withoutParticipants_hasZeroWeights() {
        HeatmapSnapshot snapshot = calculator.aggregate(TICK, GAME_DATE, BigDecimal.ZERO, Map.of(), null);

        assertThat(snapshot.buckets()).hasSize(4);
        assertThat(snapshot.buckets()).allSatisfy(bucket -> {
            assertThat(bucket.valuationKrw()).isEqualByComparingTo("0");
            assertThat(bucket.weight()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("집계_카테고리에 보유가 없어도_그 버킷은 0 으로 온다")
    void aggregate_missingCategory_stillHasBucket() {
        HeatmapSnapshot snapshot = calculator.aggregate(TICK, GAME_DATE, total("100000000"), Map.of(), null);

        assertThat(valuation(snapshot, HeatmapBucket.NASDAQ)).isEqualByComparingTo("0");
        assertThat(valuation(snapshot, HeatmapBucket.CASH)).isEqualByComparingTo("100000000");
        assertThat(weight(snapshot, HeatmapBucket.CASH)).isEqualByComparingTo("100.00");
    }

    private static BigDecimal total(String value) {
        return new BigDecimal(value);
    }

    private static Map<HeatmapBucket, BigDecimal> holdings(String nasdaq, String krx, String crypto) {
        Map<HeatmapBucket, BigDecimal> holdings = new EnumMap<>(HeatmapBucket.class);
        holdings.put(HeatmapBucket.NASDAQ, new BigDecimal(nasdaq));
        holdings.put(HeatmapBucket.KRX, new BigDecimal(krx));
        holdings.put(HeatmapBucket.CRYPTO, new BigDecimal(crypto));
        return holdings;
    }

    private static BigDecimal valuation(HeatmapSnapshot snapshot, HeatmapBucket category) {
        return bucket(snapshot, category).valuationKrw();
    }

    private static BigDecimal weight(HeatmapSnapshot snapshot, HeatmapBucket category) {
        return bucket(snapshot, category).weight();
    }

    private static BigDecimal delta(HeatmapSnapshot snapshot, HeatmapBucket category) {
        return bucket(snapshot, category).weightDelta();
    }

    private static Bucket bucket(HeatmapSnapshot snapshot, HeatmapBucket category) {
        return snapshot.buckets().stream().filter(b -> b.category() == category).findFirst().orElseThrow();
    }
}
