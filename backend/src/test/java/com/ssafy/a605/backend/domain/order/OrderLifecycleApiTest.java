package com.ssafy.a605.backend.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * 미체결 예약의 조회·수정·취소 (기획서 6.3). HTTP 경계에서 응답과 <b>잠금에 실제로 남은
 * 것</b>을 함께 본다.
 *
 * <p>잠금을 확인하는 이유는 이 영역의 실패가 조용하기 때문이다 — 응답이 200 인 채로 현금이
 * 묶여 있거나, 거절된 수정이 잠금만 풀어 놓고 끝나는 모습으로 온다.
 *
 * <p>체결은 여기서 보지 않는다 ({@code OrderFillApiTest} 의 몫). 시계를 앵커와 같은 시각에
 * 세워 두어 틱이 진행되지 않는 상태로만 호출한다.
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
class OrderLifecycleApiTest {

    private static final String ORDERS_URI = "/api/v1/orders";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 960L;
    private static final long OTHER_ACCOUNT_ID = 961L;

    /**
     * 가격 자릿수가 결제 통화보다 잘은 종목. 픽스처 종목에는 없어 이 테스트가 직접 만든다 —
     * 자릿수가 4 인데 달러 결제라 금액이 두 자리로 반올림되며 0 이 되는 칸이 존재한다.
     */
    private static final String COIN_SYMBOL = "LIFECYCLE_TEST_COIN";

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;

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
        user = fixture.user("예약주인");
        session = sessions.createSessionFor(user);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM orders WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        // 신규 상장 공지가 전원에게 간다 (noti.md 의 NEW_LISTING). 픽스처의 상장 예정 종목이
        // 2 번 틱에 상장하므로 틱을 공개하는 테스트는 어느 계좌든 알림을 받는다.
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM instruments WHERE symbol = ?", COIN_SYMBOL);
    }

    @Nested
    @DisplayName("미체결 예약 조회")
    class OpenOrders {

        @Test
        @DisplayName("예약 조회_세션이 없으면_401 을 받는다")
        void openOrders_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(ORDERS_URI)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("예약 조회_시즌에 참가하지 않았으면_404 를 받는다")
        void openOrders_withoutAccount_returnsNotFound() throws Exception {
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("예약 조회_미체결이 있으면_등록 순서로 오고 공개 틱이 실린다")
        void openOrders_returnsInRegistrationOrderWithTick() throws Exception {
            givenAccount("30000");
            givenTradingAt(FIRST_TICK);
            long first = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");
            long second = givenOpenBuyOrder("REPLAY_TEST_A", 2, "10000", "20000");

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(1))
                    .andExpect(jsonPath("$.data.orders.length()").value(2))
                    .andExpect(jsonPath("$.data.orders[0].orderId").value(first))
                    .andExpect(jsonPath("$.data.orders[0].side").value("BUY"))
                    .andExpect(jsonPath("$.data.orders[0].quantity").value(1))
                    .andExpect(jsonPath("$.data.orders[0].limitPrice").value("10000"))
                    .andExpect(jsonPath("$.data.orders[0].currency").value("KRW"))
                    .andExpect(jsonPath("$.data.orders[0].lockedAmount").value("10000"))
                    .andExpect(jsonPath("$.data.orders[0].lockedQuantity").doesNotExist())
                    .andExpect(jsonPath("$.data.orders[0].createdGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.orders[0].expiresAt").exists())
                    .andExpect(jsonPath("$.data.orders[1].orderId").value(second));
        }

        @Test
        @DisplayName("예약 조회_거래 시간이 아니어도_열람된다")
        void openOrders_outsideTrading_isAllowed() throws Exception {
            givenAccount("10000");
            givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");
            // 열람 전용 시간이다. 기획서 6.2.1 이 제한하는 것은 등록·수정·취소뿐이다.
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.CLOSE), ReplayFixture.START,
                    BigDecimal.ONE, FIRST_TICK, 1, ReplayFixture.CLOSE);

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orders.length()").value(1));
        }

        @Test
        @DisplayName("예약 조회_닫힌 예약은_목록에 없다")
        void openOrders_excludesClosedOrders() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long open = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");
            givenClosedOrder("FILLED");
            givenClosedOrder("CANCELLED");
            givenClosedOrder("EXPIRED");

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orders.length()").value(1))
                    .andExpect(jsonPath("$.data.orders[0].orderId").value(open));
        }

        @Test
        @DisplayName("예약 조회_미체결이 없으면_빈 목록이고 공개 틱은 실린다")
        void openOrders_withoutOrders_returnsEmptyListWithTick() throws Exception {
            givenAccount("0");
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(1))
                    .andExpect(jsonPath("$.data.orders.length()").value(0));
        }

        @Test
        @DisplayName("예약 조회_최초 공개 전이면_공개 틱이 null 이다")
        void openOrders_beforeFirstTick_hasNullTick() throws Exception {
            givenAccount("0");
            // fixture.reset 직후는 아직 아무 틱도 공개되지 않은 상태다.

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").doesNotExist());
        }

        @Test
        @DisplayName("예약 조회_매도 예약이면_잠긴 수량이 실리고 잠금액은 없다")
        void openOrders_sellOrder_carriesLockedQuantity() throws Exception {
            givenAccount("0");
            givenPosition("REPLAY_TEST_A", 4, "36000.0000");
            givenTradingAt(FIRST_TICK);
            givenOpenSellOrder("REPLAY_TEST_A", 4, "12000");

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orders[0].side").value("SELL"))
                    .andExpect(jsonPath("$.data.orders[0].lockedQuantity").value(4))
                    // 매수·매도가 서로 다른 것을 잠그므로 하나는 항상 비어 있다.
                    .andExpect(jsonPath("$.data.orders[0].lockedAmount").doesNotExist());
        }

        @Test
        @DisplayName("예약 조회_달러 종목이면_지정가와 잠금액이 두 자리로 온다")
        void openOrders_usdInstrument_usesItsScales() throws Exception {
            givenAccount("0");
            givenTradingAt(FIRST_TICK);
            // 검증 종목 C 는 달러 결제이고 가격 자릿수가 2 다.
            givenOpenBuyOrderIn("REPLAY_TEST_C", "USD", 2, "902.1", "1804.2");

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orders[0].currency").value("USD"))
                    .andExpect(jsonPath("$.data.orders[0].limitPrice").value("902.10"))
                    .andExpect(jsonPath("$.data.orders[0].lockedAmount").value("1804.20"));
        }

        @Test
        @DisplayName("예약 조회_남의 예약은_보이지 않는다")
        void openOrders_excludesOthers() throws Exception {
            givenAccount("0");
            givenOtherAccount();
            givenTradingAt(FIRST_TICK);
            jdbc.update("""
                    INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                        locked_amount, locked_quantity, status, expires_at,
                                        created_tick_seq, created_at)
                    VALUES (?, ?, 'BUY', 10000, 1, 'KRW', 10000, NULL, 'OPEN',
                            now() + interval '1 hour', 1, now())
                    """, OTHER_ACCOUNT_ID, instrumentId("REPLAY_TEST_A"));

            mockMvc.perform(get(ORDERS_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orders.length()").value(0));
        }
    }

    @Nested
    @DisplayName("미체결 예약 수정")
    class Amend {

        @Test
        @DisplayName("예약 수정_수량만 바꾸면_지정가는 유지되고 잠금이 재계산된다")
        void amend_quantityOnly_recalculatesLock() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(amend(orderId, "{\"quantity\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orderId").value(orderId))
                    .andExpect(jsonPath("$.data.quantity").value(3))
                    .andExpect(jsonPath("$.data.limitPrice").value("10000"))
                    .andExpect(jsonPath("$.data.lockedAmount").value("30000"));

            // 잠금은 10,000 이 풀리고 30,000 이 다시 걸린 결과다. 총 현금은 그대로다.
            assertCash("locked_krw", "30000");
            assertCash("krw_cash", "45000000");
        }

        @Test
        @DisplayName("예약 수정_지정가만 바꾸면_수량은 유지된다")
        void amend_limitPriceOnly_keepsQuantity() throws Exception {
            givenAccount("20000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 2, "10000", "20000");

            mockMvc.perform(amend(orderId, "{\"limitPrice\":\"9000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.quantity").value(2))
                    .andExpect(jsonPath("$.data.limitPrice").value("9000"))
                    .andExpect(jsonPath("$.data.lockedAmount").value("18000"));

            assertCash("locked_krw", "18000");
        }

        @Test
        @DisplayName("예약 수정_잠금이 잔고를 넘으면_409 이고 원래 예약이 그대로다")
        void amend_beyondBalance_leavesOrderUntouched() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            // 원화 4,500 만이 전부인데 5,000 만을 잠그려 한다.
            mockMvc.perform(amend(orderId, "{\"quantity\":5000}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("원화 현금이 부족합니다."));

            // 부분 성공이 없다. 잠금을 이미 풀어 둔 상태까지 함께 되돌아가야 한다.
            assertOrder(orderId, 1, "10000", "10000");
            assertCash("locked_krw", "10000");
        }

        @Test
        @DisplayName("예약 수정_매도 수량을 그대로 두면_자기 잠금 때문에 거부되지 않는다")
        void amend_sellKeepingQuantity_isAccepted() throws Exception {
            givenAccount("0");
            givenPosition("REPLAY_TEST_A", 4, "36000.0000");
            givenTradingAt(FIRST_TICK);
            // 보유 4주를 전부 잠근 매도다. 자기 잠금을 함께 세면 8주를 요구하는 꼴이 된다.
            long orderId = givenOpenSellOrder("REPLAY_TEST_A", 4, "12000");

            mockMvc.perform(amend(orderId, "{\"limitPrice\":\"13000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lockedQuantity").value(4))
                    .andExpect(jsonPath("$.data.limitPrice").value("13000"));
        }

        @Test
        @DisplayName("예약 수정_보유 수량을 넘기면_409 를 받는다")
        void amend_sellBeyondPosition_returnsConflict() throws Exception {
            givenAccount("0");
            givenPosition("REPLAY_TEST_A", 4, "36000.0000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenSellOrder("REPLAY_TEST_A", 4, "12000");

            mockMvc.perform(amend(orderId, "{\"quantity\":5}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("매도할 수 있는 보유 수량이 부족합니다."));

            assertOrder(orderId, 4, "12000", null);
        }

        @Test
        @DisplayName("예약 수정_둘 다 비어 있으면_400 을 받는다")
        void amend_withoutAnyField_returnsBadRequest() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(amend(orderId, "{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("수량과 지정가 중 최소 하나는 있어야 합니다."));
        }

        @Test
        @DisplayName("예약 수정_남의 예약이면_404 를 받는다")
        void amend_othersOrder_returnsNotFound() throws Exception {
            givenAccount("0");
            givenOtherAccount();
            givenTradingAt(FIRST_TICK);
            Long othersOrder = jdbc.queryForObject("""
                    INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                        locked_amount, locked_quantity, status, expires_at,
                                        created_tick_seq, created_at)
                    VALUES (?, ?, 'BUY', 10000, 1, 'KRW', 10000, NULL, 'OPEN',
                            now() + interval '1 hour', 1, now())
                    RETURNING id
                    """, Long.class, OTHER_ACCOUNT_ID, instrumentId("REPLAY_TEST_A"));

            mockMvc.perform(amend(othersOrder, "{\"quantity\":2}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("예약을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("예약 수정_수량과 지정가를 함께 바꾸면_둘 다 반영된다")
        void amend_bothFields_appliesBoth() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(amend(orderId, "{\"quantity\":2,\"limitPrice\":\"9500\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.quantity").value(2))
                    .andExpect(jsonPath("$.data.limitPrice").value("9500"))
                    .andExpect(jsonPath("$.data.lockedAmount").value("19000"));

            assertCash("locked_krw", "19000");
        }

        @Test
        @DisplayName("예약 수정_수량이 1 미만이면_400 을 받는다")
        void amend_nonPositiveQuantity_returnsBadRequest() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(amend(orderId, "{\"quantity\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("주문 수량은 1주 이상이어야 합니다."));

            assertOrder(orderId, 1, "10000", "10000");
        }

        @Test
        @DisplayName("예약 수정_지정가가 0 이하면_400 을 받는다")
        void amend_nonPositiveLimitPrice_returnsBadRequest() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(amend(orderId, "{\"limitPrice\":\"0\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("지정가는 0보다 커야 합니다."));
        }

        @Test
        @DisplayName("예약 수정_지정가 자릿수가 종목보다 잘면_400 을 받는다")
        void amend_tooFineLimitPrice_returnsBadRequest() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            // 검증 종목 A 는 원화 종목이고 가격 자릿수가 0 이다.
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(amend(orderId, "{\"limitPrice\":\"9999.5\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("이 종목의 지정가는 정수로만 입력할 수 있습니다."));
        }

        @Test
        @DisplayName("예약 수정_잠글 금액이 통화 최소 단위보다 작으면_400 을 받는다")
        void amend_belowCurrencyUnit_returnsBadRequest() throws Exception {
            givenAccount("1");
            givenFineGrainedCoin();
            givenTradingAt(FIRST_TICK);
            // 이 종목은 달러 결제인데 가격 자릿수가 4 다. 0.0001 은 통화 자릿수(2)로
            // 반올림하면 0.00 이라 잠금이 0 인 예약이 된다.
            long orderId = givenOpenBuyOrderIn(COIN_SYMBOL, "USD", 1, "1.0", "1.0");

            mockMvc.perform(amend(orderId, "{\"limitPrice\":\"0.0001\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("주문 금액이 너무 작아 예약할 수 없습니다."));
        }

        @Test
        @DisplayName("예약 수정_이미 닫힌 예약이면_409 를 받는다")
        void amend_closedOrder_returnsConflict() throws Exception {
            givenAccount("0");
            givenTradingAt(FIRST_TICK);
            long closed = givenClosedOrder("FILLED");

            mockMvc.perform(amend(closed, "{\"quantity\":2}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 처리된 예약입니다."));
        }

        @Test
        @DisplayName("예약 수정_없는 예약이면_404 를 받는다")
        void amend_unknownOrder_returnsNotFound() throws Exception {
            givenAccount("0");
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(amend(999999L, "{\"quantity\":2}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("예약을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("예약 수정_거래 시간이 아니면_409 를 받는다")
        void amend_outsideTrading_returnsConflict() throws Exception {
            givenAccount("10000");
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.CLOSE), ReplayFixture.START,
                    BigDecimal.ONE, FIRST_TICK, 1, ReplayFixture.CLOSE);

            mockMvc.perform(amend(orderId, "{\"quantity\":2}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("지금은 거래 시간이 아닙니다."));
        }
    }

    @Nested
    @DisplayName("미체결 예약 취소")
    class Cancel {

        @Test
        @DisplayName("예약 취소_매수면_204 이고 잠긴 현금이 반환된다")
        void cancel_buy_releasesCash() throws Exception {
            givenAccount("10000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");

            mockMvc.perform(delete(ORDERS_URI + "/" + orderId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNoContent());

            assertCash("locked_krw", "0");
            // 총 현금은 그대로다. 잠금은 돈을 옮긴 적이 없다.
            assertCash("krw_cash", "45000000");
            assertStatus(orderId, "CANCELLED");
        }

        @Test
        @DisplayName("예약 취소_매도면_잠긴 수량이 즉시 다시 쓰인다")
        void cancel_sell_releasesQuantity() throws Exception {
            givenAccount("0");
            givenPosition("REPLAY_TEST_A", 4, "36000.0000");
            givenTradingAt(FIRST_TICK);
            long orderId = givenOpenSellOrder("REPLAY_TEST_A", 4, "12000");

            mockMvc.perform(delete(ORDERS_URI + "/" + orderId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNoContent());

            assertStatus(orderId, "CANCELLED");
            // 잠금이 풀렸다는 것은 같은 수량을 다시 걸 수 있다는 뜻이다 — 계좌에는 흔적이 없고
            // OPEN 인 행이 사라진 것이 곧 해제다.
            mockMvc.perform(post("SELL", instrumentId("REPLAY_TEST_A"), 4, "12000"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("예약 취소_남의 예약이면_404 이고 그 예약은 그대로다")
        void cancel_othersOrder_returnsNotFound() throws Exception {
            givenAccount("0");
            givenOtherAccount();
            givenTradingAt(FIRST_TICK);
            Long othersOrder = jdbc.queryForObject("""
                    INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                        locked_amount, locked_quantity, status, expires_at,
                                        created_tick_seq, created_at)
                    VALUES (?, ?, 'BUY', 10000, 1, 'KRW', 10000, NULL, 'OPEN',
                            now() + interval '1 hour', 1, now())
                    RETURNING id
                    """, Long.class, OTHER_ACCOUNT_ID, instrumentId("REPLAY_TEST_A"));

            mockMvc.perform(delete(ORDERS_URI + "/" + othersOrder).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("예약을 찾을 수 없습니다."));

            assertStatus(othersOrder, "OPEN");
        }

        @Test
        @DisplayName("예약 취소_이미 닫힌 예약이면_409 를 받는다")
        void cancel_closedOrder_returnsConflict() throws Exception {
            givenAccount("0");
            givenTradingAt(FIRST_TICK);
            long closed = givenClosedOrder("FILLED");

            // 404 로 뭉뚱그리지 않는다. 내 예약이라 숨길 것이 없고, 사용자에게는 "목록이
            // 낡았다"는 다른 사건이다.
            mockMvc.perform(delete(ORDERS_URI + "/" + closed).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 처리된 예약입니다."));
        }

        @Test
        @DisplayName("예약 취소_거래 시간이 아니면_409 이고 잠금이 유지된다")
        void cancel_outsideTrading_returnsConflict() throws Exception {
            givenAccount("10000");
            long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10000", "10000");
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.CLOSE), ReplayFixture.START,
                    BigDecimal.ONE, FIRST_TICK, 1, ReplayFixture.CLOSE);

            mockMvc.perform(delete(ORDERS_URI + "/" + orderId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("지금은 거래 시간이 아닙니다."));

            assertStatus(orderId, "OPEN");
            assertCash("locked_krw", "10000");
        }

        @Test
        @DisplayName("예약 취소_없는 예약이면_404 를 받는다")
        void cancel_unknownOrder_returnsNotFound() throws Exception {
            givenAccount("0");
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(delete(ORDERS_URI + "/999999").header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("예약을 찾을 수 없습니다."));
        }
    }

    private MockHttpServletRequestBuilder amend(long orderId, String body) {
        return patch(ORDERS_URI + "/" + orderId)
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder post(String side, long instrumentId, int quantity,
                                               String limitPrice) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ORDERS_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderType":"LIMIT","side":"%s","instrumentId":%d,"quantity":%d,
                         "limitPrice":"%s"}
                        """.formatted(side, instrumentId, quantity, limitPrice));
    }

    /**
     * 그 틱까지 공개된 거래 구간으로 재생을 고정한다. 배속이 1 이어도 시계를 앵커와 같은
     * 시각에 세워 두므로 가상 위치가 움직이지 않는다 — 예약의 유효기간을 정하려면 폐장에
     * 도달하는 시각이 존재해야 해서 배속 0 을 쓸 수 없다.
     */
    private void givenTradingAt(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    /**
     * 검증 계좌. 달러도 함께 두는 것은 달러 종목 경로 때문이다 — 달러 예약을 고칠 때 옛
     * 잠금을 먼저 풀어야 하므로 {@code locked_usd} 가 그것을 덮을 만큼 있어야 한다.
     *
     * @param lockedKrw 잠긴 원화. 그 값이 예약의 잠금액과 맞아야 취소·수정이 성립한다
     */
    private void givenAccount(String lockedKrw) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 10000, CAST(? AS NUMERIC), 10,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, user.getId(), ReplayFixture.SEASON_ID, lockedKrw);
    }

    /** 종가는 두지 않는다 — 예약은 종가를 보지 않으므로 이 테스트에 필요 없다. */
    private void givenFineGrainedCoin() {
        jdbc.update("""
                INSERT INTO instruments (symbol, name, category, asset_type, currency, price_scale, summary)
                VALUES (?, '생명주기 검증용 코인', 'CRYPTO', 'CRYPTO', 'USD', 4,
                        '실제 시장 데이터가 아닌 주문 검증용 종목')
                """, COIN_SYMBOL);
    }

    private void givenOtherAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 10000, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, OTHER_ACCOUNT_ID, fixture.user("남의예약주인").getId(), ReplayFixture.SEASON_ID);
    }

    private void givenPosition(String symbol, int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, CAST(? AS NUMERIC))
                """, ACCOUNT_ID, instrumentId(symbol), quantity, totalCost);
    }

    private long givenOpenBuyOrder(String symbol, int quantity, String limitPrice,
                                   String lockedAmount) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'BUY', CAST(? AS NUMERIC), ?, 'KRW', CAST(? AS NUMERIC), NULL, 'OPEN',
                        now() + interval '1 hour', 1, now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId(symbol), limitPrice, quantity, lockedAmount);
    }

    /** 결제 통화를 지정하는 매수 예약. 달러 종목의 자릿수 경로를 보려면 필요하다. */
    private long givenOpenBuyOrderIn(String symbol, String currency, int quantity, String limitPrice,
                                     String lockedAmount) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'BUY', CAST(? AS NUMERIC), ?, ?, CAST(? AS NUMERIC), NULL, 'OPEN',
                        now() + interval '1 hour', 1, now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId(symbol), limitPrice, quantity, currency,
                lockedAmount);
    }

    private long givenOpenSellOrder(String symbol, int quantity, String limitPrice) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'SELL', CAST(? AS NUMERIC), ?, 'KRW', NULL, ?, 'OPEN',
                        now() + interval '1 hour', 1, now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId(symbol), limitPrice, quantity, quantity);
    }

    /** 닫힌 예약. 잠금 컬럼은 CHECK 가 OPEN 만 규정하므로 비워 둔다. */
    private long givenClosedOrder(String status) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at, closed_at)
                VALUES (?, ?, 'BUY', 10000, 1, 'KRW', NULL, NULL, ?,
                        now() + interval '1 hour', 1, now(), now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId("REPLAY_TEST_A"), status);
    }

    /** 저장된 현금을 값으로 비교한다. NUMERIC 의 표현 자릿수가 아니라 금액이 같은지를 본다. */
    private void assertCash(String column, String expected) {
        BigDecimal actual = jdbc.queryForObject(
                "SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column);
    }

    private void assertOrder(long orderId, int quantity, String limitPrice, String lockedAmount) {
        assertEquals(quantity, jdbc.queryForObject(
                "SELECT quantity FROM orders WHERE id = ?", Integer.class, orderId), "수량");
        assertEquals(0, new BigDecimal(limitPrice).compareTo(jdbc.queryForObject(
                "SELECT limit_price FROM orders WHERE id = ?", BigDecimal.class, orderId)), "지정가");
        BigDecimal locked = jdbc.queryForObject(
                "SELECT locked_amount FROM orders WHERE id = ?", BigDecimal.class, orderId);
        if (lockedAmount == null) {
            assertEquals(null, locked, "잠금액");
            return;
        }
        assertEquals(0, new BigDecimal(lockedAmount).compareTo(locked), "잠금액");
    }

    private void assertStatus(long orderId, String expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId), "예약 상태");
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
