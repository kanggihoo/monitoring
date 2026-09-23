package com.ssafy.a605.backend.domain.account;

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
 * 부재 중 요약 (기획서 7.5).
 *
 * <p>보는 것은 <b>판정과 구간</b>이다: 확인한 틱과 현재 틱의 차이로만 부재를 가르는가, 놓친
 * 구간이 확인한 지점 <b>다음</b>부터인가, 그리고 확인 처리가 뒤로 가지 않는가.
 *
 * <p>재생을 굴리지 않고 공개된 틱을 고정한다 — 여기서 보는 것은 시각의 흐름이 아니라 그
 * 시점에서의 판정이다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50",
                // 픽스처의 틱이 12 개뿐이라 기본값 50 으로는 부재 구간을 만들 수 없다.
                // 보는 것은 임계치의 값이 아니라 그것을 넘겼을 때의 동작이다.
                "app.absence.threshold-ticks=5"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class AbsenceSummaryApiTest {

    private static final String SUMMARY_URI = "/api/v1/accounts/me/absence-summary";
    private static final String ACK_URI = SUMMARY_URI + "/ack";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 996L;

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

    private String ownerSession;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        User owner = fixture.user("부재주인");
        ownerSession = sessions.createSessionFor(owner);
        givenAccount(owner.getId());
    }

    @AfterEach
    void tearDown() {
        clock.set(ReplayFixture.START);
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());
        jdbc.update("DELETE FROM ipo_offerings WHERE season_id = ?", ReplayFixture.SEASON_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM orders WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Test
    @DisplayName("부재 요약_확인한 틱과의 차이가 임계치 미만이면_판정 결과만 온다")
    void withinThreshold_returnsPresentOnly() throws Exception {
        givenAcked(9);
        givenPublishedTick(12);

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(false))
                // 값이 없는 구간에 0 을 채우면 "아무 일도 없었다"와 구분되지 않는다.
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.fromTickSeq").doesNotExist())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.eventCount").doesNotExist());
    }

    @Test
    @DisplayName("부재 요약_개장 전이라 공개된 틱이 없으면_부재가 아니다")
    void beforeFirstTick_isNotAbsent() throws Exception {
        givenAcked(0);
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, null, 1, ReplayFixture.TRADING_OPEN);

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(false));
    }

    @Test
    @DisplayName("부재 요약_임계치 이상 벌어지면_구간과 놓친 것이 온다")
    void beyondThreshold_summarisesTheGap() throws Exception {
        givenAcked(1);
        // 픽스처의 마지막 틱이다. 확인한 틱 1 과의 차이가 임계치를 넘는다.
        givenPublishedTick(12);
        givenFilledOrderExecution(5);
        // 시장가 체결은 세지 않는다 — 그 자리에서 누른 것이라 놓칠 수가 없다.
        givenMarketExecution(6);

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.fromTickSeq").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.toTickSeq").value(12))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.fromGameDate").value("2020-01-02"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.toGameDate").value("2026-08-31"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.filledOrderCount").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.valuationChangeKrw").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.returnChange").exists());
    }

    /**
     * 시즌 시작 전에 만들어진 계좌는 확인한 틱이 {@code 0} 이다 ({@code sea.md} §초기값).
     *
     * <p><b>초기값 0 에 "부재 아님" 특례를 주지 않는다.</b> 특례를 주면 모달이 뜨지 않아 확인
     * 요청도 발생하지 않고 값이 {@code 0} 에 <b>영구히 고착</b>된다. 프론트는 마운트와 탭 복귀에서만
     * 조회하고 확인 요청은 모달을 닫을 때만 보내므로, 첫 탭 복귀에서 한 번 부재 판정을 받고 확인
     * 요청으로 <b>자가 치유</b>되는 것이 계약과 맞는 동작이다.
     */
    @Test
    @DisplayName("부재 요약_개장 전 생성된 계좌가 임계치 이상 경과하면_부재이고 확인 처리로 해소된다")
    void ackedAtZero_beyondThreshold_isAbsentThenHealedByAck() throws Exception {
        givenAcked(0);
        givenPublishedTick(12);

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.fromTickSeq").value(0));

        mockMvc.perform(MockMvcRequestBuilders.post(ACK_URI)
                        .header(AUTH_TOKEN_HEADER, ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackedTickSeq\":12}"))
                .andExpect(MockMvcResultMatchers.status().isNoContent());

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(false));
    }

    @Test
    @DisplayName("부재 요약_확인한 틱의 체결은_구간에 들어가지 않는다")
    void excludesTheAckedTickItself() throws Exception {
        givenAcked(1);
        givenPublishedTick(12);
        // 확인한 그 틱의 체결은 이미 본 것이다. 넣으면 같은 체결이 두 번 보고된다.
        givenFilledOrderExecution(1);

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.filledOrderCount").value(0));
    }

    @Test
    @DisplayName("부재 요약_구간에 공모 일정이 있으면_공시·마감·상장을 각각 센다")
    void countsIpoMomentsSeparately() throws Exception {
        givenAcked(1);
        givenPublishedTick(12);
        // 한 공모가 구간 안에서 세 국면을 지났다. 사용자는 세 가지를 놓친 것이다.
        givenOffering(2, 3, 4);

        // 공모 3 + 무료 타임 3 (구간이 세 일차의 무료 타임 시작을 모두 넘는다).
        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.eventCount").value(6));
    }

    @Test
    @DisplayName("부재 요약_구간이 공모 일정을 비껴가면_세지 않는다")
    void ignoresIpoOutsideTheGap() throws Exception {
        givenAcked(6);
        givenPublishedTick(12);
        // 1 일차 공모다. 구간(7~12)과 겹치지 않으므로 공모는 0 이고 무료 타임 2 만 남는다.
        givenOffering(2, 3, 4);

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.eventCount").value(2));
    }

    @Test
    @DisplayName("부재 요약_현금만 들고 있었으면_평가액이 그대로다")
    void cashOnly_hasNoChange() throws Exception {
        givenAcked(1);
        givenPublishedTick(12);

        // 가격이 움직여도 들고 있는 것이 없으면 평가액은 그대로다. 되짚기가 현금을 건드리지
        // 않는다는 뜻이기도 하다.
        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.valuationChangeKrw").value("0"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.returnChange").value(0.00));
    }

    @Test
    @DisplayName("부재 요약_보유 종목의 가격이 움직였으면_그만큼이 변동이다")
    void holdingsMovePriceIntoTheChange() throws Exception {
        givenAcked(1);
        givenPublishedTick(12);
        // 1 틱 종가 10,100 / 12 틱 종가 8,800. 10 주면 −13,000 원이다.
        givenPosition(10, "101000");

        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.valuationChangeKrw").value("-13000"))
                // 시작 잔고 1 억 대비 −0.013%p 라 소수 둘째 자리에서 −0.01 이다.
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.returnChange").value(-0.01));
    }

    @Test
    @DisplayName("부재 요약_구간 중 매수했으면_그 체결을 되돌려 시작 시점을 잡는다")
    void rewindsExecutionsInTheGap() throws Exception {
        givenAcked(1);
        givenPublishedTick(12);
        // 지금은 10 주를 들고 있지만, 그중 10 주를 구간 중(5 틱)에 샀다. 시작 시점 보유는 0 이고
        // 대신 현금이 그만큼 더 있었다 — 되짚지 않으면 산 값이 그대로 손익으로 잡힌다.
        givenPosition(10, "100000");
        givenBuyExecution(5, 10, "100000");

        mockMvc.perform(get(SUMMARY_URI))
                // 시작 평가액 = 현금 4,500 만. 지금 = 현금 4,490 만 + 10 주 x 8,800 = 45,880,000.
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.valuationChangeKrw").value("-12000"));
    }

    @Test
    @DisplayName("확인 처리_받은 틱으로 옮기고_다시 물으면 부재가 아니다")
    void ackMovesTheConfirmedPoint() throws Exception {
        givenAcked(1);
        givenPublishedTick(12);

        mockMvc.perform(MockMvcRequestBuilders.post(ACK_URI)
                        .header(AUTH_TOKEN_HEADER, ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackedTickSeq\":12}"))
                .andExpect(MockMvcResultMatchers.status().isNoContent());

        assertEquals(12L, ackedTickSeq(), "확인한 틱");
        mockMvc.perform(get(SUMMARY_URI))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.absent").value(false));
    }

    @Test
    @DisplayName("확인 처리_낡은 틱이 도착해도_뒤로 가지 않는다")
    void ackNeverMovesBackwards() throws Exception {
        givenAcked(10);
        givenPublishedTick(12);

        // 재시도나 지연으로 낡은 값이 도착하는 경우다. 이미 확인한 구간이 다시 부재가 되면 안 된다.
        mockMvc.perform(MockMvcRequestBuilders.post(ACK_URI)
                        .header(AUTH_TOKEN_HEADER, ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackedTickSeq\":3}"))
                .andExpect(MockMvcResultMatchers.status().isNoContent());

        assertEquals(10L, ackedTickSeq(), "확인한 틱");
    }

    @Test
    @DisplayName("확인 처리_틱이 없으면_400")
    void ackRequiresTick() throws Exception {
        givenPublishedTick(12);

        mockMvc.perform(MockMvcRequestBuilders.post(ACK_URI)
                        .header(AUTH_TOKEN_HEADER, ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());
    }

    private org.springframework.test.web.servlet.RequestBuilder get(String uri) {
        return MockMvcRequestBuilders.get(uri).header(AUTH_TOKEN_HEADER, ownerSession);
    }

    /** 공개된 틱을 고정한 채 거래 시간에 세운다. 시계를 굴리지 않으므로 틱이 넘어가지 않는다. */
    private void givenPublishedTick(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private void givenAcked(long tickSeq) {
        jdbc.update("UPDATE accounts SET last_acked_tick_seq = ? WHERE id = ?", tickSeq, ACCOUNT_ID);
    }

    private long ackedTickSeq() {
        return jdbc.queryForObject("SELECT last_acked_tick_seq FROM accounts WHERE id = ?",
                Long.class, ACCOUNT_ID);
    }

    /** 예약에서 온 체결. order_id 가 있는 것만 부재 요약이 센다. */
    private void givenFilledOrderExecution(long tickSeq) {
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at, closed_at)
                VALUES (?, ?, 'BUY', 10000, 1, 'KRW', NULL, NULL, 'FILLED',
                        now() + interval '1 hour', 1, now(), now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId());
        insertExecution(tickSeq, orderId);
    }

    /** 시장가 체결. order_id 가 없다. */
    private void givenMarketExecution(long tickSeq) {
        insertExecution(tickSeq, null);
    }

    private void insertExecution(long tickSeq, Long orderId) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount, applied_fee_rate,
                                        fee, realized_pnl, tick_seq, game_date, executed_at)
                SELECT ?, ?, ?, 'MARKET', 'BUY', 1, 10000, 'KRW', 10000, 0, 0, NULL, t.tick_seq,
                       t.game_date, now()
                FROM game_ticks t WHERE t.tick_seq = ?
                """, ACCOUNT_ID, instrumentId(), orderId, tickSeq);
    }

    private void givenOffering(int disclosureTick, int closeTick, int listingTick) {
        jdbc.update("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                                           deposit_amount, disclosure_tick_seq, close_tick_seq,
                                           listing_tick_seq, allocation_rule)
                VALUES (?, ?, 'KRW', 12000, 10000000, ?, ?, ?, '검증용')
                """, ReplayFixture.SEASON_ID, ipoInstrumentId(), disclosureTick, closeTick,
                listingTick);
    }

    private void givenPosition(int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, CAST(? AS NUMERIC))
                """, ACCOUNT_ID, instrumentId(), quantity, totalCost);
    }

    /** 구간 중의 매수 체결. 현금이 빠지고 수량이 늘어난 사건이다. */
    private void givenBuyExecution(long tickSeq, int quantity, String amount) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount, applied_fee_rate,
                                        fee, realized_pnl, tick_seq, game_date, executed_at)
                SELECT ?, ?, NULL, 'MARKET', 'BUY', ?, 10000, 'KRW', CAST(? AS NUMERIC), 0, 0, NULL,
                       t.tick_seq, t.game_date, now()
                FROM game_ticks t WHERE t.tick_seq = ?
                """, ACCOUNT_ID, instrumentId(), quantity, amount, tickSeq);
        jdbc.update("UPDATE accounts SET krw_cash = krw_cash - CAST(? AS NUMERIC) WHERE id = ?",
                amount, ACCOUNT_ID);
    }

    private void givenAccount(long userId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, userId, ReplayFixture.SEASON_ID);
    }

    private long instrumentId() {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class,
                "REPLAY_TEST_A");
    }

    private long ipoInstrumentId() {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class,
                "REPLAY_TEST_D");
    }
}
