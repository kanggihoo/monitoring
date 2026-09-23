package com.ssafy.a605.backend.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * HTTP 경계에서 시장가 체결의 관찰 가능한 결과만 본다 — 응답, 그리고 <b>현금·포지션·체결
 * 기록에 실제로 남은 것</b>.
 *
 * <p>회계 테스트라 저장된 값을 직접 확인한다. 응답만 보면 "돈이 맞게 움직였다"를 검증할 수
 * 없고, 이 영역의 실패는 응답이 200 인 채로 잔고가 틀어지는 모습으로 온다.
 *
 * <p>편성·환율·종가는 {@code R__replay_fixture.sql} 이 심은 검증용 시즌을 그대로 쓴다. 1일차
 * 첫 틱의 종가가 10,100 이고 셋째 틱이 10,300 이라 <b>사고 나서 오른 뒤 파는</b> 시나리오를
 * 픽스처만으로 만들 수 있다.
 *
 * <p>시계를 앵커와 같은 시각에 세워 둔 상태에서만 호출한다. 폴러는 실제 타이머로 돌지만 도달한
 * 항목이 없어 아무것도 진행하지 않으므로, phase 와 공개 틱을 테스트가 정확히 쥔다 —
 * {@link #givenTradingAt} 가 그 상태를 만든다.
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
class OrderPlacementApiTest {

    private static final String ORDERS_URI = "/api/v1/orders";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 922L;

    /**
     * 가격 자릿수가 결제 통화보다 잘은 종목. 픽스처 종목에는 없어 이 테스트가 직접 만든다 —
     * 자릿수가 4 인데 달러 결제라 금액이 두 자리로 반올림되며 0 이 되는 칸이 존재한다.
     */
    private static final String COIN_SYMBOL = "PLACEMENT_TEST_COIN";

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03. 검증 종목 B 는 이날 휴장이다. */
    private static final long SECOND_TICK = 2L;

    /** 픽스처 1일차 셋째 틱. 게임 거래일 2020-01-06, 검증 종목 종가 10,300. */
    private static final long THIRD_TICK = 3L;

    /** 픽스처 2일차 첫 틱. 일차 초기화 게이트를 보려면 오늘 틱이 공개돼 있어야 한다. */
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

    private User user;
    private String session;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("체결주인");
        session = sessions.createSessionFor(user);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM executions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM orders WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM instruments WHERE symbol = ?", COIN_SYMBOL);
    }

    @Nested
    @DisplayName("경로 보호와 phase 게이팅")
    class Gating {

        @Test
        @DisplayName("주문 생성_세션이 없으면_401 을 받는다")
        void placeOrder_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(post(ORDERS_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderType\":\"MARKET\",\"side\":\"BUY\",\"instrumentId\":1,\"quantity\":3}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("주문 생성_거래 시간이 아니면_409 이고 아무것도 바뀌지 않는다")
        void placeOrder_outsideTrading_returnsConflict() throws Exception {
            givenAccount();
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.BRIEFING), ReplayFixture.START,
                    BigDecimal.ZERO, FIRST_TICK, 1, ReplayFixture.BRIEFING);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("지금은 거래 시간이 아닙니다."));
            assertKrwCash("45000000");
            assertExecutionCount(0);
        }

        @Test
        @DisplayName("주문 생성_개장했지만 오늘 첫 틱 전이면_409 이고 어제 종가로 거래되지 않는다")
        void placeOrder_beforeTodaysFirstTick_returnsConflict() throws Exception {
            givenAccount();
            // 2일차 개장 직후다. 공개 틱 순번은 일차가 넘어가도 리셋되지 않아 1일차 마지막
            // 틱(4)을 가리킨다 — ReplayStateTest 가 그 성질을 고정한다. 그대로 쓰면 어제
            // 종가(2022-03-31)로 오늘 주문이 나간다.
            fixture.store(2, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                    BigDecimal.ONE, 4L, 2, ReplayFixture.TRADING_OPEN);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("시세가 아직 공개되지 않았습니다."));
            mockMvc.perform(limitOrder("BUY", instrumentId("REPLAY_TEST_A"), 3, "9000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("시세가 아직 공개되지 않았습니다."));

            assertKrwCash("45000000");
            assertCash("locked_krw", "3000000");
            assertExecutionCount(0);
            assertOpenOrderCount(0);
        }

        /**
         * 일차 초기화는 실패해도 재생을 멈추지 않는다 ({@code DailySettlementService.on}).
         * 막지 않으면 초기화가 밀린 계좌만 <b>어제 자산으로 오늘 거래한다.</b>
         */
        @Test
        @DisplayName("주문 생성_오늘의 일차 초기화를 받지 못한 계좌면_409 이고 아무것도 바뀌지 않는다")
        void placeOrder_beforeDailyReset_returnsConflict() throws Exception {
            givenAccount();
            // 계좌의 기준 일차는 1 인데 시계는 2 일차다 — 초기화가 돌지 않은 상태다.
            // 틱은 2 일차 것을 써야 한다. 1 일차 틱이면 시세 공개 검사에 먼저 걸린다.
            givenTradingAt(FIRST_TICK_DAY_2, 2);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("오늘의 계좌 초기화가 완료되지 않아 거래할 수 없습니다. 잠시 후 다시 시도해 주세요."));
            mockMvc.perform(limitOrder("BUY", instrumentId("REPLAY_TEST_A"), 3, "9000"))
                    .andExpect(status().isConflict());

            assertKrwCash("45000000");
            assertExecutionCount(0);
            assertOpenOrderCount(0);
        }

        @Test
        @DisplayName("주문 생성_시즌에 참가하지 않았으면_404 를 받는다")
        void placeOrder_withoutAccount_returnsNotFound() throws Exception {
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }
    }

    /**
     * 예약 등록. <b>체결이 일어나지 않는 것까지가 계약</b>이다 — 등록 시점에는 판정하지 않으며
     * (기획서 6.4.1) 현재 종가보다 유리한 지정가를 걸어도 다음 틱을 기다린다. 그래서 이 묶음의
     * 모든 테스트가 체결 0 건을 함께 확인한다.
     */
    @Nested
    @DisplayName("예약 등록")
    class LimitRegistration {

        @Test
        @DisplayName("예약 매수_등록되면_현금이 잠기고 체결은 없다")
        void limitBuy_locksCashWithoutFilling() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(limitOrder("BUY", instrumentId("REPLAY_TEST_A"), 3, "9000"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.orderType").value("LIMIT"))
                    .andExpect(jsonPath("$.data.execution").doesNotExist())
                    .andExpect(jsonPath("$.data.order.side").value("BUY"))
                    .andExpect(jsonPath("$.data.order.quantity").value(3))
                    .andExpect(jsonPath("$.data.order.limitPrice").value("9000"))
                    .andExpect(jsonPath("$.data.order.currency").value("KRW"))
                    // 지정가 x 수량. 매수에 수수료가 없어 이 값이 곧 체결 금액이다.
                    .andExpect(jsonPath("$.data.order.lockedAmount").value("27000"))
                    .andExpect(jsonPath("$.data.order.lockedQuantity").doesNotExist())
                    .andExpect(jsonPath("$.data.order.createdGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.order.expiresAt").exists());

            // 총액은 그대로다. 잠금은 현금이 나가는 것이 아니라 쓸 수 없게 표시하는 것이다.
            assertKrwCash("45000000");
            assertCash("locked_krw", "3027000");
            assertOpenOrderCount(1);
            assertExecutionCount(0);
            assertNoPosition("REPLAY_TEST_A");
        }

        @Test
        @DisplayName("예약 매수_현재 종가보다 유리한 지정가여도_즉시 체결되지 않는다")
        void limitBuy_aboveCurrentClose_doesNotFillImmediately() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 첫 틱 종가가 10,100 이라 11,000 매수는 조건을 이미 충족한다. 그래도 등록만 된다 —
            // 즉시 체결을 원하는 요구는 시장가가 전담한다 (ord.md §등록 즉시 판정하지 않는다).
            mockMvc.perform(limitOrder("BUY", instrumentId("REPLAY_TEST_A"), 1, "11000"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.order.orderId").exists());

            assertExecutionCount(0);
            assertOpenOrderCount(1);
        }

        @Test
        @DisplayName("예약 매수_잠기지 않은 현금보다 크면_409 이고 아무것도 잠기지 않는다")
        void limitBuy_beyondAvailableCash_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 원화 4,500 만에서 300 만이 이미 잠겨 4,200 만이 한도다.
            mockMvc.perform(limitOrder("BUY", instrumentId("REPLAY_TEST_A"), 5000, "10000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("원화 현금이 부족합니다."));

            assertCash("locked_krw", "3000000");
            assertOpenOrderCount(0);
        }

        @Test
        @DisplayName("예약 매도_보유 수량이 있으면_수량이 잠기고 현금은 그대로다")
        void limitSell_locksQuantity() throws Exception {
            givenAccount();
            givenPosition("REPLAY_TEST_A", 10, "90000.0000");
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(limitOrder("SELL", instrumentId("REPLAY_TEST_A"), 4, "12000"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.order.lockedQuantity").value(4))
                    // 매도는 수량을 잠근다. 금액과 단위가 달라 한 필드에 합치지 않는다.
                    .andExpect(jsonPath("$.data.order.lockedAmount").doesNotExist());

            assertCash("locked_krw", "3000000");
            // 포지션은 그대로다 — 잠금은 orders.locked_quantity 가 들고 있다.
            assertPosition("REPLAY_TEST_A", 10, "90000.0000");
            assertExecutionCount(0);
        }

        @Test
        @DisplayName("예약 매도_이미 잠긴 수량을 더해 초과하면_409 를 받는다")
        void limitSell_beyondUnlockedQuantity_returnsConflict() throws Exception {
            givenAccount();
            givenPosition("REPLAY_TEST_A", 10, "90000.0000");
            givenOpenSellOrder("REPLAY_TEST_A", 8);
            givenTradingAt(FIRST_TICK);

            // 10 주 중 8 주가 이미 잠겨 2 주만 남는다. 공매도 차단이다 (기획서 6.5.3).
            mockMvc.perform(limitOrder("SELL", instrumentId("REPLAY_TEST_A"), 3, "12000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("매도할 수 있는 보유 수량이 부족합니다."));

            assertOpenOrderCount(1);
        }

        @Test
        @DisplayName("예약 매도_보유하지 않은 종목이면_409 를 받는다")
        void limitSell_withoutPosition_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(limitOrder("SELL", instrumentId("REPLAY_TEST_A"), 1, "12000"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("매도할 수 있는 보유 수량이 부족합니다."));

            assertOpenOrderCount(0);
        }

        @Test
        @DisplayName("예약 등록_휴장 종목이어도_등록된다")
        void limitOrder_onMarketClosedInstrument_isAccepted() throws Exception {
            givenAccount();
            givenTradingAt(SECOND_TICK);

            // 검증 종목 B 는 둘째 틱에 종가가 없다. 시장가는 409 지만 (기획서 4.1.4) 예약은
            // 종가를 보지 않으므로 걸 수 있다 — 그날 판정만 일어나지 않는다.
            mockMvc.perform(limitOrder("BUY", instrumentId("REPLAY_TEST_B"), 1, "9000"))
                    .andExpect(status().isCreated());

            assertOpenOrderCount(1);
        }

        @Test
        @DisplayName("예약 매수_잠글 금액이 통화 최소 단위보다 작으면_400 을 받는다")
        void limitBuy_belowCurrencyUnit_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 검증 종목 C 는 달러 종목이고 가격 자릿수가 2 다. 0.001 은 통화 자릿수로
            // 반올림하면 0.00 이라 잠금이 0 인 예약이 되고, 체결되면 수량은 있는데 취득
            // 총액이 0 인 포지션이 되어 CHECK 를 어겨 매 틱 체결이 실패한다.
            givenFineGrainedCoin();

            // 이 종목은 달러 결제인데 가격 자릿수가 4 다. 0.0001 은 통화 자릿수(2)로
            // 반올림하면 0.00 이라 잠금이 0 인 예약이 되고, 체결되면 수량은 있는데 취득
            // 총액이 0 인 포지션이 되어 CHECK 를 어겨 매 틱 체결이 실패한다.
            mockMvc.perform(limitOrder("BUY", instrumentId(COIN_SYMBOL), 1, "0.0001"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("주문 금액이 너무 작아 예약할 수 없습니다."));

            assertOpenOrderCount(0);
        }

        @Test
        @DisplayName("예약 등록_지정가가 없으면_400 을 받는다")
        void limitOrder_withoutLimitPrice_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(order("LIMIT", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("예약 주문에는 지정가가 필요합니다."));

            assertOpenOrderCount(0);
        }
    }

    @Nested
    @DisplayName("시장가 매수")
    class MarketBuy {

        @Test
        @DisplayName("시장가 매수_원화 종목이면_현금이 줄고 포지션과 체결이 남는다")
        void placeOrder_marketBuy_movesCashAndPosition() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 10,100 x 3 = 30,300. 매수에는 수수료가 없어 나가는 현금이 곧 취득 총액이다.
            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.orderType").value("MARKET"))
                    .andExpect(jsonPath("$.data.order").doesNotExist())
                    .andExpect(jsonPath("$.data.execution.executionId").isNumber())
                    .andExpect(jsonPath("$.data.execution.side").value("BUY"))
                    .andExpect(jsonPath("$.data.execution.quantity").value(3))
                    .andExpect(jsonPath("$.data.execution.executedPrice").value("10100"))
                    .andExpect(jsonPath("$.data.execution.currency").value("KRW"))
                    .andExpect(jsonPath("$.data.execution.amount").value("30300"))
                    .andExpect(jsonPath("$.data.execution.appliedFeeRate").value(0.00))
                    .andExpect(jsonPath("$.data.execution.fee").value("0"))
                    .andExpect(jsonPath("$.data.execution.realizedPnl").doesNotExist())
                    .andExpect(jsonPath("$.data.execution.gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.execution.tickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.execution.executedAt").isString());

            assertKrwCash("44969700");
            assertPosition("REPLAY_TEST_A", 3, "30300");
            assertExecutionCount(1);
        }

        @Test
        @DisplayName("시장가 매수_두 번 사면_취득 총액이 더해져 평단이 가중 평균이 된다")
        void placeOrder_marketBuyTwice_accumulatesCost() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);
            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isCreated());
            givenTradingAt(THIRD_TICK);

            // 나눗셈 없이 더하기만 하므로 이동평균법이 자연히 성립한다 (기획서 7.3.3).
            // 30,300 + 10,300 x 2 = 50,900 이고 5주라 평단 10,180 이다.
            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 2))
                    .andExpect(status().isCreated());

            assertPosition("REPLAY_TEST_A", 5, "50900");
            assertExecutionCount(2);
        }

        @Test
        @DisplayName("시장가 매수_잠기지 않은 원화가 부족하면_409 이고 아무것도 바뀌지 않는다")
        void placeOrder_marketBuyBeyondCash_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 원화 현금은 45,000,000 이지만 3,000,000 이 예약·청약에 잠겨 있다.
            // 10,100 x 5,000 = 50,500,000 은 잠기지 않은 42,000,000 을 넘는다.
            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 5000))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("원화 현금이 부족합니다."));
            assertKrwCash("45000000");
            assertNoPosition("REPLAY_TEST_A");
            assertExecutionCount(0);
        }

        @Test
        @DisplayName("시장가 매수_달러가 부족하면_원화가 많아도 409 이고 환전을 안내한다")
        void placeOrder_marketBuyWithoutUsd_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            // 매수 검증은 결제 통화 기준이다 (기획서 6.5.1). 잠기지 않은 달러는 9,446.17 뿐이다.
            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_C"), 1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("달러 현금이 부족합니다. 환전 후 다시 시도해 주세요."));
            assertKrwCash("45000000");
            assertExecutionCount(0);
        }

        @Test
        @DisplayName("시장가 매수_달러 종목이면_달러 현금만 줄고 원화는 그대로다")
        void placeOrder_marketBuyInUsd_movesUsdOnly() throws Exception {
            givenAccount();
            jdbc.update("UPDATE accounts SET usd_cash = 20000 WHERE id = ?", ACCOUNT_ID);
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_C"), 1))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.execution.currency").value("USD"))
                    .andExpect(jsonPath("$.data.execution.executedPrice").value("10100.00"))
                    .andExpect(jsonPath("$.data.execution.amount").value("10100.00"));

            assertKrwCash("45000000");
            assertCash("usd_cash", "9900");
            assertPosition("REPLAY_TEST_C", 1, "10100");
        }
    }

    @Nested
    @DisplayName("시장가 매도")
    class MarketSell {

        @Test
        @DisplayName("시장가 매도_일부를 팔면_취득 총액이 비례 차감되고 실현손익이 저장된다")
        void placeOrder_marketSellPartial_removesCostProportionally() throws Exception {
            givenAccount();
            givenPosition("REPLAY_TEST_A", 3, "30300");
            givenTradingAt(THIRD_TICK);

            // 10,300 x 2 = 20,600. 수수료 30.9 는 원화 정수라 31 이고 대금은 20,569 다.
            // 원가는 30,300 x 2 / 3 = 20,200 이므로 실현손익은 369 다.
            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 2))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.execution.side").value("SELL"))
                    .andExpect(jsonPath("$.data.execution.amount").value("20600"))
                    .andExpect(jsonPath("$.data.execution.appliedFeeRate").value(0.15))
                    .andExpect(jsonPath("$.data.execution.fee").value("31"))
                    .andExpect(jsonPath("$.data.execution.realizedPnl").value("369"))
                    .andExpect(jsonPath("$.data.execution.gameDate").value("2020-01-06"));

            assertKrwCash("45020569");
            assertPosition("REPLAY_TEST_A", 1, "10100");
        }

        @Test
        @DisplayName("시장가 매도_전량을 팔면_보유 수량과 취득 총액이 함께 정확히 0 이 된다")
        void placeOrder_marketSellAll_zeroesQuantityAndCost() throws Exception {
            givenAccount();
            // 나눗셈이 딱 떨어지지 않는 취득 총액을 쓴다 — 10,000 / 3 은 무한소수다.
            givenPosition("REPLAY_TEST_A", 3, "10000");
            givenTradingAt(THIRD_TICK);
            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 1))
                    .andExpect(status().isCreated())
                    // 대금 10,285 에서 원가 10,000 x 1 / 3 = 3,333.3333 을 뺀 6,951.6667 이고
                    // 원화 자릿수로 표현하면 6,952 다.
                    .andExpect(jsonPath("$.data.execution.realizedPnl").value("6952"));
            assertPosition("REPLAY_TEST_A", 2, "6666.6667");

            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 2))
                    .andExpect(status().isCreated());

            // 비율로 계산하면 반올림 잔차가 남는다. 전량 매도는 남은 것을 그대로 털어내므로
            // 둘 다 정확히 0 이고, ck_positions_cost_matches_quantity 가 이것을 요구한다.
            assertPosition("REPLAY_TEST_A", 0, "0");
        }

        @Test
        @DisplayName("시장가 매도_무료 타임이면_수수료가 빠지지 않아 대금이 체결 금액과 같다")
        void placeOrder_marketSellDuringFeeFree_chargesNoFee() throws Exception {
            givenAccount();
            givenPosition("REPLAY_TEST_A", 3, "30300");
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.FEE_FREE_START), ReplayFixture.START,
                    BigDecimal.ZERO, THIRD_TICK, 1, ReplayFixture.FEE_FREE_START);

            // 평소라면 수수료 46 이 빠진다. 무료 타임의 효과는 매도에만 있다.
            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 3))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.execution.appliedFeeRate").value(0.00))
                    .andExpect(jsonPath("$.data.execution.fee").value("0"))
                    .andExpect(jsonPath("$.data.execution.realizedPnl").value("600"));

            assertKrwCash("45030900");
        }

        @Test
        @DisplayName("시장가 매도_보유하지 않은 종목이면_409 를 받는다")
        void placeOrder_marketSellWithoutPosition_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("매도할 수 있는 보유 수량이 부족합니다."));
            assertExecutionCount(0);
        }

        @Test
        @DisplayName("시장가 매도_예약 매도가 잠근 수량은_쓸 수 없다")
        void placeOrder_marketSellOverLockedQuantity_returnsConflict() throws Exception {
            givenAccount();
            givenPosition("REPLAY_TEST_A", 3, "30300");
            givenOpenSellOrder("REPLAY_TEST_A", 2);
            givenTradingAt(THIRD_TICK);

            // 3주를 갖고 있지만 2주가 예약 매도에 잠겨 있어 쓸 수 있는 것은 1주다 (기획서 6.2.3).
            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 2))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("매도할 수 있는 보유 수량이 부족합니다."));
            assertPosition("REPLAY_TEST_A", 3, "30300");

            mockMvc.perform(order("MARKET", "SELL", instrumentId("REPLAY_TEST_A"), 1))
                    .andExpect(status().isCreated());
            assertPosition("REPLAY_TEST_A", 2, "20200");
        }
    }

    @Nested
    @DisplayName("종목 상태와 입력 검증")
    class Validation {

        @Test
        @DisplayName("시장가 주문_휴장 종목이면_409 를 받는다")
        void placeOrder_marketOnClosedMarket_returnsConflict() throws Exception {
            givenAccount();
            givenTradingAt(SECOND_TICK);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_B"), 3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("휴장 중인 종목은 시장가로 주문할 수 없습니다."));
            assertExecutionCount(0);
        }

        @Test
        @DisplayName("주문 생성_수량이 0 이면_400 을 받는다")
        void placeOrder_zeroQuantity_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(order("MARKET", "BUY", instrumentId("REPLAY_TEST_A"), 0))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("주문 수량은 1주 이상이어야 합니다."));
        }

        @Test
        @DisplayName("주문 생성_유니버스에 없는 종목이면_404 를 받는다")
        void placeOrder_unknownInstrument_returnsNotFound() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(order("MARKET", "BUY", -1L, 3))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("주문 생성_주문 방향이 없으면_400 을 받는다")
        void placeOrder_withoutSide_returnsBadRequest() throws Exception {
            givenAccount();
            givenTradingAt(FIRST_TICK);

            mockMvc.perform(post(ORDERS_URI)
                            .header(AUTH_TOKEN_HEADER, session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderType\":\"MARKET\",\"instrumentId\":1,\"quantity\":3}"))
                    .andExpect(status().isBadRequest());
        }
    }

    private MockHttpServletRequestBuilder order(String orderType, String side, long instrumentId,
                                                int quantity) {
        return post(ORDERS_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderType\":\"%s\",\"side\":\"%s\",\"instrumentId\":%d,\"quantity\":%d}"
                        .formatted(orderType, side, instrumentId, quantity));
    }

    private MockHttpServletRequestBuilder limitOrder(String side, long instrumentId, int quantity,
                                                     String limitPrice) {
        return post(ORDERS_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderType":"LIMIT","side":"%s","instrumentId":%d,"quantity":%d,
                         "limitPrice":"%s"}
                        """.formatted(side, instrumentId, quantity, limitPrice));
    }

    /**
     * 그 틱까지 공개된 거래 구간으로 재생을 고정한다.
     *
     * <p><b>배속을 1 로 두면서도 진행되지 않는다.</b> 앵커를 개장(0)에 두고 시계를 앵커와 같은
     * 시각에 세워 두므로 가상 위치가 0 에서 움직이지 않고, 다음 경계(3300)도 다음 틱(65초쯤)도
     * 도달하지 않는다 — 폴러는 돌지만 할 일이 없다.
     *
     * <p>배속 0 이 아닌 이유는 <b>예약의 유효기간</b>이다. 정지 중에는 폐장에 도달하는 실제
     * 시각이 존재하지 않아 ({@code ReplayState.atOffset}) 예약을 등록할 수 없고, 그것은 구현이
     * 아니라 시계 설계의 결과다.
     */
    private void givenTradingAt(long tickSeq) {
        givenTradingAt(tickSeq, 1);
    }

    /** 일차를 골라 거래 구간에 세운다. 계좌의 기준 일차와 어긋나게 두면 초기화 미완료 상태다. */
    private void givenTradingAt(long tickSeq, int seasonDay) {
        fixture.store(seasonDay, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, tickSeq, seasonDay, ReplayFixture.TRADING_OPEN);
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

    /** 종가는 두지 않는다 — 예약은 종가를 보지 않으므로 이 테스트에 필요 없다. */
    private void givenFineGrainedCoin() {
        jdbc.update("""
                INSERT INTO instruments (symbol, name, category, asset_type, currency, price_scale, summary)
                VALUES (?, '체결 검증용 코인', 'CRYPTO', 'CRYPTO', 'USD', 4,
                        '실제 시장 데이터가 아닌 주문 검증용 종목')
                """, COIN_SYMBOL);
    }

    private void givenPosition(String symbol, int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, ?::numeric)
                """, ACCOUNT_ID, instrumentId(symbol), quantity, totalCost);
    }

    /** 미체결 예약 매도. 잠긴 수량이 시장가 매도에 중복 사용되지 않는지 보려면 이 행이 있어야 한다. */
    private void givenOpenSellOrder(String symbol, int quantity) {
        jdbc.update("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'SELL', 11000, ?, 'KRW', NULL, ?, 'OPEN', now(), ?, now())
                """, ACCOUNT_ID, instrumentId(symbol), quantity, quantity, FIRST_TICK);
    }

    private void assertKrwCash(String expected) {
        assertCash("krw_cash", expected);
    }

    /** 저장된 현금을 값으로 비교한다. NUMERIC 의 표현 자릿수가 아니라 금액이 같은지를 본다. */
    private void assertCash(String column, String expected) {
        BigDecimal actual = jdbc.queryForObject(
                "SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column);
    }

    private void assertPosition(String symbol, int quantity, String totalCost) {
        assertEquals(quantity, jdbc.queryForObject(
                "SELECT quantity FROM positions WHERE account_id = ? AND instrument_id = ?",
                Integer.class, ACCOUNT_ID, instrumentId(symbol)), "보유 수량");
        BigDecimal actual = jdbc.queryForObject(
                "SELECT total_cost FROM positions WHERE account_id = ? AND instrument_id = ?",
                BigDecimal.class, ACCOUNT_ID, instrumentId(symbol));
        assertEquals(0, new BigDecimal(totalCost).compareTo(actual), "취득 총액");
    }

    private void assertNoPosition(String symbol) {
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM positions WHERE account_id = ? AND instrument_id = ?
                """, Integer.class, ACCOUNT_ID, instrumentId(symbol)), "포지션 행");
    }

    private void assertOpenOrderCount(int expected) {
        assertEquals(expected, jdbc.queryForObject("""
                SELECT count(*) FROM orders WHERE account_id = ? AND status = 'OPEN'
                """, Integer.class, ACCOUNT_ID), "미체결 예약 행");
    }

    private void assertExecutionCount(int expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT count(*) FROM executions WHERE account_id = ?", Integer.class, ACCOUNT_ID),
                "체결 행");
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
