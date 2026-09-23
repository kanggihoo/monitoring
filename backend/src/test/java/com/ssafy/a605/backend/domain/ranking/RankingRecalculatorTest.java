package com.ssafy.a605.backend.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

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

import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot.Entry;
import com.ssafy.a605.backend.domain.ranking.service.RankingRecalculator;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 틱 이벤트 한 번에 계좌 스윕 → 순위까지 이어지는지 본다.
 *
 * <p>편성·시세는 {@code R__replay_fixture.sql} 의 검증용 시즌이다 — 계좌 요약 테스트와 같은
 * 세계에서 같은 값이 나와야 한다는 것이 이 영역의 계약이다 (기획서 11.1.2). 틱 이벤트는 직접
 * 발행한다. 폴러가 틱을 커밋한 뒤 같은 객체를 발행하므로, 여기서는 그 뒤의 일만 검증한다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                // 픽스처를 여기서 켠다. 선언하지 않으면 .env 의 REPLAY_FIXTURE_ENABLED 가
                // 결과를 정하고, 그 파일은 버전 관리되지 않아 사람마다 다른 DB 를 본다.
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class RankingRecalculatorTest {

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200. 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;
    private static final LocalDate FIRST_DATE = LocalDate.of(2020, 1, 2);

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03, 환율 1210. 검증 종목 종가 9,800. */
    private static final long SECOND_TICK = 2L;
    private static final LocalDate SECOND_DATE = LocalDate.of(2020, 1, 3);

    private static final long ACCOUNT_A = 910L;
    private static final long ACCOUNT_B = 911L;
    private static final long ACCOUNT_C = 912L;

    @Autowired
    private RankingRecalculator recalculator;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private User ownerA;
    private User ownerB;
    private User ownerC;
    private long krwInstrument;
    private long usdInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        // 1인 1계좌 UNIQUE 라 계좌마다 다른 사용자가 필요하다.
        ownerA = fixture.user("랭킹참가자A");
        ownerB = fixture.user("랭킹참가자B");
        ownerC = fixture.user("랭킹참가자C");
        krwInstrument = instrumentId("REPLAY_TEST_A");
        usdInstrument = instrumentId("REPLAY_TEST_C");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
    }

    @Test
    @DisplayName("틱 이벤트_스윕이 평가한 전 계좌가 그 틱의 종가·환율 기준으로 순위가 매겨진다")
    void onTick_ranksAllAccountsWithThatTicksPrices() {
        // A: 현금 1억 → 1억 (0.00%)
        givenAccount(ACCOUNT_A, "100000000", "0");
        // B: 현금 9천만 + 원화 종목 10주 x 10,100 = 90,101,000 (-9.90%)
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, krwInstrument, 10);
        // C: 현금 4천만 + 달러 1만 x 1200 + 달러 종목 5주 x 10,100 x 1200 = 112,600,000 (+12.60%)
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, usdInstrument, 5);

        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

        RankingSnapshot snapshot = recalculator.current();
        assertThat(snapshot.tickSeq()).isEqualTo(FIRST_TICK);
        assertThat(snapshot.gameDate()).isEqualTo(FIRST_DATE);
        assertThat(snapshot.seasonDay()).isEqualTo((short) 1);
        assertThat(snapshot.totalParticipants()).isEqualTo(3);
        assertThat(snapshot.bySeasonRank()).extracting(Entry::accountId)
                .containsExactly(ACCOUNT_C, ACCOUNT_A, ACCOUNT_B);

        Entry c = snapshot.byAccountId().get(ACCOUNT_C);
        assertThat(c.userId()).isEqualTo(ownerC.getId());
        assertThat(c.totalValuationKrw()).isEqualByComparingTo("112600000");
        assertThat(c.seasonReturn()).isEqualByComparingTo("12.60");
        assertThat(snapshot.byAccountId().get(ACCOUNT_B).seasonReturn()).isEqualByComparingTo("-9.90");
    }

    @Test
    @DisplayName("다음 틱_직전 틱과 비교한 등락이 실린다")
    void nextTick_carriesDeltaAgainstPreviousSnapshot() {
        // 첫 틱(종가 10,100): A 1억(0.00%) 1위, B 9천만+10주 = 90,101,000 2위
        // 둘째 틱(종가 9,800): 새로 들어온 C(+1.00%) 가 1위, A 는 2위로, B 는 3위로 밀린다.
        givenAccount(ACCOUNT_A, "100000000", "0");
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, krwInstrument, 10);
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
        assertThat(recalculator.current().byAccountId().get(ACCOUNT_A).seasonRank()).isEqualTo(1);

        givenAccount(ACCOUNT_C, "101000000", "0");
        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        RankingSnapshot snapshot = recalculator.current();
        assertThat(snapshot.tickSeq()).isEqualTo(SECOND_TICK);
        assertThat(snapshot.byAccountId().get(ACCOUNT_C).seasonRank()).isEqualTo(1);
        assertThat(snapshot.byAccountId().get(ACCOUNT_C).seasonRankDelta()).isZero();
        assertThat(snapshot.byAccountId().get(ACCOUNT_A).seasonRank()).isEqualTo(2);
        assertThat(snapshot.byAccountId().get(ACCOUNT_A).seasonRankDelta()).isEqualTo(-1);
        // B 의 원화 종목이 9,800 으로 내려 90,098,000. 2위에서 3위로 밀렸다.
        assertThat(snapshot.byAccountId().get(ACCOUNT_B).totalValuationKrw()).isEqualByComparingTo("90098000");
        assertThat(snapshot.byAccountId().get(ACCOUNT_B).seasonRank()).isEqualTo(3);
        assertThat(snapshot.byAccountId().get(ACCOUNT_B).seasonRankDelta()).isEqualTo(-1);
    }

    @Test
    @DisplayName("보유 종목의 종가가 없는 계좌_스윕이 건너뛰므로 그 틱의 순위에도 없다")
    void onTick_accountWithoutClose_isAbsentFromRanking() {
        givenAccount(ACCOUNT_A, "100000000", "0");
        // 유니버스 시드 종목은 검증용 시즌에 종가가 적재되어 있지 않다.
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, instrumentId("NQX"), 10);

        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

        RankingSnapshot snapshot = recalculator.current();
        assertThat(snapshot.totalParticipants()).isEqualTo(1);
        assertThat(snapshot.byAccountId()).containsKey(ACCOUNT_A).doesNotContainKey(ACCOUNT_B);
    }

    private void givenAccount(long accountId, String krwCash, String usdCash) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, ownerOf(accountId).getId(), ReplayFixture.SEASON_ID, krwCash, usdCash);
    }

    private User ownerOf(long accountId) {
        return accountId == ACCOUNT_A ? ownerA : accountId == ACCOUNT_B ? ownerB : ownerC;
    }

    /** 취득 총액은 평가에 쓰이지 않지만 수량이 있으면 0 일 수 없다 (V14 CHECK). */
    private void givenPosition(long accountId, long instrumentId, int quantity) {
        jdbc.update("INSERT INTO positions (account_id, instrument_id, quantity, total_cost) VALUES (?, ?, ?, 1)",
                accountId, instrumentId, quantity);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
