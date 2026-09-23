package com.ssafy.a605.backend.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * HTTP 경계에서 환전의 관찰 가능한 결과만 본다.
 *
 * <p>편성·환율은 {@code R__replay_fixture.sql} 이 심은 검증용 시즌을 그대로 쓴다 — 적용 환율이
 * 재생이 공개한 틱의 값이므로, 재생과 다른 픽스처를 세우면 두 코드가 서로 다른 세계를 검증한다.
 *
 * <p><b>반올림이 검증의 핵심이다.</b> 수수료는 보내는 통화 자릿수로 반올림하고 수령액은 받는
 * 통화 자릿수로 내림하므로, 두 방향 모두 <b>내림과 반올림의 결과가 다른 금액</b>으로 확인한다.
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
class AccountExchangeApiTest {

    private static final String PREVIEW_URI = "/api/v1/accounts/me/exchanges/preview";
    private static final String EXCHANGES_URI = "/api/v1/accounts/me/exchanges";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 920L;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200. */
    private static final long FIRST_TICK = 1L;

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03, 환율 1210. */
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

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("환전주인");
        session = sessions.createSessionFor(user);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM exchanges WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("환전 미리보기_세션이 없으면_401 을 받는다")
        void previewExchange_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(PREVIEW_URI).param("direction", "KRW_TO_USD").param("amount", "1500000"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("환전 실행_세션이 없으면_401 을 받는다")
        void exchange_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(post(EXCHANGES_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"direction\":\"KRW_TO_USD\",\"amount\":\"1500000\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("환전 미리보기_시즌에 참가하지 않았으면_404 를 받는다")
        void previewExchange_withoutAccount_returnsNotFound() throws Exception {
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("KRW_TO_USD", "1500000"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }
    }

    @Nested
    @DisplayName("phase 게이팅")
    class PhaseGating {

        @Test
        @DisplayName("환전 미리보기_거래 시간이 아니면_409 를 받는다")
        void previewExchange_outsideTrading_returnsConflict() throws Exception {
            givenAccount();
            givenBriefingAt(FIRST_TICK);

            mockMvc.perform(preview("KRW_TO_USD", "1500000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("지금은 거래 시간이 아닙니다."));
        }

        @Test
        @DisplayName("환전 실행_거래 시간이 아니면_409 이고 잔고가 변하지 않는다")
        void exchange_outsideTrading_returnsConflict() throws Exception {
            givenAccount();
            givenBriefingAt(FIRST_TICK);

            mockMvc.perform(exchange("KRW_TO_USD", "1500000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("지금은 거래 시간이 아닙니다."));
            assertCash("45000000.0000", "12000.0000");
        }

        @Test
        @DisplayName("환전 이력_거래 시간이 아니어도_열람할 수 있다")
        void searchExchanges_outsideTrading_returnsOk() throws Exception {
            givenAccount();
            givenBriefingAt(FIRST_TICK);

            mockMvc.perform(get(EXCHANGES_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("환전 미리보기_개장 직후 공개된 시세가 없으면_409 를 받는다")
        void previewExchange_beforeFirstPublish_returnsConflict() throws Exception {
            givenAccount();
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                    BigDecimal.ZERO, null, 1, ReplayFixture.TRADING_OPEN);

            mockMvc.perform(preview("KRW_TO_USD", "1500000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("환율이 아직 공개되지 않았습니다."));
        }
    }

    @Nested
    @DisplayName("미리보기 계산")
    class Preview {

        @Test
        @DisplayName("환전 미리보기_원화에서 달러로_수령액이 내림으로 계산된다")
        void previewExchange_krwToUsd_floorsReceiveAmount() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 수수료 1,500,000 x 0.05% = 750. (1,500,000 - 750) / 1200 = 1249.375
            // 내림이라 1249.37 이다 — 반올림이면 1249.38 이 된다.
            mockMvc.perform(preview("KRW_TO_USD", "1500000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.direction").value("KRW_TO_USD"))
                    .andExpect(jsonPath("$.data.rate").value("1200.00"))
                    .andExpect(jsonPath("$.data.sendAmount").value("1500000"))
                    .andExpect(jsonPath("$.data.feeRate").value(0.05))
                    .andExpect(jsonPath("$.data.fee").value("750"))
                    .andExpect(jsonPath("$.data.receiveAmount").value("1249.37"))
                    .andExpect(jsonPath("$.data.availableCash").value("42000000"));
        }

        @Test
        @DisplayName("환전 미리보기_달러에서 원화로_금액과 잔고가 달러 자릿수로 온다")
        void previewExchange_usdToKrw_floorsReceiveAmount() throws Exception {
            givenAccount();
            givenTradingAt(SECOND_TICK);

            // 수수료 1000.05 x 0.05% = 0.500025 -> 0.50. (1000.05 - 0.50) x 1210 = 1,209,455.5
            // 내림이라 1,209,455 이다 — 반올림이면 1,209,456 이 된다.
            mockMvc.perform(preview("USD_TO_KRW", "1000.05"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.direction").value("USD_TO_KRW"))
                    .andExpect(jsonPath("$.data.rate").value("1210.00"))
                    .andExpect(jsonPath("$.data.sendAmount").value("1000.05"))
                    .andExpect(jsonPath("$.data.fee").value("0.50"))
                    .andExpect(jsonPath("$.data.receiveAmount").value("1209455"))
                    .andExpect(jsonPath("$.data.availableCash").value("9446.17"));
        }

        @Test
        @DisplayName("환전 미리보기_잔고에 표시 자릿수보다 잘은 소수가 있으면_상한이 내림으로 온다")
        void previewExchange_fractionalBalance_floorsAvailableCash() throws Exception {
            givenAccount();
            // 체결가가 소수 여덟 자리까지 가므로 매도 대금이 센트보다 잘게 들어올 수 있다.
            jdbc.update("UPDATE accounts SET usd_cash = 12000.6299 WHERE id = ?", ACCOUNT_ID);
            givenTradingAt(FIRST_TICK);

            // 12,000.6299 - 2,553.83 = 9,446.7999. 반올림하면 9446.80 이 되어, 표시된 값을
            // 그대로 보낸 사용자가 잔고 부족으로 거절된다.
            mockMvc.perform(preview("USD_TO_KRW", "1000.00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.availableCash").value("9446.79"));
        }

        @Test
        @DisplayName("환전 미리보기_잠기지 않은 잔고보다 큰 금액이어도_상태를 바꾸지 않고 200 이다")
        void previewExchange_beyondAvailableCash_returnsOk() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 미리보기가 답할 질문은 "이 환전이 가능한가" 하나이고, 판단 근거는 availableCash 다.
            mockMvc.perform(preview("KRW_TO_USD", "44000000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.availableCash").value("42000000"));
            assertCash("45000000.0000", "12000.0000");
        }
    }

    @Nested
    @DisplayName("금액 검증")
    class AmountValidation {

        @Test
        @DisplayName("환전 미리보기_금액이 0 이면_400 을 받는다")
        void previewExchange_zeroAmount_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("KRW_TO_USD", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("환전 금액은 0보다 커야 합니다."));
        }

        @Test
        @DisplayName("환전 미리보기_원화에 소수가 있으면_400 을 받는다")
        void previewExchange_fractionalKrw_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("KRW_TO_USD", "1500000.5"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("원화는 1원 단위로만 환전할 수 있습니다."));
        }

        @Test
        @DisplayName("환전 미리보기_달러가 센트보다 잘게 나뉘면_400 을 받는다")
        void previewExchange_fractionalCent_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(preview("USD_TO_KRW", "100.005"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("달러는 1센트 단위로만 환전할 수 있습니다."));
        }

        @Test
        @DisplayName("환전 미리보기_지수 표기로 거대한 금액을 보내면_전개하지 않고 400 을 받는다")
        void previewExchange_hugeAmount_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 입력은 열 글자지만 전개하면 900만 자리다. 자릿수 검사는 이것을 통과시킨다 —
            // scale 이 음수라 "소수가 있는가"에 걸리지 않는다.
            mockMvc.perform(preview("KRW_TO_USD", "1E+9000000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("환전 금액이 너무 큽니다."));
        }

        @Test
        @DisplayName("환전 실행_지수 표기로 거대한 금액을 보내면_잔고 검사 전에 400 을 받는다")
        void exchange_hugeAmount_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(exchange("KRW_TO_USD", "1E+9000000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("환전 금액이 너무 큽니다."));
            assertCash("45000000.0000", "12000.0000");
        }

        @Test
        @DisplayName("환전 미리보기_수령액이 0 이 되는 금액이면_400 을 받는다")
        void previewExchange_amountTooSmall_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 1원은 수수료 0, 수령액 1/1200 = 0.00083... 이고 내림하면 0 이다.
            mockMvc.perform(preview("KRW_TO_USD", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("환전 금액이 너무 작아 받을 금액이 없습니다."));
        }
    }

    @Nested
    @DisplayName("실행")
    class Execution {

        @Test
        @DisplayName("환전 실행_원화에서 달러로_두 통화 현금이 보낸 금액과 수령액만큼 변한다")
        void exchange_krwToUsd_movesBothWallets() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(exchange("KRW_TO_USD", "1500000"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.exchangeId").isNumber())
                    .andExpect(jsonPath("$.data.sendAmount").value("1500000"))
                    .andExpect(jsonPath("$.data.fee").value("750"))
                    .andExpect(jsonPath("$.data.receiveAmount").value("1249.37"))
                    .andExpect(jsonPath("$.data.gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.tickSeq").value(FIRST_TICK));

            // 45,000,000 - 1,500,000 = 43,500,000 / 12,000 + 1249.37 = 13,249.37
            assertCash("43500000.0000", "13249.3700");
        }

        @Test
        @DisplayName("환전 실행_달러에서 원화로_두 통화 현금이 반대로 변한다")
        void exchange_usdToKrw_movesBothWallets() throws Exception {
            givenAccount();
            givenTradingAt(SECOND_TICK);

            mockMvc.perform(exchange("USD_TO_KRW", "1000.05"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.receiveAmount").value("1209455"))
                    .andExpect(jsonPath("$.data.gameDate").value("2020-01-03"));

            // 12,000 - 1000.05 = 10,999.95 / 45,000,000 + 1,209,455 = 46,209,455
            assertCash("46209455.0000", "10999.9500");
        }

        @Test
        @DisplayName("환전 실행_잠기지 않은 현금이 부족하면_409 이고 잔고가 변하지 않는다")
        void exchange_beyondAvailableCash_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 원화 현금은 45,000,000 이지만 3,000,000 이 예약·청약에 잠겨 있다.
            mockMvc.perform(exchange("KRW_TO_USD", "42000001"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("환전할 현금이 부족합니다."));
            assertCash("45000000.0000", "12000.0000");
        }

        @Test
        @DisplayName("환전 실행_잠기지 않은 현금 전액이면_성공한다")
        void exchange_exactlyAvailableCash_succeeds() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(exchange("KRW_TO_USD", "42000000"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.sendAmount").value("42000000"));
        }

        @Test
        @DisplayName("환전 실행_방향이 없으면_400 을 받는다")
        void exchange_withoutDirection_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(post(EXCHANGES_URI)
                            .header(AUTH_TOKEN_HEADER, session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"1500000\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("이력")
    class History {

        @Test
        @DisplayName("환전 이력_두 건을 환전하면_최신순으로 온다")
        void searchExchanges_afterTwoExchanges_returnsNewestFirst() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);
            mockMvc.perform(exchange("KRW_TO_USD", "1500000")).andExpect(status().isCreated());
            givenTradingAt(SECOND_TICK);
            mockMvc.perform(exchange("USD_TO_KRW", "1000.05")).andExpect(status().isCreated());

            mockMvc.perform(get(EXCHANGES_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.content[0].direction").value("USD_TO_KRW"))
                    .andExpect(jsonPath("$.data.content[0].gameDate").value("2020-01-03"))
                    .andExpect(jsonPath("$.data.content[1].direction").value("KRW_TO_USD"))
                    .andExpect(jsonPath("$.data.content[1].gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.content[1].receiveAmount").value("1249.37"));
        }

        @Test
        @DisplayName("환전 이력_페이지 크기를 1 로 주면_한 건과 다음 페이지 표시가 온다")
        void searchExchanges_withPageSize_paginates() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);
            mockMvc.perform(exchange("KRW_TO_USD", "1000000")).andExpect(status().isCreated());
            mockMvc.perform(exchange("KRW_TO_USD", "2000000")).andExpect(status().isCreated());

            mockMvc.perform(get(EXCHANGES_URI).header(AUTH_TOKEN_HEADER, session).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.size").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    // 실제 시각이 같으면 식별자가 순서를 정한다 — 나중 것이 먼저 온다.
                    .andExpect(jsonPath("$.data.content[0].sendAmount").value("2000000"));
        }
    }

    private MockHttpServletRequestBuilder preview(String direction, String amount) {
        return get(PREVIEW_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .param("direction", direction)
                .param("amount", amount);
    }

    private MockHttpServletRequestBuilder exchange(String direction, String amount) {
        return post(EXCHANGES_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"direction\":\"%s\",\"amount\":\"%s\"}".formatted(direction, amount));
    }

    /** 그 틱까지 공개된 거래 구간으로 재생을 고정한다. */
    private void givenTradingAt(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    /** 개장 전 브리핑 구간. 거래 게이트가 닫혀 있다. */
    private void givenBriefingAt(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.BRIEFING), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.BRIEFING);
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

    /** 저장된 현금을 값으로 비교한다. NUMERIC 의 표현 자릿수가 아니라 금액이 같은지를 본다. */
    private void assertCash(String krwCash, String usdCash) {
        assertEquals(0, new BigDecimal(krwCash).compareTo(cash("krw_cash")), "원화 현금");
        assertEquals(0, new BigDecimal(usdCash).compareTo(cash("usd_cash")), "달러 현금");
    }

    private BigDecimal cash(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID);
    }
}
