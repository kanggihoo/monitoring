package com.ssafy.a605.backend.domain.ipo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * 마감 추첨 (기획서 8.4, {@code ipo.md}). 추첨 지점을 직접 부르지 않고 <b>폴러가 커밋한 결과</b>만
 * 성공 근거로 삼는다 — {@code OrderExpiryApiTest} 와 같은 방식이다.
 *
 * <p>보는 것은 회계다: 당첨자가 정해졌는가, <b>낙첨 증거금이 전액 돌아왔는가</b>, 당첨자에게는
 * 배정하고 남은 잔액만 돌아왔는가, 배정액은 상장까지 잠긴 채인가. 이 영역의 실패는 조용히
 * 오고, 그 모습이 곧 영원히 묶인 증거금이다.
 *
 * <p>누가 당첨되는지는 무작위라 단정하지 않는다. 대신 <b>몇 명이 당첨됐는지</b>와 <b>각자의
 * 회계가 결과와 맞는지</b>를 본다.
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
class IpoDrawApiTest {

    private static final long ACCOUNT_ID = 990L;
    private static final long OTHER_ACCOUNT_ID = 991L;

    /** 국내 공모 증거금 1,000만 원과 공모가 12,000 원. 833 주가 배정되고 4,000 원이 남는다. */
    private static final String DEPOSIT = "10000000";
    private static final String OFFER_PRICE = "12000";
    private static final int ALLOCATED_QUANTITY = 833;
    private static final String ALLOCATED_AMOUNT = "9996000";
    private static final String REFUNDED_AMOUNT = "4000";

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        givenAccount(ACCOUNT_ID, fixture.user("추첨주인").getId());
        givenAccount(OTHER_ACCOUNT_ID, fixture.user("추첨상대").getId());
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
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("마감 추첨_청약자가 하나면_전원 당첨이고 배정 잔액만 돌아온다")
    void singleSubscriberWins() {
        long offeringId = givenOffering(1, 2, 4);
        givenSubscription(offeringId, ACCOUNT_ID);

        reachTick(ReplayFixture.SECOND_TICK);

        awaitDrawn(offeringId);
        assertEquals(1, winnerCount(offeringId), "당첨자 수");
        Map<String, Object> settled = subscription(offeringId, ACCOUNT_ID);
        assertEquals(Boolean.TRUE, settled.get("won"), "당첨 여부");
        assertEquals(ALLOCATED_QUANTITY, settled.get("allocated_quantity"), "배정 수량");
        assertAmount(ALLOCATED_AMOUNT, settled.get("allocated_amount"), "배정 금액");
        assertAmount(REFUNDED_AMOUNT, settled.get("refunded_amount"), "반환액");
        // 배정액은 상장까지 잠긴 채다 (기획서 8.5.2). 잔액만 쓸 수 있게 돌아온다.
        assertCash(ACCOUNT_ID, "locked_krw", ALLOCATED_AMOUNT);
        // 추첨은 돈을 옮기지 않는다. 총 현금은 그대로다.
        assertCash(ACCOUNT_ID, "krw_cash", "45000000");
    }

