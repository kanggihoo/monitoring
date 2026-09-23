package com.ssafy.a605.backend.domain.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.domain.account.dto.AccountValuation;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot.Entry;

/** 순위 규칙만 본다. 수익률은 계좌 스윕이 이미 계산해 들어온다고 가정한다. */
class RankingCalculatorTest {

    private static final LocalDate DATE = LocalDate.of(2020, 1, 2);

    private final RankingCalculator calculator = new RankingCalculator();

    @Nested
    @DisplayName("동점 처리")
    class Ties {

        @Test
        @DisplayName("순위_수익률이 같으면_같은 순위를 주고 다음 순위를 건너뛴다")
        void rank_equalReturns_shareRankAndSkipNext() {
            RankingSnapshot snapshot = rank(null, season(1, "20.00"), season(2, "20.00"), season(3, "10.00"));

            assertThat(snapshot.byAccountId().get(1L).seasonRank()).isEqualTo(1);
            assertThat(snapshot.byAccountId().get(2L).seasonRank()).isEqualTo(1);
            assertThat(snapshot.byAccountId().get(3L).seasonRank()).isEqualTo(3);
        }

        @Test
        @DisplayName("순위_자릿수만 다른 같은 값이면_동점이다")
        void rank_sameValueDifferentScale_areTied() {
            RankingSnapshot snapshot = rank(null, season(1, "20.0"), season(2, "20.00"));

            assertThat(snapshot.byAccountId().get(1L).seasonRank()).isEqualTo(1);
            assertThat(snapshot.byAccountId().get(2L).seasonRank()).isEqualTo(1);
        }

        @Test
        @DisplayName("목록_같은 순위 안에서는_계좌 식별자 순으로 고정된다")
        void list_withinTie_isOrderedByAccountId() {
            RankingSnapshot snapshot = rank(null, season(7, "20.00"), season(3, "20.00"), season(5, "30.00"));

            assertThat(snapshot.bySeasonRank()).extracting(Entry::accountId).containsExactly(5L, 3L, 7L);
        }
    }

    @Nested
    @DisplayName("순위 등락")
    class Delta {

        @Test
        @DisplayName("등락_첫 틱이면_모두 0 이다")
        void delta_firstTick_isZero() {
            RankingSnapshot snapshot = rank(null, season(1, "20.00"), season(2, "10.00"));

            assertThat(snapshot.bySeasonRank()).allSatisfy(e -> {
                assertThat(e.seasonRankDelta()).isZero();
                assertThat(e.dailyRankDelta()).isZero();
            });
        }

        @Test
        @DisplayName("등락_직전 틱보다 올라가면 양수, 내려가면 음수다")
        void delta_againstPreviousTick_positiveMeansUp() {
            RankingSnapshot previous = rank(null, season(1, "20.00"), season(2, "10.00"));
            RankingSnapshot now = rank(previous, season(1, "5.00"), season(2, "15.00"));

            assertThat(now.byAccountId().get(2L).seasonRank()).isEqualTo(1);
            assertThat(now.byAccountId().get(2L).seasonRankDelta()).isEqualTo(1);
            assertThat(now.byAccountId().get(1L).seasonRank()).isEqualTo(2);
            assertThat(now.byAccountId().get(1L).seasonRankDelta()).isEqualTo(-1);
        }

