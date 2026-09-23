package com.ssafy.a605.backend.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.a605.backend.domain.market.repository.MarketPriceQueryRepository;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 현재가 스냅샷의 관찰 가능한 결과만 본다.
 *
 * <p>편성·시세는 {@code R__replay_fixture.sql} 이 심은 검증용 시즌을 그대로 쓴다 — 절단 기준이
 * 재생이 공개한 틱이므로, 재생과 다른 픽스처를 세우면 두 코드가 서로 다른 세계를 검증하게 된다.
 *
 * <p><b>배속 0 으로 고정한 상태에서만 조회한다.</b> 폴러는 실제 타이머로 돌지만 배속이 0 이면
 * 도달한 항목이 없어 아무것도 진행하지 않는다. 그래서 공개 기준 틱을 테스트가 정확히 쥐고
 * 대기 없이 검증할 수 있다.
 *
 * <p>가격 항목을 인덱스가 아니라 {@code instrumentId} 로 찾는다 — 상장 필터가 응답에서 종목을
 * 빼고 넣는 것이 이 검증의 대상이라, 인덱스로 집으면 필터가 틀렸을 때 엉뚱한 종목을 검증한다.
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
class MarketPricesApiTest {

    private static final String PRICES_URI = "/api/v1/market/prices";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200. 종가 10,100. */
    private static final long FIRST_TICK = 1L;

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03, 환율 1210. 종가 9,800. 검증 종목 B 는 이 날 종가가 없다. */
    private static final long SECOND_TICK = 2L;

    /** 보합과 직전 종가 부재를 검증하려면 종가가 움직이지 않는 종목이 필요하다. 이 테스트가 만든다. */
    private static final String FLAT_SYMBOL = "REPLAY_TEST_FLAT";

    /** 체결 건수를 심는 계좌 (S15P21A605-253). 이 테스트만 쓴다. */
    private static final long TRADER_ACCOUNT = 950L;

    /** 픽스처 2일차 첫 틱. 일차가 바뀌면 건수가 0 부터 다시 세는지 본다. */
    private static final long FIRST_TICK_DAY_2 = 5L;

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

    @Autowired
    private MarketPriceService marketPrices;

    /** 유니버스 질의가 몇 번 돌았는지 센다. 스냅샷 재사용의 유일한 관찰 지점이다. */
    @MockitoSpyBean
    private MarketPriceQueryRepository priceQuery;

    private String session;
    private long krwInstrument;
    private long carriedInstrument;
    private long usdInstrument;
    private long ipoInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        // 앞 테스트가 남긴 스냅샷이 이 테스트의 틱에 우연히 맞으면 재사용 판정이 오염된다.
        marketPrices.clearSnapshot();
        User user = fixture.user("시세구독자");
        session = sessions.createSessionFor(user);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        carriedInstrument = instrumentId("REPLAY_TEST_B");
        usdInstrument = instrumentId("REPLAY_TEST_C");
        ipoInstrument = instrumentId("REPLAY_TEST_D");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM executions WHERE account_id = ?", TRADER_ACCOUNT);
        jdbc.update("DELETE FROM accounts WHERE id = ?", TRADER_ACCOUNT);
        jdbc.update("DELETE FROM daily_closes WHERE instrument_id IN"
                + " (SELECT id FROM instruments WHERE symbol = ?)", FLAT_SYMBOL);
        jdbc.update("DELETE FROM instruments WHERE symbol = ?", FLAT_SYMBOL);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        /**
         * <b>이전 판은 같은 요청에 401 을 단언했다.</b> ADR-0005 가 그 결정을 뒤집었다.
         *
         * <p>그리고 <b>미래 차단이 인증이 지키던 것이 아님</b>을 같은 응답으로 본다 — 절단은
         * 조회 계층의 책임이고 REST 와 WS 가 같은 메서드를 쓴다. 2020-01-03 종가가 DB 에 이미
         * 있으므로, 그것을 집었다면 {@code close} 가 9,800 이 된다.
         */
        @Test
        @DisplayName("현재가 조회_세션이 없어도_200 이고_공개 기준 틱까지만 나온다")
        void getPrices_withoutSession_returnsOkAndStaysCut() throws Exception {
            givenPublishedTick(FIRST_TICK);

            String body = mockMvc.perform(get(PRICES_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(priceOf(body, krwInstrument)).containsEntry("close", "10100");
        }
    }

    /**
     * 같은 틱의 응답은 전원에게 같다. 한 번 만든 것을 재사용하고, 틱이 바뀌면 다시 만든다
     * (S15P21A605-172). 관찰 지점은 유니버스 질의의 실행 횟수다 — 응답 내용은 다른 테스트가 본다.
     */
    @Nested
    @DisplayName("스냅샷 재사용")
    class SnapshotReuse {

        @Test
        @DisplayName("현재가 조회_같은 틱을 여러 번 부르면_유니버스 질의는 한 번만 돈다")
        void getPrices_sameTickRepeatedly_queriesUniverseOnce() throws Exception {
            givenPublishedTick(FIRST_TICK);

            for (int i = 0; i < 3; i++) {
                mockMvc.perform(prices()).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK));
            }

            verify(priceQuery, times(1)).findPricesAsOf(anyLong(), any());
        }