    @Test
    @DisplayName("마감 추첨_둘이 청약하면_하나만 당첨되고 낙첨은 전액 돌려받는다")
    void oneOfTwoWins() {
        long offeringId = givenOffering(1, 2, 4);
        givenSubscription(offeringId, ACCOUNT_ID);
        givenSubscription(offeringId, OTHER_ACCOUNT_ID);

        reachTick(ReplayFixture.SECOND_TICK);

        awaitDrawn(offeringId);
        // 둘의 30% 를 반올림하면 1 명이다 (기획서 8.4.2).
        assertEquals(1, winnerCount(offeringId), "당첨자 수");
        assertSettled(offeringId, ACCOUNT_ID);
        assertSettled(offeringId, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("마감 추첨_청약자가 없으면_당첨자 0 으로 확정된다")
    void withoutSubscribers_drawsNobody() {
        long offeringId = givenOffering(1, 2, 4);

        reachTick(ReplayFixture.SECOND_TICK);

        // 편성된 공모는 청약자가 없어도 상장한다. 추첨만 비어 있다.
        awaitDrawn(offeringId);
        assertEquals(0, winnerCount(offeringId), "당첨자 수");
    }

    @Test
    @DisplayName("마감 추첨_이미 뽑힌 공모는_다시 뽑히지 않는다")
    void alreadyDrawn_isNotDrawnAgain() {
        long offeringId = givenOffering(1, 2, 4);
        givenSubscription(offeringId, ACCOUNT_ID);

        reachTick(ReplayFixture.SECOND_TICK);
        awaitDrawn(offeringId);
        Object drawnAt = drawnAt(offeringId);
        BigDecimal locked = cash(ACCOUNT_ID, "locked_krw");

        // 다음 틱의 스윕이 같은 공모를 다시 잡으면 잠금이 두 번 풀려 없던 현금이 생긴다.
        clock.set(fixture.realTimeAt(ReplayFixture.THIRD_TICK));
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());

        assertEquals(drawnAt, drawnAt(offeringId), "추첨 시각");
        assertEquals(0, locked.compareTo(cash(ACCOUNT_ID, "locked_krw")), "잠긴 원화");
    }

    /** 그 틱까지 재생시킨다. 폴러가 실제 타이머로 돌므로 결과는 기다려서 본다. */
    private void reachTick(int offset) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, null, 1, ReplayFixture.TRADING_OPEN);
        clock.set(fixture.realTimeAt(offset));
    }

    private void awaitDrawn(long offeringId) {
        ReplayFixture.awaitUntil(() -> drawnAt(offeringId) != null,
                () -> "공모 %d 가 추첨되기를 기다렸으나 여전히 추첨 전이다".formatted(offeringId));
    }

    /**
     * 당락과 회계가 서로 맞는지 본다. 누가 뽑혔는지는 무작위라 단정할 수 없지만, 뽑힌 사람과
     * 떨어진 사람의 장부는 각각 하나의 모습이어야 한다.
     */
    private void assertSettled(long offeringId, long accountId) {
        Map<String, Object> settled = subscription(offeringId, accountId);
        assertNotNull(settled.get("won"), "추첨 결과");
        if ((Boolean) settled.get("won")) {
            assertEquals(ALLOCATED_QUANTITY, settled.get("allocated_quantity"), "배정 수량");
            assertAmount(REFUNDED_AMOUNT, settled.get("refunded_amount"), "당첨 반환액");
            assertCash(accountId, "locked_krw", ALLOCATED_AMOUNT);
            return;
        }
        assertEquals(0, settled.get("allocated_quantity"), "낙첨 배정 수량");
        // 낙첨은 전액이 즉시 돌아온다. 상장일 매매 자금으로 바로 쓸 수 있어야 한다 (8.4.4).
        assertAmount(DEPOSIT, settled.get("refunded_amount"), "낙첨 반환액");
        assertCash(accountId, "locked_krw", "0");
    }

    private Map<String, Object> subscription(long offeringId, long accountId) {
        return jdbc.queryForMap(
                "SELECT * FROM ipo_subscriptions WHERE offering_id = ? AND account_id = ?",
                offeringId, accountId);
    }

    private Object drawnAt(long offeringId) {
        return jdbc.queryForObject("SELECT drawn_at FROM ipo_offerings WHERE id = ?",
                java.sql.Timestamp.class, offeringId);
    }

    private int winnerCount(long offeringId) {
        return jdbc.queryForObject("SELECT winner_count FROM ipo_offerings WHERE id = ?",
                Integer.class, offeringId);
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

    /** 신청 경로를 거치지 않고 잠긴 상태를 그대로 만든다. 여기서 보는 것은 추첨이다. */
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
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID);
    }

    private BigDecimal cash(long accountId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM accounts WHERE id = ?",
                BigDecimal.class, accountId);
    }

    private void assertCash(long accountId, String column, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(cash(accountId, column)), column);
    }

    private static void assertAmount(String expected, Object actual, String label) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), label);
    }

    private long instrumentId() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM instruments WHERE symbol = ?",
                Long.class, "REPLAY_TEST_D");
        return ids.getFirst();
    }
}
