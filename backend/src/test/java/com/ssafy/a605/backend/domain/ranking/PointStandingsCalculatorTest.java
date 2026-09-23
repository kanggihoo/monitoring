package com.ssafy.a605.backend.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.PointStanding;
import com.ssafy.a605.backend.domain.ranking.service.PointStandingsCalculator;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 포인트 합산 순위 ({@code S15P21A605-250} 확정 규칙).
 *
 * <p><b>점수표가 SQL 안에 있어 컨테이너로 본다.</b> {@code CASE WHEN} 을 자바로 옮기면 계좌
 * 전부를 메모리로 올려야 하므로 집계를 DB 에 맡겼고, 그래서 점수의 정확성은 실제 질의를
 * 돌려야만 확인된다.
 *
 * <p>일간 순위는 <b>확정 행에 적힌 값</b>이라 원하는 등수를 직접 넣는다 — 4 등·10 등·11 등을
 * 보려고 계좌 11 개를 만들 이유가 없다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class PointStandingsCalculatorTest {

    private static final long FIRST_ACCOUNT = 940L;

    @Autowired
    private PointStandingsCalculator calculator;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReplayFixture fixture;

    @BeforeEach
    void setUp() {
        clean();
        calculator.clear();
    }

    @AfterEach
    void tearDown() {
        clean();
        calculator.clear();
    }

    @Test
    @DisplayName("점수표_1·2·3등과 4~10등 감소, 11등부터 10점")
    void points_followTheConfirmedTable() {
        givenSettled(account(0), 1, 1, 5, "10");
        givenSettled(account(1), 1, 2, 5, "9");
        givenSettled(account(2), 1, 3, 5, "8");
        givenSettled(account(3), 1, 4, 5, "7");
        givenSettled(account(4), 1, 10, 5, "6");
        givenSettled(account(5), 1, 11, 5, "5");

        assertThat(calculator.standings()).extracting(PointStanding::accountId, PointStanding::points)
                .containsExactly(
                        tuple(account(0), 100L),
                        tuple(account(1), 80L),
                        tuple(account(2), 65L),
                        tuple(account(3), 50L),
                        tuple(account(4), 20L),
                        tuple(account(5), 10L));
    }

    /** 가입만 하고 아무것도 하지 않은 계좌에 점수가 쌓이지 않게 하는 것이 이 규칙의 목적이다. */
    @Test
    @DisplayName("그날 체결 0건_등수와 무관하게 0점")
    void noTrade_scoresZero() {
        givenSettled(account(0), 1, 1, 0, "0");
        givenSettled(account(1), 1, 2, 3, "-5");

        assertThat(calculator.standings()).extracting(PointStanding::accountId, PointStanding::points)
                .containsExactly(
                        tuple(account(1), 80L),
                        tuple(account(0), 0L));
    }

    /**
     * 그날 전원이 손실을 보면 아무 거래도 하지 않은 계좌가 0.00 % 로 일간 1 등이 된다.
     * 점수가 0 인데 1 등 횟수만 쌓이면 그 횟수가 동점을 가르는 두 번째 기준이라 실제 거래자를
     * 제친다 — 시상도 아무것도 하지 않은 사람에게 간다.
     */
    @Test
    @DisplayName("미거래로 일간 1등이 되면_1등 횟수로 세지 않는다")
    void noTradeTopRank_isNotAWin() {
        givenSettled(account(0), 1, 1, 0, "0");
        givenSettled(account(1), 1, 2, 4, "-3");

        assertThat(calculator.standings()).extracting(PointStanding::accountId, PointStanding::wins)
                .containsExactly(tuple(account(1), 0L), tuple(account(0), 0L));
    }

    /** 굵은 점수 단위라 서로 다른 경로가 같은 합에 도달하기 쉽다 (80+65+50 = 100+50+45 = 195). */
    @Test
    @DisplayName("점수가 같으면_일간 1등 횟수가 많은 쪽이 앞")
    void samePoints_splitByWins() {
        // 195 점: 1등 한 번 + 4등 + 5등
        givenSettled(account(0), 1, 1, 2, "10");
        givenSettled(account(0), 2, 4, 2, "10");
        givenSettled(account(0), 3, 5, 2, "10");
        // 195 점: 2등 + 3등 + 4등 (1등 없음). 수익률 합은 더 높다
        givenSettled(account(1), 1, 2, 2, "50");
        givenSettled(account(1), 2, 3, 2, "50");
        givenSettled(account(1), 3, 4, 2, "50");

        List<PointStanding> standings = calculator.standings();

        assertThat(standings).extracting(PointStanding::points).containsExactly(195L, 195L);
        // 수익률 합이 낮아도 1등 횟수가 앞선다 — 순서가 뒤집히면 동점 기준이 뒤바뀐 것이다
        assertThat(standings).extracting(PointStanding::accountId).containsExactly(account(0), account(1));
        assertThat(standings).extracting(PointStanding::wins).containsExactly(1L, 0L);
    }

    @Test
    @DisplayName("점수와 1등 횟수가 같으면_일간 수익률 합계가 높은 쪽이 앞")
    void samePointsAndWins_splitByDailyReturnSum() {
        givenSettled(account(0), 1, 2, 2, "10");
        givenSettled(account(1), 1, 2, 2, "30");

        assertThat(calculator.standings()).extracting(PointStanding::accountId)
                .containsExactly(account(1), account(0));
    }

    /** 세 기준이 모두 같으면 공동 시상이 결론이고, 순위 표기는 다음 등수를 건너뛴다. */
    @Test
    @DisplayName("세 기준이 모두 같으면_같은 순위를 주고 다음 순위를 건너뛴다")
    void fullTie_sharesRankAndSkipsNext() {
        givenSettled(account(0), 1, 2, 2, "10");
        givenSettled(account(1), 1, 2, 2, "10");
        givenSettled(account(2), 1, 5, 2, "10");

        assertThat(calculator.standings()).extracting(PointStanding::accountId, PointStanding::rank)
                .containsExactly(
                        tuple(account(0), 1),
                        tuple(account(1), 1),
                        tuple(account(2), 3));
    }

    /** 첫 폐장 전에는 점수가 정말로 없다 — 전원 0 점으로 나열하지 않는다. */
    @Test
    @DisplayName("확정된 일차가 없으면_빈 목록")
    void beforeFirstClose_isEmpty() {
        assertThat(calculator.standings()).isEmpty();
    }

    /** 점수가 바뀌는 순간은 폐장 정산 하나뿐이고, 그때 다시 집계해야 새 점수가 보인다. */
    @Test
    @DisplayName("폐장 정산 뒤_다음 조회에 새 점수가 반영된다")
    void afterFinalized_isRecalculated() {
        givenSettled(account(0), 1, 1, 2, "10");
        assertThat(calculator.standings()).extracting(PointStanding::points).containsExactly(100L);

        givenSettled(account(0), 2, 1, 2, "10");
        // 무효화 전에는 직전 집계 그대로다
        assertThat(calculator.standings()).extracting(PointStanding::points).containsExactly(100L);

        events.publishEvent(new DailySettlementFinalizedEvent((short) 2, 1));

        assertThat(calculator.standings()).extracting(PointStanding::points).containsExactly(200L);
    }

    private static long account(int index) {
        return FIRST_ACCOUNT + index;
    }

    private void givenSettled(long accountId, int seasonDay, int dailyRank, int executionCount, String dailyReturn) {
        givenAccount(accountId);
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, ?, ?, 0, CAST(? AS NUMERIC), 100000000, ?, 0, 0, '[]'::jsonb, now())
                """, seasonDay, accountId, dailyRank, dailyRank, dailyReturn, executionCount);
    }

    /** 계좌마다 주인이 다르다 — accounts 에 (user_id, season_id) UNIQUE 가 있다. */
    private void givenAccount(long accountId) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Integer.class, accountId);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, fixture.user("포인트참가자" + accountId).getId(), ReplayFixture.SEASON_ID);
    }

    private void clean() {
        jdbc.update("DELETE FROM daily_holdings WHERE account_id >= ?", FIRST_ACCOUNT);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id >= ?", FIRST_ACCOUNT);
        jdbc.update("DELETE FROM accounts WHERE id >= ?", FIRST_ACCOUNT);
    }
}
