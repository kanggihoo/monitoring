package com.ssafy.a605.backend.domain.ipo;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 공시 조회와 청약 신청·취소 (기획서 8.1~8.3, {@code ipo.md}).
 *
 * <p>보는 것은 두 가지다: <b>공시 전 편성이 새어 나가지 않는가</b>(종목명 자체가 미래 정보다),
 * 그리고 <b>증거금이 정확히 한 번 잠기고 정확히 돌아오는가</b>. 후자의 실패는 조용히 오고,
 * 그 모습이 곧 쓸 수 없게 묶인 현금이다.
 *
 * <p>재생을 굴리지 않고 <b>공개된 틱을 고정</b>한다 ({@code givenTradingAt}) — 여기서 보는 것은
 * 시각에 따른 국면 변화가 아니라 그 국면에서의 계약이다. 마감 추첨은 {@code IpoDrawApiTest} 가
 * 폴러를 굴려서 본다.
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
class IpoSubscriptionApiTest {

    private static final String OFFERINGS_URI = "/api/v1/ipo/offerings";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 980L;
    private static final long OTHER_ACCOUNT_ID = 981L;

    /** 달러 공모용 종목. 픽스처의 IPO 종목이 원화뿐이라 여기서 만든다. */
    private static final String USD_SYMBOL = "IPO_TEST_NASDAQ";

    /** 국내 공모 증거금. 기획서 8.2.2 의 1,000만 원이다. */
    private static final String KRW_DEPOSIT = "10000000";

