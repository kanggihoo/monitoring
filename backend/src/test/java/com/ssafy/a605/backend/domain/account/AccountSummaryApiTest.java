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
 * HTTP 경계에서 계좌 요약의 관찰 가능한 결과만 본다.
 *
 * <p>편성·시세는 {@code R__replay_fixture.sql} 이 심은 검증용 시즌을 그대로 쓴다 — 계좌 요약이
 * 평가 기준으로 삼는 것이 재생이 공개한 틱이므로, 재생과 다른 픽스처를 세우면 두 코드가 서로
 * 다른 세계를 검증하게 된다. 여기서 만드는 것은 계좌와 포지션뿐이다.
 *
 * <p><b>배속 0 으로 고정한 상태에서만 조회한다.</b> 폴러는 실제 타이머로 돌지만 배속이 0 이면
 * 도달한 항목이 없어 아무것도 진행하지 않는다. 그래서 공개 기준 틱을 테스트가 정확히 쥐고
 * 대기 없이 검증할 수 있다.
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
class AccountSummaryApiTest {

    private static final String SUMMARY_URI = "/api/v1/accounts/me";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 900L;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200. */
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
    private long usdInstrument;
    private long carriedInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("계좌주인");
        session = sessions.createSessionFor(user);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        carriedInstrument = instrumentId("REPLAY_TEST_B");
        usdInstrument = instrumentId("REPLAY_TEST_C");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("계좌 요약_세션이 없으면_401 을 받는다")
        void getSummary_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(SUMMARY_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("계좌 요약_시즌에 참가하지 않았으면_404 를 받는다")
        void getSummary_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(summary())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }
    }

    @Nested
    @DisplayName("최초 공개 전 - 1일차 브리핑")
    class BeforeFirstPublish {

        @Test
        @DisplayName("계좌 요약_공개된 틱이 없으면_틱 관련 세 필드가 null 이고 원화 현금이 총평가액이다")
        void getSummary_beforeFirstPublish_nullsTickFields() throws Exception {
            givenAccount("100000000", "0", "0", "0");

            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(nullValue()))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(nullValue()))
                    .andExpect(jsonPath("$.data.usdKrwRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("100000000"))
                    .andExpect(jsonPath("$.data.holdingsValuationKrw").value("0"))
                    .andExpect(jsonPath("$.data.cumulativeReturn").value(0.00))
                    .andExpect(jsonPath("$.data.dailyReturn").value(0.00));
        }

        @Test
        @DisplayName("계좌 요약_공개된 틱이 없는데 달러 현금이 있으면_500 으로 드러난다")
        void getSummary_beforeFirstPublishWithUsd_returnsServerError() throws Exception {
            givenAccount("100000000", "12000", "0", "0");

            mockMvc.perform(summary())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("공개된 종가가 없어 평가액을 계산할 수 없습니다."));
        }
    }

    @Nested
    @DisplayName("공개 기준 틱")
    class AsOfPublishedTick {

        @Test
        @DisplayName("계좌 요약_틱 하나가 공개되면_그 틱의 거래일과 환율이 기준이 된다")
        void getSummary_afterFirstPublish_usesThatTick() throws Exception {
            givenAccount();
            givenPublishedTick(FIRST_TICK);

            // 45,000,000 + 12,000 x 1200 = 59,400,000
            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.usdKrwRate").value("1200.00"))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("59400000"))
                    .andExpect(jsonPath("$.data.cumulativeReturn").value(-40.60));
        }

        @Test
        @DisplayName("계좌 요약_다음 틱이 공개되면_기준과 환율이 그 틱으로 옮겨간다")
        void getSummary_afterSecondPublish_movesToIt() throws Exception {
            givenAccount();
            givenPublishedTick(SECOND_TICK);

            // 45,000,000 + 12,000 x 1210 = 59,520,000
            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(SECOND_TICK))
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-03"))
                    .andExpect(jsonPath("$.data.usdKrwRate").value("1210.00"))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("59520000"));
        }
    }

    @Nested
    @DisplayName("평가")
    class Valuation {

        @Test
        @DisplayName("계좌 요약_현금과 잠긴 금액_통화 자릿수의 문자열로 온다")
        void getSummary_amounts_areStringsScaledByCurrency() throws Exception {
            givenAccount();
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.krwCash").value("45000000"))
                    .andExpect(jsonPath("$.data.usdCash").value("12000.00"))
                    .andExpect(jsonPath("$.data.lockedKrw").value("3000000"))
                    .andExpect(jsonPath("$.data.lockedUsd").value("2553.83"))
                    .andExpect(jsonPath("$.data.startingBalanceKrw").value("100000000"));
        }

        @Test
        @DisplayName("계좌 요약_달러 보유_그 틱의 환율로 원화 환산된다")
        void getSummary_usdPosition_convertsWithTickRate() throws Exception {
            givenAccount();
            givenPosition(usdInstrument, 10, "80000.0000");
            givenPublishedTick(FIRST_TICK);

            // 검증 종목 C 의 2020-01-02 종가 10,100 (USD) x 10 x 1200 = 121,200,000
            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.holdingsValuationKrw").value("121200000"))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("180600000"));
        }

        @Test
        @DisplayName("계좌 요약_기준 거래일에 종가가 없는 종목_직전 거래일 종가로 평가된다")
        void getSummary_positionWithoutCloseOnAsOfDate_usesLastClose() throws Exception {
            givenAccount();
            givenPosition(carriedInstrument, 10, "90000.0000");
            givenPublishedTick(SECOND_TICK);

            // 검증 종목 B 는 2020-01-03 종가가 없다. 직전 거래일 10,100 x 10 = 101,000
            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.holdingsValuationKrw").value("101000"))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("59621000"));
        }

        @Test
        @DisplayName("계좌 요약_기준 거래일 뒤의 종가가 있어도_평가에 쓰이지 않는다")
        void getSummary_futureClose_isNotUsed() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            givenPublishedTick(FIRST_TICK);

            // 2020-01-02 종가 10,100 x 10 = 101,000. 다음 거래일 9,800 을 집었다면 98,000 이 된다.
            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.holdingsValuationKrw").value("101000"))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("59501000"));
        }

        @Test
        @DisplayName("계좌 요약_수익률_시작 잔고와 당일 기준값 대비로 계산된다")
        void getSummary_returns_areAgainstStoredBases() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 10, "90000.0000");
            jdbc.update("UPDATE accounts SET daily_base_valuation_krw = 50000000 WHERE id = ?", ACCOUNT_ID);
            givenPublishedTick(FIRST_TICK);

            // 총평가액 59,501,000. 시작 잔고 1억 대비 -40.50%, 당일 기준값 5,000 만 대비 +19.00%
            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("59501000"))
                    .andExpect(jsonPath("$.data.cumulativeReturn").value(-40.50))
                    .andExpect(jsonPath("$.data.dailyReturn").value(19.00));
        }

        @Test
        @DisplayName("계좌 요약_수량 0 인 포지션_평가에서 빠진다")
        void getSummary_zeroQuantityPosition_isIgnored() throws Exception {
            givenAccount();
            givenPosition(krwInstrument, 0, "0.0000");
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(summary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.holdingsValuationKrw").value("0"));
        }

        @Test
        @DisplayName("계좌 요약_보유 종목의 종가가 없으면_500 으로 드러난다")
        void getSummary_positionWithoutAnyClose_returnsServerError() throws Exception {
            givenAccount();
            // 유니버스 시드 종목은 검증용 시즌에 종가가 적재되어 있지 않다.
            givenPosition(instrumentId("NQX"), 10, "90000.0000");
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(summary())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message")
                            .value("보유 종목의 시세가 없어 평가액을 계산할 수 없습니다."));
        }
    }

    private MockHttpServletRequestBuilder summary() {
        return get(SUMMARY_URI).header(AUTH_TOKEN_HEADER, session);
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

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
