package com.ssafy.a605.backend.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 폐장 자동 취소 (기획서 6.2.4). 경계 처리 지점을 직접 부르지 않고 <b>폴러가 커밋한 결과</b>만
 * 성공 근거로 삼는다 — {@code OrderFillApiTest} 와 같은 방식이다.
 *
 * <p>보는 것은 회계다: 예약이 닫혔는가, <b>잠긴 현금이 돌아왔는가</b>, 총 현금은 그대로인가.
 * 이 영역의 실패는 예외 없이 조용히 오고, 그 모습이 곧 다음 날까지 묶여 있는 현금이다.
 *
 * <p>{@code order.expired} 전달은 {@code OrderRealtimeApiTest} 가 본다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class OrderExpiryApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 970L;
    private static final long OTHER_ACCOUNT_ID = 971L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private User owner;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());
        owner = fixture.user("만료주인");
    }

    @AfterEach
    void tearDown() {
        // 다음 테스트가 정지 상태에서 시작하도록 폴러를 먼저 멈춘다.
        clock.set(ReplayFixture.START);
        // 시계를 되돌려도 진행 중인 한 번은 끝까지 간다. 폐장 정산이 그 안에서 행을 다시
        // 넣으면 아래의 삭제가 FK 로 막히므로, 가라앉기를 먼저 기다린다.
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());
        // 폐장을 넘기므로 폐장 스냅샷이 남는다. 계좌와 체결을 참조하니 먼저 지운다.
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM orders WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("폐장 자동 취소_매수 예약이 남아 있으면_잠긴 현금이 반환된다")
    void expiresBuyOrderAndReleasesCash() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "27000");
        // 1일차 종가는 10,100 · 9,800 · 10,300 · 9,600 이다. 지정가 9,000 이면 어떤 틱에도
        // 체결되지 않아 폐장까지 살아남는다.
        long orderId = givenOpenBuyOrder(ACCOUNT_ID, "REPLAY_TEST_A", 3, "9000", "27000");

        reachClose();

        awaitStatus(orderId, "EXPIRED");
        assertCash("locked_krw", "0");
        // 총 현금은 그대로다. 잠금은 돈을 옮긴 적이 없다.
        assertCash("krw_cash", "45000000");
    }

    @Test
    @DisplayName("폐장 자동 취소_매도 예약이면_잠긴 수량이 풀리고 포지션은 그대로다")
    void expiresSellOrderKeepingPosition() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "0");
        givenPosition(ACCOUNT_ID, "REPLAY_TEST_A", 4, "36000.0000");
        // 지정가 99,999 라 그날 어떤 틱에도 체결되지 않는다 — 폐장까지 살아남아야 한다.
        long orderId = givenOpenSellOrder(ACCOUNT_ID, "REPLAY_TEST_A", 4, "99999");

        reachClose();

        awaitStatus(orderId, "EXPIRED");
        // 포지션은 건드리지 않는다. 매도 잠금은 orders 가 들고 있었고 상태가 바뀌며 풀린다.
        assertEquals(4, jdbc.queryForObject("""
                SELECT quantity FROM positions WHERE account_id = ? AND instrument_id = ?
                """, Integer.class, ACCOUNT_ID, instrumentId("REPLAY_TEST_A")), "보유 수량");
    }

    @Test
    @DisplayName("폐장 자동 취소_이미 체결된 예약은_다시 닫히지 않는다")
    void doesNotTouchFilledOrders() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "10100");
        // 첫 틱 종가 10,100 이라 그 틱에 체결된다. 폐장이 그것을 또 닫으면 잠금이 두 번 풀린다.
        long orderId = givenOpenBuyOrder(ACCOUNT_ID, "REPLAY_TEST_A", 1, "10100", "10100");

        reachClose();

        awaitStatus(orderId, "FILLED");
        assertCash("locked_krw", "0");
        // 체결로 10,100 이 나갔다. 폐장이 한 번 더 풀었다면 이 값이 45,000,000 이 된다.
        assertCash("krw_cash", "44989900");
    }

    @Test
    @DisplayName("폐장 자동 취소_다른 사람의 예약도_각자 자기 계좌에서 닫힌다")
    void expiresEachAccountIndependently() throws Exception {
        User other = fixture.user("다른만료주인");
        givenAccount(ACCOUNT_ID, owner.getId(), "27000");
        givenAccount(OTHER_ACCOUNT_ID, other.getId(), "18000");
        long mine = givenOpenBuyOrder(ACCOUNT_ID, "REPLAY_TEST_A", 3, "9000", "27000");
        long theirs = givenOpenBuyOrder(OTHER_ACCOUNT_ID, "REPLAY_TEST_A", 2, "9000", "18000");

        reachClose();

        awaitStatus(mine, "EXPIRED");
        awaitStatus(theirs, "EXPIRED");
        assertCash("locked_krw", "0");
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT locked_krw FROM accounts WHERE id = ?", BigDecimal.class, OTHER_ACCOUNT_ID)),
                "남의 잠금");
    }

    @Test
    @DisplayName("폐장 자동 취소_사용자가 이미 취소한 예약은_CANCELLED 로 남는다")
    void keepsUserCancellationDistinct() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "0");
        // 사용자 취소와 폐장 만료를 상태로 나눠 둔 것이 설계 결정이다 — 마음을 바꾼 것과
        // 시간이 다 된 것은 복기에서 구분되어야 한다 (기획서 6.2.4).
        long cancelled = givenClosedOrder(ACCOUNT_ID, "CANCELLED");

        reachClose();
        // 폐장 처리가 확실히 지나가도록 다른 예약 하나로 만료를 관찰한다.
        ReplayFixture.awaitQuiet(() -> status(cancelled));

        assertEquals("CANCELLED", status(cancelled), "사용자 취소가 만료로 덮이지 않는다");
    }

    @Test
    @DisplayName("폐장 자동 취소_미체결이 하나도 없으면_아무 예약도 닫히지 않는다")
    void withoutOpenOrders_closesNothing() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "0");
        long filled = givenClosedOrder(ACCOUNT_ID, "FILLED");

        reachClose();
        ReplayFixture.awaitQuiet(() -> status(filled));

        // 후보가 비면 스윕이 계좌를 읽지도 않는다. 닫힌 행이 다시 닫히면 여기서 드러난다.
        assertEquals("FILLED", status(filled), "닫힌 예약 상태");
        assertCash("locked_krw", "0");
    }

    /**
     * 폐장 경계까지 진행시킨다. 1일차 틱 넷과 그 앞의 경계들이 함께 지나가므로, 폐장까지
     * 살아남는 예약은 그동안 체결되지 않은 것뿐이다.
     */
    private void reachClose() throws Exception {
        speed(1);
        clock.set(fixture.realTimeAt(ReplayFixture.CLOSE));
    }

    private void speed(int speed) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.patch(REPLAY_URI)
                        .header(AUTH_TOKEN_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":" + speed + "}"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    /** 폴러는 실제 타이머로 도니 경계 처리가 그 자리에서 보이지 않는다. 상태가 될 때까지 기다린다. */
    private void awaitStatus(long orderId, String expected) {
        ReplayFixture.awaitUntil(() -> expected.equals(status(orderId)),
                () -> "예약 %d 가 %s 가 되기를 기다렸으나 %s 다".formatted(orderId, expected, status(orderId)));
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private void assertCash(String column, String expected) {
        BigDecimal actual = jdbc.queryForObject(
                "SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column);
    }

    private void givenAccount(long accountId, long userId, String lockedKrw) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, CAST(? AS NUMERIC), 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID, lockedKrw);
    }

    private void givenPosition(long accountId, String symbol, int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, CAST(? AS NUMERIC))
                """, accountId, instrumentId(symbol), quantity, totalCost);
    }

    /** 닫힌 예약. 잠금 컬럼은 CHECK 가 OPEN 만 규정하므로 비워 둔다. */
    private long givenClosedOrder(long accountId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at, closed_at)
                VALUES (?, ?, 'BUY', 9000, 1, 'KRW', NULL, NULL, ?,
                        now() + interval '1 hour', 1, now(), now())
                RETURNING id
                """, Long.class, accountId, instrumentId("REPLAY_TEST_A"), status);
    }

    private long givenOpenBuyOrder(long accountId, String symbol, int quantity, String limitPrice,
                                   String lockedAmount) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'BUY', CAST(? AS NUMERIC), ?, 'KRW', CAST(? AS NUMERIC), NULL, 'OPEN',
                        now() + interval '1 hour', 1, now())
                RETURNING id
                """, Long.class, accountId, instrumentId(symbol), limitPrice, quantity, lockedAmount);
    }

    private long givenOpenSellOrder(long accountId, String symbol, int quantity, String limitPrice) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'SELL', CAST(? AS NUMERIC), ?, 'KRW', NULL, ?, 'OPEN',
                        now() + interval '1 hour', 1, now())
                RETURNING id
                """, Long.class, accountId, instrumentId(symbol), limitPrice, quantity, quantity);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
