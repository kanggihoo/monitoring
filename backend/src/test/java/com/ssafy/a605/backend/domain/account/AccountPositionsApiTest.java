package com.ssafy.a605.backend.domain.account;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
 * HTTP 경계에서 보유 포지션의 관찰 가능한 결과만 본다.
 *
 * <p>편성·시세는 {@code R__replay_fixture.sql} 이 심은 검증용 시즌을 그대로 쓴다 — 포지션이
 * 평가 기준으로 삼는 것이 재생이 공개한 틱이므로, 재생과 다른 픽스처를 세우면 두 코드가 서로
 * 다른 세계를 검증하게 된다. 여기서 만드는 것은 계좌·포지션·예약 주문뿐이다.
 *
 * <p>검증 종목의 자릿수가 갈려 있어 세 경로를 모두 밟는다 — A·B 는 원화에 {@code priceScale}
 * 0, C 는 달러에 2 다.
 *
 * <p><b>배속 0 으로 고정한 상태에서만 조회한다.</b> 폴러는 실제 타이머로 돌지만 배속이 0 이면
 * 도달한 항목이 없어 아무것도 진행하지 않는다.
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
class AccountPositionsApiTest {

    private static final String POSITIONS_URI = "/api/v1/accounts/me/positions";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 910L;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200, 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03, 환율 1210. 검증 종목 B 는 이 날 종가가 없다. */
    private static final long SECOND_TICK = 2L;

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
    private long krwInstrument;
    private long carriedInstrument;
    private long usdInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("포지션주인");
        session = sessions.createSessionFor(user);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        carriedInstrument = instrumentId("REPLAY_TEST_B");
        usdInstrument = instrumentId("REPLAY_TEST_C");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM orders WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("보유 포지션_세션이 없으면_401 을 받는다")
        void getPositions_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(POSITIONS_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("보유 포지션_시즌에 참가하지 않았으면_404 를 받는다")
        void getPositions_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(positions())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }
    }

    @Nested
    @DisplayName("목록 구성")
    class Listing {

        @Test
        @DisplayName("보유 포지션_공개된 틱이 없으면_틱 관련 세 필드가 null 이고 목록이 비어 있다")
        void getPositions_beforeFirstPublish_returnsEmptyList() throws Exception {
            givenAccount("100000000", "0", "0", "0");

            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(nullValue()))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(nullValue()))
                    .andExpect(jsonPath("$.data.usdKrwRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.positions").isArray())
                    .andExpect(jsonPath("$.data.positions.length()").value(0));
        }

        @Test
        @DisplayName("보유 포지션_보유가 없으면_기준 틱은 오고 목록만 비어 있다")
        void getPositions_withoutHoldings_returnsTickWithEmptyList() throws Exception {
            givenAccount();
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.usdKrwRate").value("1200.00"))
                    .andExpect(jsonPath("$.data.positions.length()").value(0));
        }

        @Test
        @DisplayName("보유 포지션_수량 0 인 포지션_목록에서 빠진다")
        void getPositions_zeroQuantity_isExcluded() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 0, "0.0000");
            givenPosition(usdInstrument, 10, "80000.0000");
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions.length()").value(1))
                    .andExpect(jsonPath("$.data.positions[0].instrumentId").value(usdInstrument));
        }

        @Test
        @DisplayName("보유 포지션_보유 종목의 종가가 없으면_500 으로 드러난다")
        void getPositions_withoutAnyClose_returnsServerError() throws Exception {
            givenAccount();
            // 유니버스 시드 종목은 검증용 시즌에 종가가 적재되어 있지 않다.
            givenPosition(instrumentId("NQX"), 10, "90000.0000");
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(positions())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message")
                            .value("보유 종목의 시세가 없어 평가액을 계산할 수 없습니다."));
        }
    }

    @Nested
    @DisplayName("평가와 평단")
    class Valuation {

        @Test
        @DisplayName("보유 포지션_원화 종목_평단이 취득 총액 나누기 수량으로 온다")
        void getPositions_krwInstrument_derivesAvgPrice() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            givenPublishedTick(FIRST_TICK);

            // 평단 90,000 / 10 = 9,000. 종가 10,100 x 10 = 101,000. 손익 11,000 (12.22%)
            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions[0].currency").value("KRW"))
                    .andExpect(jsonPath("$.data.positions[0].quantity").value(10))
                    .andExpect(jsonPath("$.data.positions[0].totalCost").value("90000"))
                    .andExpect(jsonPath("$.data.positions[0].avgPrice").value("9000"))
                    .andExpect(jsonPath("$.data.positions[0].currentPrice").value("10100"))
                    .andExpect(jsonPath("$.data.positions[0].valuation").value("101000"))
                    .andExpect(jsonPath("$.data.positions[0].valuationKrw").value("101000"))
                    .andExpect(jsonPath("$.data.positions[0].unrealizedPnl").value("11000"))
                    .andExpect(jsonPath("$.data.positions[0].unrealizedPnlKrw").value("11000"))
                    .andExpect(jsonPath("$.data.positions[0].returnRate").value(12.22));
        }

        @Test
        @DisplayName("보유 포지션_달러 종목_현지 통화와 원화 손익이 함께 온다")
        void getPositions_usdInstrument_reportsBothCurrencies() throws Exception {
            givenAccount();
            givenPosition(usdInstrument, 10, "80000.0000");
            givenPublishedTick(FIRST_TICK);

            // 평단 8,000.00 달러. 평가액 101,000.00 달러 x 1200 = 121,200,000 원.
            // 손익 21,000.00 달러 = 25,200,000 원 (26.25%)
            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions[0].currency").value("USD"))
                    .andExpect(jsonPath("$.data.positions[0].totalCost").value("80000.00"))
                    .andExpect(jsonPath("$.data.positions[0].avgPrice").value("8000.00"))
                    .andExpect(jsonPath("$.data.positions[0].currentPrice").value("10100.00"))
                    .andExpect(jsonPath("$.data.positions[0].valuation").value("101000.00"))
                    .andExpect(jsonPath("$.data.positions[0].valuationKrw").value("121200000"))
                    .andExpect(jsonPath("$.data.positions[0].unrealizedPnl").value("21000.00"))
                    .andExpect(jsonPath("$.data.positions[0].unrealizedPnlKrw").value("25200000"))
                    .andExpect(jsonPath("$.data.positions[0].returnRate").value(26.25));
        }

        @Test
        @DisplayName("보유 포지션_기준 거래일에 종가가 없는 종목_직전 거래일 종가로 평가된다")
        void getPositions_withoutCloseOnAsOfDate_usesLastClose() throws Exception {
            givenAccount();
            givenPosition(carriedInstrument, 10, "90000.0000");
            givenPublishedTick(SECOND_TICK);

            // 검증 종목 B 는 2020-01-03 종가가 없다. 직전 거래일 10,100 이 유지된다.
            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-03"))
                    .andExpect(jsonPath("$.data.positions[0].currentPrice").value("10100"))
                    .andExpect(jsonPath("$.data.positions[0].valuationKrw").value("101000"));
        }

        @Test
        @DisplayName("보유 포지션_비중_현금을 포함한 총평가액 대비로 계산된다")
        void getPositions_weight_isAgainstTotalValuation() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            givenPosition(usdInstrument, 10, "80000.0000");
            givenPublishedTick(FIRST_TICK);

            // 총평가액 = 45,000,000 + 12,000 x 1200 + (101,000 + 121,200,000) = 180,701,000
            // 원화 종목 101,000 -> 0.06%, 달러 종목 121,200,000 -> 67.07%
            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions.length()").value(2))
                    .andExpect(jsonPath("$.data.positions[0].instrumentId").value(krwInstrument))
                    .andExpect(jsonPath("$.data.positions[0].weight").value(0.06))
                    .andExpect(jsonPath("$.data.positions[1].instrumentId").value(usdInstrument))
                    .andExpect(jsonPath("$.data.positions[1].weight").value(67.07));
        }
    }

    @Nested
    @DisplayName("예약 매도 잠금")
    class SellLocks {

        @Test
        @DisplayName("보유 포지션_예약 매도가 없으면_잠긴 수량이 0 이다")
        void getPositions_withoutOpenSell_reportsZeroLocked() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions[0].quantity").value(10))
                    .andExpect(jsonPath("$.data.positions[0].lockedQuantity").value(0));
        }

        @Test
        @DisplayName("보유 포지션_미체결 예약 매도_잠긴 수량이 합산되어 온다")
        void getPositions_openSellOrders_sumLockedQuantity() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            givenOpenSellOrder(krwInstrument, 3);
            givenOpenSellOrder(krwInstrument, 2);
            givenPublishedTick(FIRST_TICK);

            // 같은 종목의 예약이 각각 독립적으로 판정되므로 행이 여러 개일 수 있다.
            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions[0].quantity").value(10))
                    .andExpect(jsonPath("$.data.positions[0].lockedQuantity").value(5));
        }

        @Test
        @DisplayName("보유 포지션_취소된 예약 매도_잠긴 수량에서 빠진다")
        void getPositions_cancelledSellOrder_isNotLocked() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            givenOpenSellOrder(krwInstrument, 3);
            givenSellOrder(krwInstrument, 4, "CANCELLED");
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(positions())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.positions[0].lockedQuantity").value(3));
        }
    }

    private MockHttpServletRequestBuilder positions() {
        return get(POSITIONS_URI).header(AUTH_TOKEN_HEADER, session);
    }

    /**
     * 그 틱까지 공개된 상태로 재생을 고정한다. 배속 0 이라 폴러가 더 진행하지 않는다.
     */
    private void givenPublishedTick(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private void givenAccount() {
        givenAccount("45000000", "12000", "3000000", "2553.8300");
    }

    private void givenAccount(String krwCash, String usdCash, String lockedKrw, String lockedUsd) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                        100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, user.getId(), ReplayFixture.SEASON_ID,
                krwCash, usdCash, lockedKrw, lockedUsd);
    }

    private void givenPosition(long instrumentId, int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, CAST(? AS NUMERIC))
                """, ACCOUNT_ID, instrumentId, quantity, totalCost);
    }

    private void givenOpenSellOrder(long instrumentId, int lockedQuantity) {
        givenSellOrder(instrumentId, lockedQuantity, "OPEN");
    }

    private void givenSellOrder(long instrumentId, int lockedQuantity, String status) {
        jdbc.update("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_quantity, status, expires_at, created_tick_seq, created_at)
                VALUES (?, ?, 'SELL', 10000, ?, 'KRW', ?, ?, now(), ?, now())
                """, ACCOUNT_ID, instrumentId, lockedQuantity, lockedQuantity, status, FIRST_TICK);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