    /** 나스닥 공모 증거금. 재생 구간 평균 환율로 환산한 고정액이다. */
    private static final String USD_DEPOSIT = "8600.00";

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
    private String ownerSession;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        owner = fixture.user("청약주인");
        ownerSession = sessions.createSessionFor(owner);
        givenAccount(ACCOUNT_ID, owner.getId());
        givenUsdInstrument();
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
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM instruments WHERE symbol = ?", USD_SYMBOL);
    }

    @Nested
    @DisplayName("공시 조회")
    class Offerings {

        @Test
        @DisplayName("목록_공시 틱이 지났으면_공모가 보인다")
        void listsDisclosedOffering() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);

            mockMvc.perform(get(OFFERINGS_URI))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.asOfTickSeq").value(1))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings.length()").value(1))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].offeringId")
                            .value((int) offeringId))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].status")
                            .value("OPEN"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].depositAmount")
                            .value(KRW_DEPOSIT))
                    // 목록에는 공시 본문이 실리지 않는다. null 로 비워 보내면 "본문 없는 공모"가 된다.
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].summary")
                            .doesNotExist());
        }

        @Test
        @DisplayName("목록_공시 틱 전이면_공모가 아예 없다")
        void hidesUndisclosedOffering() throws Exception {
            givenKrwOffering(3, 4, 4);
            givenTradingAt(2);

            // 개수만 새어 나가도 편성이 드러난다 (기획서 3.5.2).
            mockMvc.perform(get(OFFERINGS_URI))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings.length()").value(0));
        }

        @Test
        @DisplayName("목록_공개된 틱이 없으면_빈 목록이고 기준 틱이 없다")
        void beforeFirstTick_isEmpty() throws Exception {
            givenKrwOffering(1, 3, 4);
            // 개장은 했지만 아직 아무 틱도 공개되지 않은 순간이다.
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                    BigDecimal.ONE, null, 1, ReplayFixture.TRADING_OPEN);

            mockMvc.perform(get(OFFERINGS_URI))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.asOfTickSeq").doesNotExist())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings.length()").value(0));
        }

        @Test
        @DisplayName("상세_공시된 공모면_공시 본문이 함께 온다")
        void detailCarriesDisclosure() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);

            mockMvc.perform(get(OFFERINGS_URI + "/" + offeringId))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.allocationRule")
                            .value("청약자 수의 30%를 무작위 추첨한다. 모든 청약자의 당첨 확률은 같다."))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.summary").exists());
        }

        @Test
        @DisplayName("상세_공시 전이면_404")
        void detailBeforeDisclosure_isNotFound() throws Exception {
            long offeringId = givenKrwOffering(3, 4, 4);
            givenTradingAt(1);

            // 403 으로 나누면 그 식별자의 공모가 존재한다는 사실이 드러난다.
            mockMvc.perform(get(OFFERINGS_URI + "/" + offeringId))
                    .andExpect(MockMvcResultMatchers.status().isNotFound())
                    // 취할 행동이 없는 거부에는 data 가 실리지 않는다 (_common.md §4).
                    // "data": null 을 늘 붙이면 오류 응답 형식이 바뀐 것으로 읽힌다.
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("경쟁률_청약자가 없으면_당첨 예정 인원 0 이고 비율은 비어 있다")
        void withoutSubscribers_hasNoRate() throws Exception {
            givenKrwOffering(1, 3, 4);
            givenTradingAt(1);

            mockMvc.perform(get(OFFERINGS_URI))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].subscriberCount")
                            .value(0))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].expectedWinnerCount")
                            .value(0))
                    // 0 으로 나누지 않는다.
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].competitionRate")
                            .doesNotExist())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.offerings[0].winProbability")
                            .doesNotExist());
        }

        @Test
        @DisplayName("경쟁률_청약자가 하나면_전원 당첨이라 1 대 1 이다")
        void singleSubscriber_isAllWinners() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);
            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isCreated());

            // 30% 를 반올림하면 0 이지만 최소 1 명이다 (기획서 8.4.2).
            mockMvc.perform(get(OFFERINGS_URI + "/" + offeringId))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.subscriberCount").value(1))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.expectedWinnerCount").value(1))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.competitionRate").value(1.00))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.winProbability").value(100.00));
        }
    }

    @Nested
    @DisplayName("청약 신청")
    class Subscribe {

        @Test
        @DisplayName("신청_증거금이_잠기고 총 현금은 그대로다")
        void locksDeposit() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);

            subscribe(offeringId)
                    .andExpect(MockMvcResultMatchers.status().isCreated())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.depositAmount")
                            .value(KRW_DEPOSIT))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.result").doesNotExist());

            assertCash("locked_krw", KRW_DEPOSIT);
            // 잠금은 총액을 건드리지 않는다.
            assertCash("krw_cash", "45000000");
        }

        @Test
        @DisplayName("신청_두 번 누르면_200 이고 두 번 잠기지 않는다")
        void isIdempotent() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);

            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isCreated());
            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isOk());

            assertCash("locked_krw", KRW_DEPOSIT);
            assertEquals(1, subscriptionCount(offeringId), "청약 건수");
        }

        @Test
        @DisplayName("신청_원화가 모자라면_409 에 환전 안내가 실린다")
        void withoutCash_carriesExchange() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);
            // 잠기지 않은 원화를 증거금보다 400 만 적게 남긴다.
            jdbc.update("UPDATE accounts SET locked_krw = 39000000 WHERE id = ?", ACCOUNT_ID);

            subscribe(offeringId)
                    .andExpect(MockMvcResultMatchers.status().isConflict())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.shortfall").value("4000000"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.shortfallExchange.direction")
                            .value("USD_TO_KRW"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.shortfallExchange.sendAmount")
                            .exists());

            // 거부는 아무것도 잠그지 않는다.
            assertCash("locked_krw", "39000000");
            assertEquals(0, subscriptionCount(offeringId), "청약 건수");
        }

        @Test
        @DisplayName("신청_달러 공모에 달러가 없으면_원화에서 환전하라고 알린다")
        void usdOffering_asksForUsd() throws Exception {
            long offeringId = givenUsdOffering(1, 3, 4);
            givenTradingAt(1);

            subscribe(offeringId)
                    .andExpect(MockMvcResultMatchers.status().isConflict())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.shortfall").value(USD_DEPOSIT))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.shortfallExchange.direction")
                            .value("KRW_TO_USD"));

            // 원화가 1 억 있어도 달러 공모는 달러로 잠근다 (기획서 8.2.2).
            assertCash("locked_krw", "0");
            assertCash("locked_usd", "0");
        }

        @Test
        @DisplayName("신청_달러가 있으면_달러 지갑이 잠긴다")
        void usdOffering_locksUsd() throws Exception {
            long offeringId = givenUsdOffering(1, 3, 4);
            givenTradingAt(1);
            jdbc.update("UPDATE accounts SET usd_cash = 9000 WHERE id = ?", ACCOUNT_ID);

            subscribe(offeringId)
                    .andExpect(MockMvcResultMatchers.status().isCreated())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.depositAmount").value(USD_DEPOSIT));

            assertCash("locked_usd", "8600");
            assertCash("locked_krw", "0");
        }

        @Test
        @DisplayName("신청_마감 틱이 지났으면_409")
        void afterClose_isRejected() throws Exception {
            long offeringId = givenKrwOffering(1, 2, 4);
            givenTradingAt(3);

            // 마감 이후의 신청은 이미 돌아간 추첨을 흔든다 (기획서 8.2.5).
            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isConflict());
            assertCash("locked_krw", "0");
        }

        @Test
        @DisplayName("신청_거래 시간이 아니면_409")
        void outsideTrading_isRejected() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            // 브리핑 중이다. 청약 창은 거래 시간 안에만 열린다 (0장 시간표).
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.BRIEFING), ReplayFixture.START,
                    BigDecimal.ONE, 1L, 1, ReplayFixture.BRIEFING);

            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isConflict());
            assertCash("locked_krw", "0");
        }

        @Test
        @DisplayName("신청_남의 청약은_내 목록에 보이지 않는다")
        void othersSubscriptionIsInvisible() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);
            User other = fixture.user("남의청약");
            givenAccount(OTHER_ACCOUNT_ID, other.getId());
            mockMvc.perform(MockMvcRequestBuilders.post(OFFERINGS_URI + "/" + offeringId + "/subscription")
                            .header(AUTH_TOKEN_HEADER, sessions.createSessionFor(other)))
                    .andExpect(MockMvcResultMatchers.status().isCreated());

            mockMvc.perform(get(OFFERINGS_URI + "/" + offeringId))
                    // 경쟁률은 남의 청약까지 센다. 내 청약 여부와는 다른 질문이다.
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.subscriberCount").value(1))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.mySubscription").doesNotExist());
        }
    }

    @Nested
    @DisplayName("청약 취소")
    class Cancel {

        @Test
        @DisplayName("취소_청약이 있으면_증거금이 즉시 돌아온다")
        void releasesDeposit() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);
            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isCreated());

            cancel(offeringId).andExpect(MockMvcResultMatchers.status().isNoContent());

            assertCash("locked_krw", "0");
            assertCash("krw_cash", "45000000");
            assertEquals(0, subscriptionCount(offeringId), "청약 건수");
        }

        @Test
        @DisplayName("취소_청약한 적이 없어도_204")
        void withoutSubscription_isNoContent() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);

            // 목표 상태(청약 없음)가 이미 성립한다. 재시도와 중복 클릭이 오류로 보이지 않는다.
            cancel(offeringId).andExpect(MockMvcResultMatchers.status().isNoContent());
            assertCash("locked_krw", "0");
        }

        @Test
        @DisplayName("취소_마감 틱이 지났으면_409 이고 증거금이 묶인 채 남는다")
        void afterClose_isRejected() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);
            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isCreated());
            givenTradingAt(3);

            // 마감 뒤의 취소는 추첨에서 이미 뽑힌 물량을 되돌린다 (기획서 8.2.5).
            cancel(offeringId).andExpect(MockMvcResultMatchers.status().isConflict());
            assertCash("locked_krw", KRW_DEPOSIT);
        }

        @Test
        @DisplayName("취소_다시 신청하면_증거금이 한 번만 잠긴다")
        void resubscribeLocksOnce() throws Exception {
            long offeringId = givenKrwOffering(1, 3, 4);
            givenTradingAt(1);
            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isCreated());
            cancel(offeringId).andExpect(MockMvcResultMatchers.status().isNoContent());

            subscribe(offeringId).andExpect(MockMvcResultMatchers.status().isCreated());

            assertCash("locked_krw", KRW_DEPOSIT);
            assertEquals(1, subscriptionCount(offeringId), "청약 건수");
        }
    }

    private org.springframework.test.web.servlet.ResultActions subscribe(long offeringId)
            throws Exception {
        return mockMvc.perform(
                MockMvcRequestBuilders.post(OFFERINGS_URI + "/" + offeringId + "/subscription")
                        .header(AUTH_TOKEN_HEADER, ownerSession));
    }

    private org.springframework.test.web.servlet.ResultActions cancel(long offeringId)
            throws Exception {
        return mockMvc.perform(
                MockMvcRequestBuilders.delete(OFFERINGS_URI + "/" + offeringId + "/subscription")
                        .header(AUTH_TOKEN_HEADER, ownerSession));
    }

    private org.springframework.test.web.servlet.RequestBuilder get(String uri) {
        return MockMvcRequestBuilders.get(uri).header(AUTH_TOKEN_HEADER, ownerSession);
    }

    /** 공개된 틱을 고정한 채 거래 시간에 세운다. 시계를 굴리지 않으므로 틱이 넘어가지 않는다. */
    private void givenTradingAt(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private long givenKrwOffering(int disclosureTick, int closeTick, int listingTick) {
        return givenOffering("REPLAY_TEST_D", "KRW", "12000", KRW_DEPOSIT,
                disclosureTick, closeTick, listingTick);
    }

    private long givenUsdOffering(int disclosureTick, int closeTick, int listingTick) {
        return givenOffering(USD_SYMBOL, "USD", "78.00", USD_DEPOSIT,
                disclosureTick, closeTick, listingTick);
    }

    private long givenOffering(String symbol, String currency, String offerPrice, String deposit,
                               int disclosureTick, int closeTick, int listingTick) {
        return jdbc.queryForObject("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                                           deposit_amount, disclosure_tick_seq, close_tick_seq,
                                           listing_tick_seq, allocation_rule)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, ?, ?)
                RETURNING id
                """, Long.class, ReplayFixture.SEASON_ID, instrumentId(symbol), currency, offerPrice,
                deposit, disclosureTick, closeTick, listingTick,
                "청약자 수의 30%를 무작위 추첨한다. 모든 청약자의 당첨 확률은 같다.");
    }

    /** 달러 공모용 나스닥 IPO 종목. 상장일이 있어야 상장 예정으로 다뤄진다. */
    private void givenUsdInstrument() {
        jdbc.update("""
                INSERT INTO instruments (season_id, symbol, name, category, asset_type, currency,
                                         price_scale, listed_game_date, summary)
                VALUES (1, ?, '청약 검증 나스닥', 'NASDAQ', 'IPO_STOCK', 'USD', 2, DATE '2020-01-06',
                        '실제 시장 데이터가 아닌 청약 검증용 상장 예정 종목')
                ON CONFLICT (season_id, symbol) DO NOTHING
                """, USD_SYMBOL);
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

    private int subscriptionCount(long offeringId) {
        return jdbc.queryForObject("SELECT count(*) FROM ipo_subscriptions WHERE offering_id = ?",
                Integer.class, offeringId);
    }

    private void assertCash(String column, String expected) {
        BigDecimal actual = jdbc.queryForObject(
                "SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
