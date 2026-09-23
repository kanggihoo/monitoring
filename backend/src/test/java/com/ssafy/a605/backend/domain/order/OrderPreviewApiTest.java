package com.ssafy.a605.backend.domain.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * HTTP 경계에서 주문 미리보기의 관찰 가능한 결과만 본다.
 *
 * <p>편성·환율·종가는 {@code R__replay_fixture.sql} 이 심은 검증용 시즌을 그대로 쓴다 — 기준가가
 * 재생이 공개한 틱의 종가이므로, 재생과 다른 픽스처를 세우면 두 코드가 서로 다른 세계를
 * 검증한다.
 *
 * <p><b>이 테스트가 쓰는 종목은 이 테스트가 만든다.</b> 픽스처 종목만으로는 자릿수 네 자리
 * (코인)와 상장 전(IPO)을 표현할 수 없고, 남의 종목의 성질에 기대면 그 종목의 시세가 적재되는
 * 날 조용히 깨진다.
 *
 * <p>배속 0 으로 고정한 상태에서만 호출한다. 폴러는 실제 타이머로 돌지만 배속이 0 이면 도달한
 * 항목이 없어 아무것도 진행하지 않으므로, phase 와 공개 틱을 테스트가 정확히 쥔다.
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
class OrderPreviewApiTest {

    private static final String PREVIEW_URI = "/api/v1/orders/preview";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 921L;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200, 검증 종목 종가 10100. */
    private static final long FIRST_TICK = 1L;

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03, 환율 1210. 검증 종목 B 는 이날 휴장이다. */
    private static final long SECOND_TICK = 2L;

    /** 이 테스트가 만드는 종목. 상장일이 첫 틱보다 뒤라 그날은 거래되지 않는다. */
    private static final String UPCOMING_SYMBOL = "ORDER_TEST_UPCOMING";

    /** 이 테스트가 만드는 종목. 가격 자릿수가 네 자리라 금액 반올림이 눈에 보인다. */
    private static final String COIN_SYMBOL = "ORDER_TEST_COIN";

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
        user = fixture.user("주문자");
        session = sessions.createSessionFor(user);
        givenOwnInstruments();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM exchanges WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
        jdbc.update("""
                DELETE FROM daily_closes WHERE instrument_id IN
                    (SELECT id FROM instruments WHERE symbol IN (?, ?))
                """, UPCOMING_SYMBOL, COIN_SYMBOL);
        jdbc.update("DELETE FROM instruments WHERE symbol IN (?, ?)", UPCOMING_SYMBOL, COIN_SYMBOL);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("주문 미리보기_세션이 없으면_401 을 받는다")
        void previewOrder_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(PREVIEW_URI)
                            .param("orderType", "MARKET").param("side", "BUY")
                            .param("instrumentId", "1").param("quantity", "3"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("주문 미리보기_시즌에 참가하지 않았으면_404 를 받는다")
        void previewOrder_withoutAccount_returnsNotFound() throws Exception {
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }
    }

    @Nested
    @DisplayName("phase 게이팅")
    class PhaseGating {