        @Test
        @DisplayName("현재가 조회_틱이 전진하면_그 틱으로 다시 만든다")
        void getPrices_afterTickAdvances_rebuildsForNewTick() throws Exception {
            givenPublishedTick(FIRST_TICK);
            mockMvc.perform(prices()).andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK));

            givenPublishedTick(SECOND_TICK);
            mockMvc.perform(prices()).andExpect(jsonPath("$.data.asOfTickSeq").value(SECOND_TICK))
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-03"));
            mockMvc.perform(prices()).andExpect(jsonPath("$.data.asOfTickSeq").value(SECOND_TICK));

            verify(priceQuery, times(2)).findPricesAsOf(anyLong(), any());
        }

        /** 시즌 초기화가 비운다. 비우지 않으면 옛 시즌의 스냅샷이 같은 순번에 맞아 나간다. */
        @Test
        @DisplayName("현재가 조회_스냅샷을 비우면_같은 틱이라도 다시 만든다")
        void getPrices_afterClear_rebuildsSameTick() throws Exception {
            givenPublishedTick(FIRST_TICK);
            mockMvc.perform(prices()).andExpect(status().isOk());

            marketPrices.clearSnapshot();
            mockMvc.perform(prices()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK));

            verify(priceQuery, times(2)).findPricesAsOf(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("최초 공개 전 - 1일차 브리핑")
    class BeforeFirstPublish {

        @Test
        @DisplayName("현재가 조회_공개된 틱이 없으면_절단 기준이 비고 목록이 빈 배열이다")
        void getPrices_beforeFirstPublish_nullsCutoffAndEmptiesList() throws Exception {
            mockMvc.perform(prices())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(nullValue()))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(nullValue()))
                    .andExpect(jsonPath("$.data.usdKrwRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.prices").isArray())
                    .andExpect(jsonPath("$.data.prices").isEmpty());
        }
    }

    @Nested
    @DisplayName("절단 기준과 환율")
    class Cutoff {

        @Test
        @DisplayName("현재가 조회_틱 하나가 공개되면_그 틱의 거래일·순번·환율을 함께 싣는다")
        void getPrices_afterFirstPublish_carriesCutoffAndRate() throws Exception {
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(prices())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.usdKrwRate").value("1200.00"));
        }

        @Test
        @DisplayName("현재가 조회_공개 기준 틱이 전진하면_그 틱의 값으로 바뀐다")
        void getPrices_afterSecondPublish_movesToIt() throws Exception {
            givenPublishedTick(SECOND_TICK);

            mockMvc.perform(prices())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-03"))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(SECOND_TICK))
                    .andExpect(jsonPath("$.data.usdKrwRate").value("1210.00"));
        }

        @Test
        @DisplayName("현재가 조회_기준 거래일 뒤의 종가가 있어도_응답에 나타나지 않는다")
        void getPrices_futureClose_isNotUsed() throws Exception {
            givenPublishedTick(FIRST_TICK);

            // 2020-01-03 종가 9,800 이 DB 에 이미 있다. 그것을 집었다면 close 가 "9800" 이 된다.
            assertThat(priceOf(body(), krwInstrument)).containsEntry("close", "10100");
        }
    }

    @Nested
    @DisplayName("가격 표현")
    class Presentation {

        @Test
        @DisplayName("현재가 조회_자릿수가 다른 두 종목_종가와 직전 종가가 각자의 자릿수로 온다")
        void getPrices_scales_followInstrumentPriceScale() throws Exception {
            givenPublishedTick(FIRST_TICK);
            String body = body();

            assertThat(priceOf(body, krwInstrument))
                    .containsEntry("close", "10100")
                    .containsEntry("prevClose", "10000");
            assertThat(priceOf(body, usdInstrument))
                    .containsEntry("close", "10100.00")
                    .containsEntry("prevClose", "10000.00");
        }

        @Test
        @DisplayName("현재가 조회_원화 종목은 환산이 종가와 같고_달러 종목은 환율이 곱해진다")
        void getPrices_closeKrw_convertsOnlyUsd() throws Exception {
            givenPublishedTick(FIRST_TICK);
            String body = body();

            assertThat(priceOf(body, krwInstrument)).containsEntry("closeKrw", "10100");
            // 10,100 x 1,200 = 12,120,000
            assertThat(priceOf(body, usdInstrument)).containsEntry("closeKrw", "12120000");
        }

        @Test
        @DisplayName("현재가 조회_등락률_직전 종가 대비 비율로 온다")
        void getPrices_changeRate_isAgainstPreviousClose() throws Exception {
            givenPublishedTick(SECOND_TICK);

            // (9,800 - 10,100) / 10,100 x 100 = -2.97%
            assertChangeRate(priceOf(body(), krwInstrument), -2.97);
        }
    }

    @Nested
    @DisplayName("참가자 거래대금")
    class TradedAmount {

        @Test
        @DisplayName("현재가 조회_체결이 없는 종목도_0 으로 내려간다")
        void getPrices_noTrades_isZeroNotNull() throws Exception {
            givenPublishedTick(FIRST_TICK);

            assertThat(priceOf(body(), krwInstrument)).containsEntry("tradedAmountKrw", "0");
        }

        /** 원화 종목은 체결 금액 그대로, 달러 종목은 그 틱의 환율이 곱해진다. */
        @Test
        @DisplayName("현재가 조회_그 일차의 거래대금을 종목별로 더한다")
        void getPrices_sumsThatDaysAmountsPerInstrument() throws Exception {
            givenTrader();
            givenExecution(krwInstrument, FIRST_TICK, "30000");
            givenExecution(krwInstrument, FIRST_TICK, "12000");
            // 1일차 첫 틱 환율 1200 — 100 달러가 120,000 원이 된다.
            givenExecution(usdInstrument, FIRST_TICK, "100");
            givenPublishedTick(FIRST_TICK);

            String body = body();
            assertThat(priceOf(body, krwInstrument)).containsEntry("tradedAmountKrw", "42000");
            assertThat(priceOf(body, usdInstrument)).containsEntry("tradedAmountKrw", "120000");
            assertThat(priceOf(body, carriedInstrument)).containsEntry("tradedAmountKrw", "0");
        }

        /** 매수·매도를 모두 더한다 — 총 거래대금과 같은 정의다. */
        @Test
        @DisplayName("현재가 조회_사고 판 금액이_둘 다 더해진다")
        void getPrices_buyAndSell_bothCount() throws Exception {
            givenTrader();
            givenExecution(krwInstrument, FIRST_TICK, "30000", "BUY");
            givenExecution(krwInstrument, FIRST_TICK, "30000", "SELL");
            givenPublishedTick(FIRST_TICK);

            assertThat(priceOf(body(), krwInstrument)).containsEntry("tradedAmountKrw", "60000");
        }

        /** 가격과 같은 절단 기준이라야 한 응답 안의 두 숫자가 같은 시점을 말한다. */
        @Test
        @DisplayName("현재가 조회_공개 기준 틱 뒤의 체결은_세지 않는다")
        void getPrices_executionsAfterCutoff_areNotCounted() throws Exception {
            givenTrader();
            givenExecution(krwInstrument, FIRST_TICK, "30000");
            givenExecution(krwInstrument, SECOND_TICK, "12000");
            givenPublishedTick(FIRST_TICK);

            assertThat(priceOf(body(), krwInstrument)).containsEntry("tradedAmountKrw", "30000");

            givenPublishedTick(SECOND_TICK);

            assertThat(priceOf(body(), krwInstrument)).containsEntry("tradedAmountKrw", "42000");
        }

        /** 당일 기준이므로 어제 거래가 오늘 대금에 남으면 안 된다. */
        @Test
        @DisplayName("현재가 조회_일차가 바뀌면_그 일차의 체결만 더한다")
        void getPrices_newSeasonDay_sumsOnlyThatDay() throws Exception {
            givenTrader();
            givenExecution(krwInstrument, FIRST_TICK, "30000");
            givenExecution(krwInstrument, SECOND_TICK, "12000");
            givenExecution(krwInstrument, FIRST_TICK_DAY_2, "7000");
            givenPublishedTick(FIRST_TICK_DAY_2);

            assertThat(priceOf(body(), krwInstrument)).containsEntry("tradedAmountKrw", "7000");
        }
    }

    @Nested
    @DisplayName("휴장")
    class MarketClosed {

        @Test
        @DisplayName("현재가 조회_그날 종가가 없는 종목_직전 종가를 유지하고 휴장으로 표시된다")
        void getPrices_withoutCloseOnAsOfDate_carriesLastClose() throws Exception {
            givenPublishedTick(SECOND_TICK);

            // 검증 종목 B 는 2020-01-03 종가가 없다. 직전 거래일 10,100 을 그대로 유지한다.
            Map<String, Object> carried = priceOf(body(), carriedInstrument);
            assertThat(carried)
                    .containsEntry("close", "10100")
                    .containsEntry("marketClosed", true)
                    .containsEntry("changeRate", null)
                    // 계약 예시의 휴장 행은 close 와 prevClose 가 같은 값이다 (mkt.md).
                    // 이월된 종가의 그 앞 거래일(2019-12-30, 10,000)까지 거슬러 가지 않는다.
                    .containsEntry("prevClose", "10100");
        }

        @Test
        @DisplayName("현재가 조회_보합과 휴장_한 응답에서 0 과 null 로 구별된다")
        void getPrices_flatAndClosed_areDistinguishable() throws Exception {
            long flat = givenFlatInstrument();
            givenPublishedTick(SECOND_TICK);
            String body = body();

            // 보합은 "오늘 안 움직였다", 휴장은 "오늘 값이 없다"다. 등락 보드에서 전자는
            // 한가운데 막대, 후자는 막대 없음이므로 0 으로 합칠 수 없다.
            assertChangeRate(priceOf(body, flat), 0.00);
            assertThat(priceOf(body, flat)).containsEntry("marketClosed", false);
            assertThat(priceOf(body, carriedInstrument)).containsEntry("changeRate", null);
        }

        @Test
        @DisplayName("현재가 조회_직전 종가가 없는 첫 거래일_등락률이 비어 있고 실패하지 않는다")
        void getPrices_withoutPreviousClose_leavesChangeRateEmpty() throws Exception {
            long flat = givenFlatInstrument();
            givenPublishedTick(FIRST_TICK);

            Map<String, Object> first = priceOf(body(), flat);
            assertThat(first)
                    .containsEntry("changeRate", null)
                    .containsEntry("marketClosed", false)
                    // 직전 종가가 없으면 종가 자체가 직전 종가다. 필드를 비우지 않는다.
                    .containsEntry("prevClose", "10000")
                    .containsEntry("close", "10000");
        }
    }

    @Nested
    @DisplayName("상장 필터")
    class Listing {

        @Test
        @DisplayName("현재가 조회_상장일이 오지 않은 종목_응답에 포함되지 않는다")
        void getPrices_beforeListing_excludesInstrument() throws Exception {
            givenPublishedTick(FIRST_TICK);

            // 검증 종목 D 는 2020-01-03 상장이고 그 전 날짜에도 종가가 있다 — 빠지는 근거가
            // 종가 부재가 아니라 상장 필터임이 이 픽스처로 드러난다.
            assertThat(instrumentIdsIn(body()))
                    .containsExactly(krwInstrument, carriedInstrument, usdInstrument);
        }

        @Test
        @DisplayName("현재가 조회_상장일이 지난 IPO 종목_응답에 포함된다")
        void getPrices_afterListing_includesInstrument() throws Exception {
            givenPublishedTick(SECOND_TICK);

            assertThat(instrumentIdsIn(body()))
                    .containsExactly(krwInstrument, carriedInstrument, usdInstrument, ipoInstrument);
            assertThat(priceOf(body(), ipoInstrument)).containsEntry("close", "9800");
        }
    }

    /** 체결의 주인. 계좌가 있어야 executions 의 외래 키가 선다. */
    private void givenTrader() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, TRADER_ACCOUNT, fixture.user("체결참가자").getId(), ReplayFixture.SEASON_ID);
    }

    private void givenExecution(long instrumentId, long tickSeq, String amount) {
        givenExecution(instrumentId, tickSeq, amount, "BUY");
    }

    /**
     * 대금만 더하므로 수량·체결가는 최소값으로 둔다 — 검증 대상은 {@code amount} 의 합이다.
     * 통화는 종목에서 읽는다: 환산 곱수를 가르는 값이라 임의로 정하면 달러 종목 검증이 무의미해진다.
     */
    private void givenExecution(long instrumentId, long tickSeq, String amount, String side) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_type, side, quantity,
                                        executed_price, currency, amount, applied_fee_rate, fee,
                                        tick_seq, game_date, executed_at)
                SELECT ?, i.id, 'MARKET', ?, 1, 1, i.currency, CAST(? AS NUMERIC), 0, 0,
                       t.tick_seq, t.game_date, now()
                FROM game_ticks t, instruments i WHERE t.tick_seq = ? AND i.id = ?
                """, TRADER_ACCOUNT, side, amount, tickSeq, instrumentId);
    }

    private MockHttpServletRequestBuilder prices() {
        return get(PRICES_URI).header(AUTH_TOKEN_HEADER, session);
    }

    private String body() throws Exception {
        return mockMvc.perform(prices())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 응답에서 그 종목의 줄. 없으면 실패한다 — 인덱스로 집으면 필터 오류를 놓친다. */
    private static Map<String, Object> priceOf(String body, long instrumentId) {
        List<Map<String, Object>> matched =
                JsonPath.read(body, "$.data.prices[?(@.instrumentId == " + instrumentId + ")]");
        assertThat(matched).as("종목 %d 의 가격 한 줄", instrumentId).hasSize(1);
        return matched.getFirst();
    }

    private static List<Long> instrumentIdsIn(String body) {
        List<Number> ids = JsonPath.read(body, "$.data.prices[*].instrumentId");
        return ids.stream().map(Number::longValue).toList();
    }

    /** 비율은 number 로 나가므로 JSON 파서가 고르는 숫자 타입에 기대지 않는다. */
    private static void assertChangeRate(Map<String, Object> price, double expected) {
        assertThat(price.get("changeRate")).isInstanceOf(Number.class);
        assertThat(((Number) price.get("changeRate")).doubleValue()).isEqualTo(expected);
    }

    /**
     * 종가가 두 거래일 연속 같은 종목. 픽스처 종목은 매 틱 값이 바뀌어 보합을 만들 수 없고,
     * 2019-12-30 종가를 갖고 있어 "직전 종가 없음"도 만들 수 없다.
     */
    private long givenFlatInstrument() {
        jdbc.update("""
                INSERT INTO instruments (symbol,name,category,asset_type,currency,price_scale,summary)
                VALUES (?,'검증 종목 보합','KRX','STOCK','KRW',0,'보합·직전 종가 부재 검증용')
                """, FLAT_SYMBOL);
        long id = instrumentId(FLAT_SYMBOL);
        jdbc.update("""
                INSERT INTO daily_closes (instrument_id,game_date,close_price,raw_close)
                VALUES (?, DATE '2020-01-02', 10000, 10000), (?, DATE '2020-01-03', 10000, 10000)
                """, id, id);
        return id;
    }

    /** 그 틱까지 공개된 상태로 재생을 고정한다. 배속 0 이라 폴러가 더 진행하지 않는다. */
    private void givenPublishedTick(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
