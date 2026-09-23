package com.ssafy.a605.backend.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 체결 내역 조회만 본다.
 *
 * <p>체결 행은 대부분 <b>직접 심는다.</b> 이 테스트가 검증할 것은 회계가 아니라 필터·정렬·페이지
 * 경계이고, 주문을 내서 만들면 그 셋을 흔들려고 종가·잔고를 맞추는 데 시간이 다 간다. 회계는
 * {@code OrderPlacementApiTest} 가 본다.
 *
 * <p>다만 <b>{@code game_date} 가 실제로 저장되는지</b>는 주문을 내서 확인한다. 그 값을 쓰는 것이
 * 이 조회이므로, 심은 행으로만 검증하면 저장 경로가 비어 있어도 테스트가 통과한다.
 *
 * <p>픽스처 편성: 1일차가 틱 1~4, 2일차가 틱 5~8, 3일차가 틱 9~12 다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                // 픽스처를 여기서 켠다. 선언하지 않으면 .env 의 REPLAY_FIXTURE_ENABLED 가
                // 결과를 정하고, 그 파일은 버전 관리되지 않아 사람마다 다른 DB 를 본다.
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class ExecutionHistoryApiTest {

    private static final String EXECUTIONS_URI = "/api/v1/executions";
    private static final String ORDERS_URI = "/api/v1/orders";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 923L;
    private static final long OTHER_ACCOUNT_ID = 924L;

    /** 1일차 첫 틱. 게임 거래일 2020-01-02, 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;

    /** 2일차 첫 틱. 게임 거래일 2022-04-01. */
    private static final long SECOND_DAY_TICK = 5L;

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

    private User user;
    private String session;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("내역주인");
        session = sessions.createSessionFor(user);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("체결 내역_세션이 없으면_401 을 받는다")
        void searchExecutions_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(EXECUTIONS_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("체결 내역_시즌에 참가하지 않았으면_404 를 받는다")
        void searchExecutions_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(executions())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }

        @Test
        @DisplayName("체결 내역_남의 체결은_보이지 않는다")
        void searchExecutions_othersExecutions_areHidden() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            User other = fixture.user("남");
            givenAccount(OTHER_ACCOUNT_ID, other.getId());
            givenExecution(OTHER_ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);

            mockMvc.perform(executions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        @DisplayName("체결 내역_거래 시간이 아니어도_열람할 수 있다")
        void searchExecutions_outsideTrading_returnsOk() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.BRIEFING), ReplayFixture.START,
                    BigDecimal.ZERO, FIRST_TICK, 1, ReplayFixture.BRIEFING);

            // 기획서 0장 시간표가 열람 전용 시간에도 내역 조회를 허용한다.
            mockMvc.perform(executions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    @Nested
    @DisplayName("항목과 정렬")
    class Content {

        @Test
        @DisplayName("체결 내역_매도 체결이면_실현손익과 요율이 저장값으로 온다")
        void searchExecutions_sellExecution_carriesStoredAccounting() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "SELL", FIRST_TICK, "2020-01-02", "76.95");

            mockMvc.perform(executions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].executionId").isNumber())
                    .andExpect(jsonPath("$.data.content[0].orderType").value("MARKET"))
                    .andExpect(jsonPath("$.data.content[0].side").value("SELL"))
                    .andExpect(jsonPath("$.data.content[0].quantity").value(3))
                    // 달러 종목이라 가격·금액이 두 자리다 (priceScale 2).
                    .andExpect(jsonPath("$.data.content[0].executedPrice").value("902.10"))
                    .andExpect(jsonPath("$.data.content[0].currency").value("USD"))
                    .andExpect(jsonPath("$.data.content[0].amount").value("2706.30"))
                    .andExpect(jsonPath("$.data.content[0].appliedFeeRate").value(0.15))
                    .andExpect(jsonPath("$.data.content[0].fee").value("4.06"))
                    .andExpect(jsonPath("$.data.content[0].realizedPnl").value("76.95"))
                    .andExpect(jsonPath("$.data.content[0].gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.content[0].tickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.content[0].executedAt").isString());
        }

        @Test
        @DisplayName("체결 내역_매수 체결이면_실현손익이 없고 수수료가 0 이다")
        void searchExecutions_buyExecution_hasNoRealizedPnl() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);

            // 원화 종목이라 자릿수가 0 이고, 매수에는 수수료가 없다.
            mockMvc.perform(executions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].side").value("BUY"))
                    .andExpect(jsonPath("$.data.content[0].executedPrice").value("902"))
                    .andExpect(jsonPath("$.data.content[0].appliedFeeRate").value(0.00))
                    .andExpect(jsonPath("$.data.content[0].fee").value("0"))
                    .andExpect(jsonPath("$.data.content[0].realizedPnl").doesNotExist());
        }

        @Test
        @DisplayName("체결 내역_여러 건이면_최신순으로 온다")
        void searchExecutions_multiple_returnsNewestFirst() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "SELL", SECOND_DAY_TICK, "2022-04-01", "10.00");

            mockMvc.perform(executions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.content[0].gameDate").value("2022-04-01"))
                    .andExpect(jsonPath("$.data.content[1].gameDate").value("2020-01-02"));
        }

        @Test
        @DisplayName("체결 내역_페이지 크기를 1 로 주면_한 건과 다음 페이지 표시가 온다")
        void searchExecutions_withPageSize_paginates() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "SELL", SECOND_DAY_TICK, "2022-04-01", "10.00");

            mockMvc.perform(executions().param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.size").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.content[0].gameDate").value("2022-04-01"));
        }
    }

    @Nested
    @DisplayName("필터")
    class Filters {

        @Test
        @DisplayName("체결 내역_종목으로 거르면_그 종목만 온다")
        void searchExecutions_byInstrument_filters() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "BUY", FIRST_TICK, "2020-01-02", null);

            mockMvc.perform(executions().param("instrumentId", String.valueOf(instrumentId("REPLAY_TEST_C"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].currency").value("USD"));
        }

        @Test
        @DisplayName("체결 내역_일차로 거르면_그 일차의 틱 구간만 온다")
        void searchExecutions_bySeasonDay_filters() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "SELL", SECOND_DAY_TICK, "2022-04-01", "10.00");

            // 체결에 season_day 컬럼이 없어 편성에서 그 일차의 틱 범위를 구해 거른다.
            mockMvc.perform(executions().param("seasonDay", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].tickSeq").value(FIRST_TICK));

            mockMvc.perform(executions().param("seasonDay", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].tickSeq").value(SECOND_DAY_TICK));
        }

        @Test
        @DisplayName("체결 내역_종목과 일차를 함께 주면_둘 다 적용된다")
        void searchExecutions_byInstrumentAndSeasonDay_filtersBoth() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "BUY", FIRST_TICK, "2020-01-02", null);
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "SELL", SECOND_DAY_TICK, "2022-04-01", "10.00");

            mockMvc.perform(executions()
                            .param("instrumentId", String.valueOf(instrumentId("REPLAY_TEST_C")))
                            .param("seasonDay", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].tickSeq").value(FIRST_TICK));
        }

        @Test
        @DisplayName("체결 내역_편성에 없는 일차를 주면_전체가 아니라 빈 목록이 온다")
        void searchExecutions_byUnknownSeasonDay_returnsEmpty() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, "2020-01-02", null);

            // 범위가 비었다고 필터를 버리면 전체가 나간다 — 있지도 않은 일차를 물었는데
            // 다른 일차의 체결이 보이면 안 된다.
            mockMvc.perform(executions().param("seasonDay", "9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Nested
    @DisplayName("게임 거래일 저장")
    class GameDateStorage {

        @Test
        @DisplayName("시장가 체결_저장되면_그 틱의 게임 거래일이 행에 함께 남는다")
        void placeOrder_storesGameDateOnTheRow() throws Exception {
            givenAccount(ACCOUNT_ID, user.getId());
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                    BigDecimal.ZERO, FIRST_TICK, 1, ReplayFixture.TRADING_OPEN);

            mockMvc.perform(post(ORDERS_URI)
                            .header(AUTH_TOKEN_HEADER, session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderType\":\"MARKET\",\"side\":\"BUY\",\"instrumentId\":%d,\"quantity\":1}"
                                    .formatted(instrumentId("REPLAY_TEST_A"))))
                    .andExpect(status().isCreated());

            // ord.md 가 gameDate 를 '저장'으로 표기한다. 조인으로 만들어 넣으면 이 단언이 깨진다.
            LocalDate stored = jdbc.queryForObject(
                    "SELECT game_date FROM executions WHERE account_id = ?", LocalDate.class, ACCOUNT_ID);
            assertEquals(LocalDate.parse("2020-01-02"), stored, "체결 행의 게임 거래일");
        }
    }

    private MockHttpServletRequestBuilder executions() {
        return get(EXECUTIONS_URI).header(AUTH_TOKEN_HEADER, session);
    }

    private void givenAccount(long accountId, Long userId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 12000, 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID);
    }

    /**
     * 체결 한 행을 직접 심는다. 회계가 아니라 필터·정렬을 보는 테스트라, 주문을 내서 만들면
     * 종가·잔고를 맞추는 데 시간이 다 간다.
     *
     * <p><b>심는 행도 계약을 따른다.</b> 매수는 수수료가 없으므로 요율과 수수료를 0 으로
     * 넣는다 — 쓰기 경로가 만들 수 없는 상태를 읽기 테스트가 전제하면, 존재할 수 없는
     * 데이터를 검증하는 테스트가 된다.
     */
    private void givenExecution(long accountId, String symbol, String side, long tickSeq,
                                String gameDate, String realizedPnl) {
        boolean sell = "SELL".equals(side);
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_type, side, quantity,
                                        executed_price, currency, amount, applied_fee_rate, fee,
                                        realized_pnl, tick_seq, game_date, executed_at)
                VALUES (?, ?, 'MARKET', ?, 3, 902.10, ?, 2706.30, ?::numeric, ?::numeric,
                        ?::numeric, ?, ?::date, now())
                """, accountId, instrumentId(symbol), side,
                symbol.equals("REPLAY_TEST_C") ? "USD" : "KRW",
                sell ? "0.15" : "0", sell ? "4.06" : "0",
                realizedPnl, tickSeq, gameDate);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