        @Test
        @DisplayName("등락_직전 틱에 없던 신규 참가자는_0 이다")
        void delta_newcomer_isZero() {
            RankingSnapshot previous = rank(null, season(1, "20.00"));
            RankingSnapshot now = rank(previous, season(1, "20.00"), season(9, "30.00"));

            assertThat(now.byAccountId().get(9L).seasonRank()).isEqualTo(1);
            assertThat(now.byAccountId().get(9L).seasonRankDelta()).isZero();
            assertThat(now.byAccountId().get(1L).seasonRankDelta()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("세 트랙")
    class Tracks {

        @Test
        @DisplayName("순위_두 트랙은 독립적으로 매겨진다")
        void ranks_perTrack_areIndependent() {
            // 1번: 시즌 +20%, 일간 -4%.  2번: 시즌 +10%, 일간 +10%.
            RankingSnapshot snapshot = rank(null,
                    valuation(1, "20.00", "-4.00"), valuation(2, "10.00", "10.00"));

            assertThat(snapshot.bySeasonRank()).extracting(Entry::accountId).containsExactly(1L, 2L);
            assertThat(snapshot.byDailyRank()).extracting(Entry::accountId).containsExactly(2L, 1L);
            assertThat(snapshot.byAccountId().get(1L).dailyRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("평가액 순위_금액 내림차순이고_시드가 같으면 시즌 순위와 같다")
        void valuationRank_isByAmountDesc() {
            RankingSnapshot snapshot = rank(null, season(1, "5.00"), season(2, "20.00"), season(3, "-3.00"));

            assertThat(snapshot.byValuationRank()).extracting(Entry::accountId).containsExactly(2L, 1L, 3L);
            assertThat(snapshot.byValuationRank()).extracting(Entry::accountId)
                    .isEqualTo(snapshot.bySeasonRank().stream().map(Entry::accountId).toList());
        }

        /**
         * 시작 잔고가 다르면 두 트랙이 갈린다 — 중간 합류자는 소급 인덱스 잔고를 받으므로
         * 적은 돈으로 높은 수익률을 낼 수 있다. 이 갈림이 평가액 트랙을 따로 둔 이유다.
         */
        @Test
        @DisplayName("평가액 순위_시작 잔고가 다르면_시즌 순위와 갈린다")
        void valuationRank_withDifferentStartingBalance_divergesFromSeason() {
            // 1번: 1억 시드로 1억 1,000만. 2번: 늦게 합류해 7,000만으로 시작, 지금 1억 500만(+50%).
            RankingSnapshot snapshot = rank(null,
                    entry(1, "10.00", "0.00", "110000000"),
                    entry(2, "50.00", "0.00", "105000000"));

            assertThat(snapshot.bySeasonRank()).extracting(Entry::accountId).containsExactly(2L, 1L);
            assertThat(snapshot.byValuationRank()).extracting(Entry::accountId).containsExactly(1L, 2L);
            assertThat(snapshot.byAccountId().get(1L).valuationRank()).isEqualTo(1);
            assertThat(snapshot.byAccountId().get(1L).seasonRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("평가액 순위_금액이 같으면_같은 순위를 주고 다음을 건너뛴다")
        void valuationRank_ties_shareRankAndSkipNext() {
            RankingSnapshot snapshot = rank(null,
                    entry(1, "10.00", "0.00", "110000000"),
                    entry(2, "20.00", "0.00", "110000000"),
                    entry(3, "5.00", "0.00", "105000000"));

            assertThat(snapshot.byAccountId().get(1L).valuationRank()).isEqualTo(1);
            assertThat(snapshot.byAccountId().get(2L).valuationRank()).isEqualTo(1);
            assertThat(snapshot.byAccountId().get(3L).valuationRank()).isEqualTo(3);
        }

        @Test
        @DisplayName("평가액 등락_직전 틱 대비로 매겨진다")
        void valuationDelta_againstPreviousTick() {
            RankingSnapshot previous = rank(null,
                    entry(1, "0.00", "0.00", "110000000"), entry(2, "0.00", "0.00", "100000000"));
            RankingSnapshot now = rank(previous,
                    entry(1, "0.00", "0.00", "100000000"), entry(2, "0.00", "0.00", "120000000"));

            assertThat(now.byAccountId().get(2L).valuationRank()).isEqualTo(1);
            assertThat(now.byAccountId().get(2L).valuationRankDelta()).isEqualTo(1);
            assertThat(now.byAccountId().get(1L).valuationRankDelta()).isEqualTo(-1);
        }

        @Test
        @DisplayName("행_스윕이 낸 수익률과 평가액을 그대로 싣는다")
        void entry_carriesSweepValuesUnchanged() {
            RankingSnapshot snapshot = rank(null, valuation(1, "12.34", "-1.87"));

            Entry entry = snapshot.byAccountId().get(1L);
            assertThat(entry.seasonReturn()).isEqualByComparingTo("12.34");
            assertThat(entry.dailyReturn()).isEqualByComparingTo("-1.87");
            assertThat(entry.totalValuationKrw()).isEqualByComparingTo("112340000");
            assertThat(entry.userId()).isEqualTo(10L);
        }
    }

    @Test
    @DisplayName("참가자 수_순위에 든 계좌 수다")
    void totalParticipants_countsRankedAccounts() {
        RankingSnapshot snapshot = rank(null, season(1, "1.00"), season(2, "2.00"), season(3, "3.00"));

        assertThat(snapshot.totalParticipants()).isEqualTo(3);
    }

    @Test
    @DisplayName("계좌가 없으면_빈 순위다")
    void rank_noAccounts_isEmpty() {
        RankingSnapshot snapshot = rank(null);

        assertThat(snapshot.totalParticipants()).isZero();
        assertThat(snapshot.bySeasonRank()).isEmpty();
    }

    private RankingSnapshot rank(RankingSnapshot previous, AccountValuation... valuations) {
        return calculator.rank(1L, DATE, (short) 1, List.of(valuations), previous);
    }

    /** 일간 수익률을 시즌과 같게 두어 시즌 트랙만 의미 있는 계좌. */
    private static AccountValuation season(long accountId, String cumulativeReturn) {
        return valuation(accountId, cumulativeReturn, cumulativeReturn);
    }

    /**
     * 총평가액은 시드 1억에 시즌 수익률을 얹은 값으로 둔다 — 전원 같은 시드라는 기본 상황이고,
     * 그때 평가액 트랙은 시즌 트랙과 순서가 같다. 둘을 갈라 놓고 싶으면 {@link #entry} 를 쓴다.
     */
    private static AccountValuation valuation(long accountId, String cumulativeReturn, String dailyReturn) {
        BigDecimal total = new BigDecimal("100000000")
                .add(new BigDecimal(cumulativeReturn).multiply(new BigDecimal("1000000")));
        return entry(accountId, cumulativeReturn, dailyReturn, total.toPlainString());
    }

    /** 수익률과 평가액을 따로 준다. 시작 잔고가 사람마다 다른 상황을 만들 때 쓴다. */
    private static AccountValuation entry(long accountId, String cumulativeReturn, String dailyReturn,
                                          String totalValuationKrw) {
        return new AccountValuation(accountId, accountId * 10, BigDecimal.ZERO,
                new BigDecimal(totalValuationKrw),
                new BigDecimal(cumulativeReturn), new BigDecimal(dailyReturn), null, null);
    }
}
