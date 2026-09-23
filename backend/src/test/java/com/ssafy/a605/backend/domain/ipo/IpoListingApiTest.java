package com.ssafy.a605.backend.domain.ipo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 상장 전환 (기획서 8.5.2). 추첨 지점과 마찬가지로 스윕을 직접 부르지 않고 <b>폴러가 커밋한
 * 결과</b>만 성공 근거로 삼는다.
 *
 * <p>보는 것은 회계다: 잠겨 있던 배정액이 <b>현금에서 빠져 포지션이 됐는가</b>, 평단이 공모가가
 * 됐는가, 낙첨자에게는 아무 일도 일어나지 않았는가. 청약부터 상장까지 계좌를 통과하는 돈의
 * 총량은 한 번도 어긋나면 안 된다 — 어긋나면 없던 현금이 생기거나 영원히 잠긴다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class IpoListingApiTest {

    private static final long ACCOUNT_ID = 992L;
    private static final long OTHER_ACCOUNT_ID = 993L;

    /** 증거금 1,000만 원, 공모가 12,000 원 → 833 주 배정, 9,996,000 원 지출, 4,000 원 반환. */
    private static final String DEPOSIT = "10000000";
    private static final String OFFER_PRICE = "12000";
    private static final int ALLOCATED_QUANTITY = 833;
    private static final String ALLOCATED_AMOUNT = "9996000";
    private static final String SEED = "45000000";
    private static final String CASH_AFTER_LISTING = "35004000";

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        givenAccount(ACCOUNT_ID, fixture.user("상장주인").getId());
        givenAccount(OTHER_ACCOUNT_ID, fixture.user("상장상대").getId());
    }

    @AfterEach
    void tearDown() {
        clock.set(ReplayFixture.START);
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());
        jdbc.update("DELETE FROM ipo_subscriptions WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM ipo_offerings WHERE season_id = ?", ReplayFixture.SEASON_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("상장 전환_당첨 물량이_포지션이 되고 잠금이 풀린다")
    void convertsAllocationIntoPosition() {
        long offeringId = givenOffering(1, 2, 3);
        givenSubscription(offeringId, ACCOUNT_ID);

        reachTick(ReplayFixture.THIRD_TICK);

        awaitConverted(ACCOUNT_ID);
        Map<String, Object> position = position(ACCOUNT_ID);
        assertEquals(ALLOCATED_QUANTITY, position.get("quantity"), "배정 수량");
        // 취득 총액이 배정액이므로 평단이 자동으로 공모가가 된다 (12,000 x 833).
        assertAmount(ALLOCATED_AMOUNT, position.get("total_cost"), "취득 총액");
        // 여기서 처음으로 현금이 실제로 빠진다. 잠금은 그만큼 풀린다.
        assertCash(ACCOUNT_ID, "krw_cash", CASH_AFTER_LISTING);
        assertCash(ACCOUNT_ID, "locked_krw", "0");
    }

    @Test
    @DisplayName("상장 전환_낙첨자에게는_포지션도 지출도 없다")
    void leavesLosersUntouched() {
        long offeringId = givenOffering(1, 2, 3);
        givenSubscription(offeringId, ACCOUNT_ID);
        givenSubscription(offeringId, OTHER_ACCOUNT_ID);

        reachTick(ReplayFixture.THIRD_TICK);

        ReplayFixture.awaitUntil(this::anyPositionExists,
                () -> "당첨자의 배정 물량이 포지션이 되기를 기다렸으나 오지 않았다");
        // 둘 중 하나만 당첨된다. 누구인지는 무작위라 장부의 모양으로 가른다.
        assertEquals(1, positionCount(), "포지션을 받은 계좌 수");
        for (long accountId : List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID)) {
            boolean won = won(offeringId, accountId);
            assertCash(accountId, "krw_cash", won ? CASH_AFTER_LISTING : SEED);
            // 당첨이든 낙첨이든 잠금은 남지 않는다.
            assertCash(accountId, "locked_krw", "0");
        }
    }

    @Test
    @DisplayName("상장 전환_다음 틱이 지나가도_한 번만 전환된다")
    void convertsOnce() {
        long offeringId = givenOffering(1, 2, 3);
        givenSubscription(offeringId, ACCOUNT_ID);

        reachTick(ReplayFixture.THIRD_TICK);
        awaitConverted(ACCOUNT_ID);

        // 상장 틱에만 후보가 잡히므로 뒤따르는 틱은 같은 공모를 건드리지 않는다.
        clock.set(fixture.realTimeAt(ReplayFixture.LAST_TICK_DAY_1_2));
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());

        assertEquals(ALLOCATED_QUANTITY, position(ACCOUNT_ID).get("quantity"), "배정 수량");
        assertCash(ACCOUNT_ID, "krw_cash", CASH_AFTER_LISTING);
    }

    @Test
    @DisplayName("상장 전환_추첨 전이면_아무것도 옮기지 않는다")
    void withoutDraw_convertsNothing() {
        // 마감이 상장보다 늦게 편성된 공모다. 배정이 아직 없으므로 옮길 것도 없다.
        long offeringId = givenOffering(1, 4, 3);
        givenSubscription(offeringId, ACCOUNT_ID);

        reachTick(ReplayFixture.THIRD_TICK);
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());

        assertTrue(jdbc.queryForList("SELECT 1 FROM positions WHERE account_id = ?", ACCOUNT_ID)
                .isEmpty(), "포지션");
        // 증거금은 잠긴 채 남는다. 추첨이 오기 전에 풀리면 안 된다.
        assertCash(ACCOUNT_ID, "locked_krw", DEPOSIT);
    }

    /** 그 틱까지 재생시킨다. 폴러가 실제 타이머로 도니 결과는 기다려서 본다. */
    private void reachTick(int offset) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, null, 1, ReplayFixture.TRADING_OPEN);
        clock.set(fixture.realTimeAt(offset));
    }

    /** 전환 여부를 표시하는 컬럼이 없으므로 결과(포지션)로 관찰한다. */
    private void awaitConverted(long accountId) {
        ReplayFixture.awaitUntil(
                () -> !jdbc.queryForList("SELECT 1 FROM positions WHERE account_id = ?", accountId)
                        .isEmpty(),
                () -> "계좌 %d 의 배정 물량이 포지션이 되기를 기다렸으나 오지 않았다".formatted(accountId));
    }

    private boolean won(long offeringId, long accountId) {
        Boolean won = jdbc.queryForObject(
                "SELECT won FROM ipo_subscriptions WHERE offering_id = ? AND account_id = ?",
                Boolean.class, offeringId, accountId);
        assertNotNull(won, "추첨 결과");
        return won;
    }

    private Map<String, Object> position(long accountId) {
        return jdbc.queryForMap("SELECT * FROM positions WHERE account_id = ?", accountId);
    }

    private boolean anyPositionExists() {
        return positionCount() > 0;
    }

    private int positionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM positions WHERE account_id IN (?, ?)",
                Integer.class, ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    private long givenOffering(int disclosureTick, int closeTick, int listingTick) {
        return jdbc.queryForObject("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                                           deposit_amount, disclosure_tick_seq, close_tick_seq,
                                           listing_tick_seq, allocation_rule)
                VALUES (?, ?, 'KRW', CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, ?, ?)
                RETURNING id
                """, Long.class, ReplayFixture.SEASON_ID, instrumentId(), OFFER_PRICE, DEPOSIT,
                disclosureTick, closeTick, listingTick,
                "청약자 수의 30%를 무작위 추첨한다. 모든 청약자의 당첨 확률은 같다.");
    }

    /** 신청 경로를 거치지 않고 잠긴 상태를 그대로 만든다. 여기서 보는 것은 상장 전환이다. */
    private void givenSubscription(long offeringId, long accountId) {
        jdbc.update("""
                INSERT INTO ipo_subscriptions (offering_id, account_id, deposit_amount, subscribed_at)
                VALUES (?, ?, CAST(? AS NUMERIC), now())
                """, offeringId, accountId, DEPOSIT);
        jdbc.update("UPDATE accounts SET locked_krw = locked_krw + CAST(? AS NUMERIC) WHERE id = ?",
                DEPOSIT, accountId);
    }

    private void givenAccount(long accountId, long userId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID, SEED);
    }

    private void assertCash(long accountId, String column, String expected) {
        BigDecimal actual = jdbc.queryForObject("SELECT " + column + " FROM accounts WHERE id = ?",
                BigDecimal.class, accountId);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column + " (" + accountId + ")");
    }

    private static void assertAmount(String expected, Object actual, String label) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), label);
    }

    private long instrumentId() {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class,
                "REPLAY_TEST_D");
    }
}
