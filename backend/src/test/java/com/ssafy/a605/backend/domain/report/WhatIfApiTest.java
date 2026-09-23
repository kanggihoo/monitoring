package com.ssafy.a605.backend.domain.report;

import static org.hamcrest.Matchers.hasSize;
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
 * '만약에' 시뮬레이터를 HTTP 경계에서 본다.
 *
 * <p>기대값은 픽스처 종가에서 손으로 계산한다 — 종가가 {@code 10,000 ± tick × 100} 이라
 * 산식이 코드와 같은 실수를 반복하지 않는다.
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
class WhatIfApiTest {

    private static final String URI = "/api/v1/reports/season/what-if";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 980L;

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

    private User owner;
    private String session;
    private long krwInstrument;
    private long usdInstrument;
    private long ipoInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        owner = fixture.user("만약에");
        session = sessions.createSessionFor(owner);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        usdInstrument = instrumentId("REPLAY_TEST_C");
        ipoInstrument = instrumentId("REPLAY_TEST_D");
        givenAccount();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM daily_holdings WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("세션이 없으면_401 을 받는다")
        void withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(URI).param("instrumentId", "1"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("instrumentId 가 없으면_400 을 받는다")
        void withoutInstrumentId_returnsBadRequest() throws Exception {
            givenAnnounced(1);

            mockMvc.perform(get(URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("유니버스에 없는 종목이면_404 를 받는다")
        void withUnknownInstrument_returnsNotFound() throws Exception {
            givenAnnounced(1);

            mockMvc.perform(whatIf(999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("없는 종목입니다."));
        }

        @Test
        @DisplayName("아직 발표된 일차가 없으면_409 를 받는다")
        void beforeAnyAnnouncement_returnsConflict() throws Exception {
            // 1일차 폐장은 지났지만 발표 전이다. 그 시점엔 무엇을 물어도 답이 없어야 한다.
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.CLOSE), ReplayFixture.START,
                    BigDecimal.ZERO, 4L, 1, ReplayFixture.CLOSE);

            mockMvc.perform(whatIf(krwInstrument))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("정산 발표 후에 열람할 수 있습니다."));
        }
    }

    @Nested
    @DisplayName("일차별 비교")
    class PerDay {

        @Test
        @DisplayName("1일차만 발표됐으면_그 일차만 담기고 시즌 값은 비어 있다")
        void afterFirstDay_hasOnlyThatDay() throws Exception {
            givenSettlement(1, "4.21", "-1.50");
            givenAnnounced(1);

            // 1일차 구간 종가: 10,100(t1) -> 9,600(t4). (9600-10100)/10100 = -4.95%
            mockMvc.perform(whatIf(krwInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentId").value(krwInstrument))
                    .andExpect(jsonPath("$.data.days", hasSize(1)))
                    .andExpect(jsonPath("$.data.days[0].seasonDay").value(1))
                    .andExpect(jsonPath("$.data.days[0].returnRate").value(-4.95))
                    .andExpect(jsonPath("$.data.days[0].myReturnRate").value(4.21))
                    // 3일차 발표 전이라 "3일차까지 보유했다면" 을 낼 수 없다.
                    .andExpect(jsonPath("$.data.returnRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.myReturnRate").value(nullValue()));
        }

        @Test
        @DisplayName("일차마다 구간이 갈리고_같은 축으로 비교된다")
        void eachDayUsesItsOwnWindow() throws Exception {
            givenSettlement(1, "4.21", "-1.50");
            givenSettlement(2, "-2.87", "1.20");
            givenSettlement(3, "8.10", "13.20");
            givenAnnounced(3);

            // 2일차: 10,500(t5) -> 9,200(t8) = -12.38% / 3일차: 10,900(t9) -> 8,800(t12) = -19.27%
            mockMvc.perform(whatIf(krwInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.days", hasSize(3)))
                    .andExpect(jsonPath("$.data.days[1].returnRate").value(-12.38))
                    .andExpect(jsonPath("$.data.days[1].myReturnRate").value(-2.87))
                    .andExpect(jsonPath("$.data.days[2].returnRate").value(-19.27))
                    // 시즌은 누적끼리 비교한다 - 10,100(t1) -> 8,800(t12) = -12.87%
                    .andExpect(jsonPath("$.data.returnRate").value(-12.87))
                    // 내 성과는 참가한 일차의 일간 수익률 합이다 - 4.21 - 2.87 + 8.10 = 9.44.
                    // season_return(13.20)은 초기화 이후 3일차 하루 값이라 쓰지 않는다.
                    .andExpect(jsonPath("$.data.myReturnRate").value(9.44));
        }

        @Test
        @DisplayName("그 일차에 참가하지 않았으면_내 수익률만 비어 있다")
        void withoutMySettlement_leavesOnlyMyRateNull() throws Exception {
            // 중간 합류자다. 종목 수익률은 계좌와 무관하므로 그대로 나온다.
            givenSettlement(2, "-2.87", "1.20");
            givenAnnounced(2);

            mockMvc.perform(whatIf(krwInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.days[0].seasonDay").value(1))
                    .andExpect(jsonPath("$.data.days[0].returnRate").value(-4.95))
                    .andExpect(jsonPath("$.data.days[0].myReturnRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.days[1].myReturnRate").value(-2.87));
        }
    }

    @Nested
    @DisplayName("환율과 상장일")
    class CurrencyAndListing {

        @Test
        @DisplayName("달러 종목은_양 끝 환율로 환산돼 원화 종목과 값이 갈린다")
        void usdInstrument_isConvertedAtBothEnds() throws Exception {
            givenSettlement(3, "8.10", "13.20");
            givenAnnounced(3);

            // 3일차 환율이 양 끝에서 다르다 - t9 는 1,210, t12 는 1,200.
            // 10,900 x 1,210 = 13,189,000 -> 8,800 x 1,200 = 10,560,000 = -19.93%
            // 환산하지 않으면 원화 종목과 같은 -19.27% 가 나온다.
            mockMvc.perform(whatIf(usdInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.days[2].returnRate").value(-19.93));
        }

        @Test
        @DisplayName("상장 전 종가가 있어도_상장일부터 계산한다")
        void ipoInstrument_startsAtListingDate() throws Exception {
            // 검증 종목 D 는 상장일(2020-01-03) 이전인 2020-01-02 에도 종가가 있다. 종가 존재로
            // 판정하면 그 값을 시작점으로 잡아 -4.95% 가 나온다 - 살 수 없었던 날이다.
            // 상장일부터면 9,800(t2) -> 9,600(t4) = -2.04% 다.
            givenSettlement(1, "4.21", "-1.50");
            givenAnnounced(1);

            mockMvc.perform(whatIf(ipoInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.days[0].returnRate").value(-2.04));
        }
    }

    private MockHttpServletRequestBuilder whatIf(long instrumentId) {
        return get(URI)
                .header(AUTH_TOKEN_HEADER, session)
                .param("instrumentId", String.valueOf(instrumentId));
    }

    /** 그 일차의 정산 발표가 끝난 상태. 열람 전용 구간에 시계를 세운다. */
    private void givenAnnounced(int seasonDay) {
        fixture.store(seasonDay, BigDecimal.valueOf(ReplayFixture.ANNOUNCE), ReplayFixture.START,
                BigDecimal.ZERO, 4L, seasonDay, ReplayFixture.ANNOUNCE);
    }

    private void givenAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, owner.getId(), ReplayFixture.SEASON_ID);
    }

    /** 폐장 확정 행. 비교 대상이 되는 두 수익률만 의미가 있다. */
    private void givenSettlement(int seasonDay, String dailyReturn, String seasonReturn) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, 1, 1, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 100000000,
                        0, 0, 0, NULL, NULL, CAST('[]' AS JSONB), now())
                """, seasonDay, ACCOUNT_ID, seasonReturn, dailyReturn);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