        @Test
        @DisplayName("주문 미리보기_거래 시간이 아니면_409 를 받는다")
        void previewOrder_outsideTrading_returnsConflict() throws Exception {
            givenAccount();
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.BRIEFING), ReplayFixture.START,
                    BigDecimal.ZERO, FIRST_TICK, 1, ReplayFixture.BRIEFING);

            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("지금은 거래 시간이 아닙니다."));
        }

        @Test
        @DisplayName("주문 미리보기_개장 직후 공개된 시세가 없으면_409 를 받는다")
        void previewOrder_beforeFirstPublish_returnsConflict() throws Exception {
            givenAccount();
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                    BigDecimal.ZERO, null, 1, ReplayFixture.TRADING_OPEN);

            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("시세가 아직 공개되지 않았습니다."));
        }
    }

    @Nested
    @DisplayName("금액 계산")
    class Calculation {

        @Test
        @DisplayName("시장가 매수_원화 종목이면_수수료가 붙지 않아 총액이 금액과 같다")
        void previewOrder_marketBuyInKrw_chargesNoFee() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 10,100 x 3 = 30,300. 수수료는 매도에만 붙으므로 매수의 총액은 금액 그대로다.
            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currency").value("KRW"))
                    .andExpect(jsonPath("$.data.referencePrice").value("10100"))
                    .andExpect(jsonPath("$.data.estimatedAmount").value("30300"))
                    .andExpect(jsonPath("$.data.appliedFeeRate").value(0.00))
                    .andExpect(jsonPath("$.data.estimatedFee").value("0"))
                    .andExpect(jsonPath("$.data.totalAmount").value("30300"))
                    // 원화 현금 45,000,000 중 3,000,000 이 예약·청약에 잠겨 있다.
                    .andExpect(jsonPath("$.data.availableCash").value("42000000"))
                    .andExpect(jsonPath("$.data.shortfall").value("0"))
                    .andExpect(jsonPath("$.data.shortfallExchange").doesNotExist())
                    .andExpect(jsonPath("$.data.feeFree").value(false));
        }

        @Test
        @DisplayName("시장가 매도_같은 조건이면_총액이 금액에서 수수료를 뺀 값이다")
        void previewOrder_marketSell_subtractsFeeFromAmount() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.estimatedAmount").value("30300"))
                    .andExpect(jsonPath("$.data.estimatedFee").value("45"))
                    .andExpect(jsonPath("$.data.totalAmount").value("30255"))
                    // 파는 쪽은 현금이 아니라 보유 수량을 쓰므로 부족분이 있을 수 없다.
                    .andExpect(jsonPath("$.data.shortfall").value("0"))
                    .andExpect(jsonPath("$.data.shortfallExchange").doesNotExist());
        }

        @Test
        @DisplayName("예약 매수_지정가를 내면_기준가가 종가가 아니라 지정가다")
        void previewOrder_limitBuy_usesLimitPriceAsReference() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 9,000.55 x 2 = 18,001.10. 매수라 수수료가 없어 총액이 금액과 같다.
            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_C"), 2)
                            .param("limitPrice", "9000.55"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currency").value("USD"))
                    .andExpect(jsonPath("$.data.referencePrice").value("9000.55"))
                    .andExpect(jsonPath("$.data.estimatedAmount").value("18001.10"))
                    .andExpect(jsonPath("$.data.estimatedFee").value("0.00"))
                    .andExpect(jsonPath("$.data.totalAmount").value("18001.10"));
        }

        @Test
        @DisplayName("시장가 매수_가격 자릿수가 네 자리면_기준가는 네 자리로 금액은 센트로 온다")
        void previewOrder_fractionalPriceScale_roundsAmountToCurrencyScale() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 0.5012 x 3 = 1.5036 이고 결제 통화 자릿수로 확정해 1.50 이다. 자릿수를 맞추지
            // 않으면 화면에 보인 금액과 실제로 빠져나가는 금액이 갈라진다.
            mockMvc.perform(preview("MARKET", "BUY", instrumentId(COIN_SYMBOL), 3))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.referencePrice").value("0.5012"))
                    .andExpect(jsonPath("$.data.estimatedAmount").value("1.50"))
                    .andExpect(jsonPath("$.data.estimatedFee").value("0.00"))
                    .andExpect(jsonPath("$.data.totalAmount").value("1.50"));
        }

        @Test
        @DisplayName("시장가 매도_무료 타임이면_수수료가 빠지지 않아 총액이 금액과 같다")
        void previewOrder_marketSellDuringFeeFree_appliesZeroRate() throws Exception {
            givenAccount();
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.FEE_FREE_START), ReplayFixture.START,
                    BigDecimal.ZERO, FIRST_TICK, 1, ReplayFixture.FEE_FREE_START);

            // 평소라면 30,300 - 45 = 30,255 다. 무료 타임의 효과는 매도에만 있다 —
            // 매수는 원래 수수료가 없어 이 구간에도 숫자가 달라지지 않는다.
            mockMvc.perform(preview("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.appliedFeeRate").value(0.00))
                    .andExpect(jsonPath("$.data.estimatedFee").value("0"))
                    .andExpect(jsonPath("$.data.totalAmount").value("30300"))
                    .andExpect(jsonPath("$.data.feeFree").value(true));
        }
    }

    @Nested
    @DisplayName("부족분 환전")
    class Shortfall {

        @Test
        @DisplayName("시장가 매수_달러가 모자라면_원화로 보낼 최소 금액이 온다")
        void previewOrder_shortOfUsd_returnsKrwToUsdExchange() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 총액 10,100.00 - 잠기지 않은 달러 9,446.17 = 653.83 이 모자란다.
            // 환율 1200 에서 653.83 을 받으려면 784,988 원을 보내야 한다 — 784,987 원은
            // 환전 수수료 반올림과 수령액 내림이 겹쳐 653.82 가 되어 1센트가 모자란다.
            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_C"), 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalAmount").value("10100.00"))
                    .andExpect(jsonPath("$.data.availableCash").value("9446.17"))
                    .andExpect(jsonPath("$.data.shortfall").value("653.83"))
                    .andExpect(jsonPath("$.data.shortfallExchange.direction").value("KRW_TO_USD"))
                    .andExpect(jsonPath("$.data.shortfallExchange.sendAmount").value("784988"));
        }

        @Test
        @DisplayName("시장가 매수_원화가 모자라면_달러로 보낼 최소 금액이 온다")
        void previewOrder_shortOfKrw_returnsUsdToKrwExchange() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 국내 종목도 대칭으로 동작한다 — 계약을 한쪽으로만 두면 반대 방향에 특례가 생긴다.
            // 총액 50,500,000 - 42,000,000 = 8,500,000 원이 모자라고, 보내는 쪽은 센트 단위라
            // 7,086.88 달러가 최소다 (7,086.87 은 8,499,996 원이라 4원 모자란다).
            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 5000))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalAmount").value("50500000"))
                    .andExpect(jsonPath("$.data.shortfall").value("8500000"))
                    .andExpect(jsonPath("$.data.shortfallExchange.direction").value("USD_TO_KRW"))
                    .andExpect(jsonPath("$.data.shortfallExchange.sendAmount").value("7086.88"));
        }

        @Test
        @DisplayName("부족분 환전_안내받은 금액을 그대로 환전하면_부족분이 채워진다")
        void previewOrder_shortfallExchange_coversTheShortfall() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(exchange("KRW_TO_USD", "784988"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.receiveAmount").value("653.83"));

            // 이 필드가 존재하는 이유 자체다 — 안내한 금액으로 환전했는데 주문이 또 막히면 안 된다.
            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_C"), 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.shortfall").value("0.00"))
                    .andExpect(jsonPath("$.data.shortfallExchange").doesNotExist());
        }
    }

    @Nested
    @DisplayName("종목 상태")
    class InstrumentState {

        @Test
        @DisplayName("시장가 주문_휴장 종목이면_409 를 받는다")
        void previewOrder_marketOnClosedMarket_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(SECOND_TICK);

            // 검증 종목 B 는 2020-01-03 에 종가가 없어 직전 종가가 이월된 상태다 = 그날 휴장.
            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_B"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("휴장 중인 종목은 시장가로 주문할 수 없습니다."));
        }

        @Test
        @DisplayName("예약 주문_휴장 종목이어도_기준가가 지정가라 200 이다")
        void previewOrder_limitOnClosedMarket_returnsOk() throws Exception {
            givenAccount();
            givenTradingAt(SECOND_TICK);

            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_B"), 3)
                            .param("limitPrice", "9500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.referencePrice").value("9500"));
        }

        @Test
        @DisplayName("주문 미리보기_상장일 전 종목이면_409 를 받는다")
        void previewOrder_beforeListing_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("MARKET", "BUY", instrumentId(UPCOMING_SYMBOL), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("아직 상장되지 않은 종목입니다."));
        }

        @Test
        @DisplayName("주문 미리보기_유니버스에 없는 종목이면_404 를 받는다")
        void previewOrder_unknownInstrument_returnsNotFound() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("MARKET", "BUY", -1L, 3))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목을 찾을 수 없습니다."));
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class InputValidation {

        @Test
        @DisplayName("주문 미리보기_수량이 0 이면_400 을 받는다")
        void previewOrder_zeroQuantity_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 0))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("주문 수량은 1주 이상이어야 합니다."));
        }

        @Test
        @DisplayName("예약 주문_지정가가 없으면_400 을 받는다")
        void previewOrder_limitWithoutPrice_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("예약 주문에는 지정가가 필요합니다."));
        }

        @Test
        @DisplayName("예약 주문_지정가가 0 이면_400 을 받는다")
        void previewOrder_zeroLimitPrice_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_A"), 3)
                            .param("limitPrice", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("지정가는 0보다 커야 합니다."));
        }

        @Test
        @DisplayName("예약 주문_지정가가 종목의 가격 자릿수보다 잘면_400 을 받는다")
        void previewOrder_limitPriceFinerThanPriceScale_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 조용히 반올림하면 사용자가 낸 값과 체결 판정에 쓰일 값이 달라진다.
            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_C"), 2)
                            .param("limitPrice", "9000.555"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("이 종목의 지정가는 소수점 2자리까지 입력할 수 있습니다."));
        }

        @Test
        @DisplayName("예약 주문_원화 종목의 지정가에 소수가 있으면_400 을 받는다")
        void previewOrder_fractionalLimitPriceOnKrw_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_A"), 3)
                            .param("limitPrice", "10100.5"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("이 종목의 지정가는 정수로만 입력할 수 있습니다."));
        }

        @Test
        @DisplayName("예약 주문_지수 표기로 거대한 지정가를 보내면_전개하지 않고 400 을 받는다")
        void previewOrder_hugeLimitPrice_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 입력은 열 글자지만 전개하면 900만 자리다. 부호 검사는 이것을 통과시킨다.
            mockMvc.perform(preview("LIMIT", "BUY", instrumentId("REPLAY_TEST_A"), 3)
                            .param("limitPrice", "1E+9000000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("지정가가 너무 큽니다."));
        }

        @Test
        @DisplayName("주문 미리보기_주문 유형이 없으면_400 을 받는다")
        void previewOrder_withoutOrderType_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(get(PREVIEW_URI).header(AUTH_TOKEN_HEADER, session)
                            .param("side", "BUY")
                            .param("instrumentId", String.valueOf(instrumentId("REPLAY_TEST_A")))
                            .param("quantity", "3"))
                    .andExpect(status().isBadRequest());
        }
    }

    private MockHttpServletRequestBuilder preview(String orderType, String side, long instrumentId,
                                                  int quantity) {
        return get(PREVIEW_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .param("orderType", orderType)
                .param("side", side)
                .param("instrumentId", String.valueOf(instrumentId))
                .param("quantity", String.valueOf(quantity));
    }

    /** 안내받은 부족분 환전을 그대로 실행한다 — 프론트가 하는 것과 같은 요청이다. */
    private MockHttpServletRequestBuilder exchange(String direction, String sendAmount) {
        return post("/api/v1/accounts/me/exchanges")
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"direction\":\"%s\",\"amount\":\"%s\"}".formatted(direction, sendAmount));
    }

    /** 그 틱까지 공개된 거래 구간으로 재생을 고정한다. */
    private void givenTradingAt(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private void givenAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 12000, 3000000, 2553.83,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, user.getId(), ReplayFixture.SEASON_ID);
    }

    /**
     * 이 테스트에만 필요한 두 종목. 상장 전 종목은 종가를 두지 않는다 — 상장일 전에는 시세가
     * 없는 것이 정상이고, 판정이 상장일에만 걸리는지를 그래야 확인할 수 있다.
     */
    private void givenOwnInstruments() {
        jdbc.update("""
                INSERT INTO instruments (symbol, name, category, asset_type, currency, price_scale,
                                         listed_game_date, summary)
                VALUES (?, '주문 검증용 공모주', 'KRX', 'IPO_STOCK', 'KRW', 0, '2020-01-06',
                        '실제 시장 데이터가 아닌 주문 검증용 종목')
                """, UPCOMING_SYMBOL);
        jdbc.update("""
                INSERT INTO instruments (symbol, name, category, asset_type, currency, price_scale, summary)
                VALUES (?, '주문 검증용 코인', 'CRYPTO', 'CRYPTO', 'USD', 4,
                        '실제 시장 데이터가 아닌 주문 검증용 종목')
                """, COIN_SYMBOL);
        jdbc.update("""
                INSERT INTO daily_closes (instrument_id, game_date, close_price, raw_close)
                SELECT id, '2020-01-02'::date, 0.5012, 0.5012 FROM instruments WHERE symbol = ?
                """, COIN_SYMBOL);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
