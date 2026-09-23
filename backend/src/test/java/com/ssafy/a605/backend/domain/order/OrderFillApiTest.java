package com.ssafy.a605.backend.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 틱이 공개될 때 미체결 예약이 <b>실제로 체결되고 회계가 맞는가</b>를 본다. 판정 지점을 직접
 * 부르지 않고 폴러가 커밋한 결과만 성공 근거로 삼는다 — {@code AccountValuationRealtimeApiTest}
 * 와 같은 방식이다.
 *
 * <p>회계 테스트라 저장된 값을 직접 확인한다. 이 영역의 실패는 예외 없이 조용히 오며, 그 모습이
 * 곧 <b>잠금이 풀리지 않아 영구히 묶인 현금</b>이나 <b>차액이 반환되지 않은 매수</b>다.
 *
 * <p>편성은 {@code R__replay_fixture.sql} 의 검증용 시즌을 쓴다. 1일차 첫 틱 종가가 10,100,
 * 둘째가 9,800, 셋째가 10,300 이라 <b>지정가를 사이에 두고 종가가 오르내리는</b> 시나리오를
 * 픽스처만으로 만들 수 있다. 검증 종목 B 는 둘째 틱에 종가가 없어 휴장 판정을 그대로 쓴다.
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
class OrderFillApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 940L;
    private static final long OTHER_ACCOUNT_ID = 941L;

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

    private String admin;
    private User owner;
    private String ownerSession;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());
        owner = fixture.user("예약주인");
        ownerSession = sessions.createSessionFor(owner);
    }

    @AfterEach
    void tearDown() {
        // 다음 테스트가 정지 상태에서 시작하도록 폴러를 먼저 멈춘다.
        clock.set(ReplayFixture.START);
        // 시계를 되돌려도 진행 중인 한 번은 끝까지 간다. 폐장 정산이 그 안에서 행을 다시
        // 넣으면 아래의 삭제가 FK 로 막히므로, 가라앉기를 먼저 기다린다.
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());
        // 폐장을 넘기는 테스트가 있어 폐장 스냅샷이 남는다. 그 행이 계좌와 체결을 참조하므로
        // 먼저 지운다 — 순서를 바꾸면 FK 때문에 계좌 삭제가 실패한다.
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM orders WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("예약 매수_종가가 지정가 이하면_종가로 체결되고 차액이 반환된다")
    void limitBuy_fillsAtCloseAndReturnsDifference() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "1030000", "0");
        // 지정가 10,300 x 100 = 1,030,000 을 잠갔다. 첫 틱 종가는 10,100 이라 조건을 충족한다.
        long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 100, "10300", "1030000");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        awaitFills(1);

        // 체결가는 지정가가 아니라 그 틱의 종가다 (기획서 6.4.2). 10,100 x 100 = 1,010,000.
        Map<String, Object> execution = execution(orderId);
        assertEquals(0, new BigDecimal("10100").compareTo((BigDecimal) execution.get("executed_price")),
                "체결가");
        assertEquals(0, new BigDecimal("1010000").compareTo((BigDecimal) execution.get("amount")), "금액");
        assertEquals("LIMIT", execution.get("order_type"));
        // 매수에는 수수료가 없다 (ord.md §수수료).
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) execution.get("applied_fee_rate")), "요율");
        assertNull(execution.get("realized_pnl"), "매수의 실현손익");
        assertEquals(1L, ((Number) execution.get("tick_seq")).longValue(), "틱");

        // 잠금 1,030,000 이 전액 풀리고 실제 체결 1,010,000 만 나갔다 — 차액 20,000 이 반환된
        // 모습이 이 두 값이다 (기획서 6.4.3).
        assertCash("krw_cash", "43990000");
        assertCash("locked_krw", "0");
        assertPosition("REPLAY_TEST_A", 100, "1010000");
        assertOrderStatus(orderId, "FILLED");
    }

    @Test
    @DisplayName("예약 매수_종가가 지정가보다 높으면_체결되지 않고 잠금이 유지된다")
    void limitBuy_aboveLimit_doesNotFill() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "9000", "0");
        // 첫 틱 종가 10,100 > 지정가 9,000. 매수는 종가가 지정가 이하일 때만 체결된다.
        long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "9000", "9000");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        ReplayFixture.awaitQuiet(this::fillCount);

        assertEquals(0, fillCount(), "체결 행");
        assertCash("locked_krw", "9000");
        assertOrderStatus(orderId, "OPEN");
    }

    @Test
    @DisplayName("예약 매수_다음 틱에 종가가 내려오면_그 틱에 체결된다")
    void limitBuy_fillsOnLaterTick() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "9900", "0");
        // 첫 틱 10,100 은 조건 미충족, 둘째 틱 9,800 이 충족한다.
        long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "9900", "9900");

        resumeAndReach(ReplayFixture.SECOND_TICK);
        awaitFills(1);

        Map<String, Object> execution = execution(orderId);
        assertEquals(0, new BigDecimal("9800").compareTo((BigDecimal) execution.get("executed_price")),
                "체결가");
        assertEquals(2L, ((Number) execution.get("tick_seq")).longValue(), "틱");
        assertCash("krw_cash", "44990200");
        assertCash("locked_krw", "0");
    }

    @Test
    @DisplayName("예약 매도_종가가 지정가 이상이면_수수료를 뗀 대금이 들어오고 실현손익이 남는다")
    void limitSell_fillsAndCreditsProceeds() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "0", "0");
        givenPosition(ACCOUNT_ID, "REPLAY_TEST_A", 10, "90000.0000");
        // 첫 틱 종가 10,100 >= 지정가 10,000 이라 체결된다.
        long orderId = givenOpenSellOrder("REPLAY_TEST_A", 4, "10000");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        awaitFills(1);

        // 금액 10,100 x 4 = 40,400. 수수료 0.15% = 60.60 → 원화 자릿수 0 으로 61.
        Map<String, Object> execution = execution(orderId);
        assertEquals(0, new BigDecimal("40400").compareTo((BigDecimal) execution.get("amount")), "금액");
        assertEquals(0, new BigDecimal("0.15").compareTo((BigDecimal) execution.get("applied_fee_rate")),
                "요율");
        assertEquals(0, new BigDecimal("61").compareTo((BigDecimal) execution.get("fee")), "수수료");
        // 원가는 비례 차감이다. 90,000 x 4 / 10 = 36,000. 손익 = (40,400 - 61) - 36,000 = 4,339.
        assertEquals(0, new BigDecimal("4339").compareTo((BigDecimal) execution.get("realized_pnl")),
                "실현손익");

        assertCash("krw_cash", "45040339");
        assertPosition("REPLAY_TEST_A", 6, "54000.0000");
        assertOrderStatus(orderId, "FILLED");
    }

    @Test
    @DisplayName("예약 매도_지정가가 종가보다 높으면_체결되지 않는다")
    void limitSell_belowLimit_doesNotFill() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "0", "0");
        givenPosition(ACCOUNT_ID, "REPLAY_TEST_A", 10, "90000.0000");
        // 첫 틱 종가 10,100 < 지정가 12,000. 매도는 종가가 지정가 이상일 때만 체결된다.
        long orderId = givenOpenSellOrder("REPLAY_TEST_A", 4, "12000");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        ReplayFixture.awaitQuiet(this::fillCount);

        assertEquals(0, fillCount(), "체결 행");
        assertPosition("REPLAY_TEST_A", 10, "90000.0000");
        assertOrderStatus(orderId, "OPEN");
    }

    @Test
    @DisplayName("예약 체결_지정가와 종가가 같으면_체결된다")
    void limitOrder_atExactClose_fills() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "10100", "0");
        // 부등호에 등호가 붙는다 — 사용자가 낸 조건이 "이 값이면 산다"다.
        long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10100", "10100");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        awaitFills(1);

        assertOrderStatus(orderId, "FILLED");
        assertCash("locked_krw", "0");
    }

    @Test
    @DisplayName("예약 체결_무료 타임에 체결되면_등록이 그 전이어도 수수료가 0 이다")
    void limitSell_filledDuringFeeFree_chargesNothing() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "0", "0");
        givenPosition(ACCOUNT_ID, "REPLAY_TEST_A", 10, "90000.0000");
        long orderId = givenOpenSellOrder("REPLAY_TEST_A", 4, "10000");

        // 무료 요율은 등록이 아니라 *체결* 시점 기준이다 (기획서 6.7.3). 이 예약은 첫 틱에서
        // 이미 조건을 충족하므로, 앵커를 무료 구간 시작으로 옮겨 첫 틱 공개 자체가 무료 구간
        // 안에서 일어나게 한다 — 진행시켜 12:00 을 넘기면 그 전에 체결돼 버린다.
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.FEE_FREE_START), ReplayFixture.START,
                BigDecimal.ZERO, null, 1, ReplayFixture.FEE_FREE_START);
        resumeAndReach(ReplayFixture.FEE_FREE_START + 60);
        awaitFills(1);

        Map<String, Object> execution = execution(orderId);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) execution.get("applied_fee_rate")),
                "요율");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) execution.get("fee")), "수수료");
        // 수수료가 없어 대금 전액이 들어온다. 손익 = 40,400 - 36,000 = 4,400.
        assertCash("krw_cash", "45040400");
        assertEquals(0, new BigDecimal("4400").compareTo((BigDecimal) execution.get("realized_pnl")),
                "실현손익");
    }

    @Test
    @DisplayName("예약 체결_그 틱에 휴장인 종목이면_직전 종가로 판정되지 않는다")
    void limitOrder_onMarketClosedInstrument_isNotJudged() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "10100", "0");

        // 첫 틱을 <b>이미 공개된 것으로</b> 두고 시작한다. 그래야 이 예약이 첫 틱 판정을 겪지
        // 않고 둘째 틱에서 처음 판정된다.
        //
        // 첫 틱을 실제로 진행시킨 뒤 예약을 넣는 방식은 성립하지 않는다 — 재생은 커밋 후
        // 발행이라 last_published_tick_seq 가 보이는 시점에 체결 스윕은 아직 돌지 않았고,
        // 그 틈에 넣은 예약을 첫 틱 스윕이 잡아 체결한다.
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, 1L, 1, ReplayFixture.TRADING_OPEN);
        long orderId = givenOpenBuyOrder("REPLAY_TEST_B", 1, "10100", "10100");

        // 검증 종목 B 는 둘째 틱(2020-01-03)에 종가가 없다. 이월된 첫 틱 종가 10,100 으로
        // 판정하면 이 예약이 여기서 체결된다 — 직전 종가 유지는 체결 사유가 아니다 (6.4.5).
        resumeAndReach(ReplayFixture.SECOND_TICK);
        awaitPublishedTick(2);
        ReplayFixture.awaitQuiet(this::fillCount);

        assertEquals(0, fillCount(), "체결 행");
        assertOrderStatus(orderId, "OPEN");
        assertCash("locked_krw", "10100");
    }

    @Test
    @DisplayName("예약 체결_폐장 취소가 밀린 어제 예약이면_다음 일차 틱에 체결되지 않는다")
    void yesterdaysOrder_isNotFilledToday() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "0", "0");
        givenPosition(ACCOUNT_ID, "REPLAY_TEST_A", 10, "90000.0000");

        // 1일차를 발표까지 끝내 일차를 전환시킨다. 테스트 모드는 야간 대기를 생략한다.
        resumeAndReach(ReplayFixture.ANNOUNCE);
        awaitSeasonDay(2);

        // 일차가 넘어간 뒤에 어제 예약을 심는다. 정상 경로라면 폐장 자동 취소가 이미 닫았을
        // 행이고, 이것은 그 취소가 어떤 이유로든 밀린 상태를 흉내 낸다 — 리스너가 예외를
        // 삼키므로 실제로 일어날 수 있다.
        //
        // 2일차 첫 틱 종가는 10,500 이라 지정가 10,400 매도는 조건을 충족한다. 그래도
        // 체결되면 안 된다. 예약의 유효기간은 당일뿐이다 (기획서 6.2.4).
        long stale = givenOpenSellOrder("REPLAY_TEST_A", 4, "10400");

        // 새 앵커 기준으로 2일차 첫 틱(5번, 오프셋 31)까지 옮긴다.
        reach(ReplayFixture.FIRST_TICK);
        awaitPublishedTick(5);
        ReplayFixture.awaitQuiet(this::fillCount);

        assertEquals(0, fillCount(), "체결 행");
        assertOrderStatus(stale, "OPEN");
        assertPosition("REPLAY_TEST_A", 10, "90000.0000");
    }

    @Test
    @DisplayName("예약 체결_같은 종목에 예약이 둘이면_각각 독립적으로 체결된다")
    void limitOrders_onSameInstrument_fillIndependently() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "31000", "0");
        long cheap = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10100", "10100");
        long dear = givenOpenBuyOrder("REPLAY_TEST_A", 2, "10450", "20900");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        awaitFills(2);

        assertOrderStatus(cheap, "FILLED");
        assertOrderStatus(dear, "FILLED");
        // 둘 다 종가 10,100 으로 체결된다. 잠금 31,000 이 풀리고 30,300 이 나갔다.
        assertCash("krw_cash", "44969700");
        assertCash("locked_krw", "0");
        assertPosition("REPLAY_TEST_A", 3, "30300");
    }

    @Test
    @DisplayName("예약 체결_다른 사람의 예약이 함께 있으면_각자 자기 계좌만 움직인다")
    void fills_areScopedToTheirOwnAccount() throws Exception {
        User other = fixture.user("다른예약주인");
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "10100", "0");
        givenAccount(OTHER_ACCOUNT_ID, other.getId(), "45000000", "0", "0", "0");
        long mine = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10100", "10100");

        resumeAndReach(ReplayFixture.FIRST_TICK);
        awaitFills(1);

        assertOrderStatus(mine, "FILLED");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM executions WHERE account_id = ?", Integer.class,
                OTHER_ACCOUNT_ID), "남의 체결 행");
        assertEquals(0, new BigDecimal("45000000").compareTo(jdbc.queryForObject(
                "SELECT krw_cash FROM accounts WHERE id = ?", BigDecimal.class, OTHER_ACCOUNT_ID)),
                "남의 원화 현금");
    }

    @Test
    @DisplayName("예약 체결_틱이 두 번 공개되면_이미 체결된 예약은 다시 체결되지 않는다")
    void filledOrder_isNotFilledAgain() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "10100", "0");
        long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "10100", "10100");

        resumeAndReach(ReplayFixture.SECOND_TICK);
        ReplayFixture.awaitQuiet(this::fillCount);

        // 첫 틱에 체결되고 둘째 틱(종가 9,800, 조건 여전히 충족)에는 다시 잡히지 않는다.
        // 두 번 잡히면 잠금이 두 번 풀려 현금이 늘어난다.
        assertEquals(1, fillCount(), "체결 행");
        assertOrderStatus(orderId, "FILLED");
        assertCash("krw_cash", "44989900");
        assertCash("locked_krw", "0");
    }

    @Test
    @DisplayName("예약 체결_지정가를 고치면_다음 틱은 새 조건으로 판정한다")
    void amendedOrder_isJudgedByNewLimit() throws Exception {
        givenAccount(ACCOUNT_ID, owner.getId(), "45000000", "0", "9000", "0");
        // 지정가 9,000 은 1일차 어떤 종가에도 못 미친다(최저 9,600). 그대로면 영원히 미체결이다.
        long orderId = givenOpenBuyOrder("REPLAY_TEST_A", 1, "9000", "9000");

        // 첫 틱을 이미 공개된 것으로 두고 거래 구간에 세운다. 그 상태에서 지정가를 올린다.
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ONE, 1L, 1, ReplayFixture.TRADING_OPEN);
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/orders/" + orderId)
                        .header(AUTH_TOKEN_HEADER, ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitPrice\":\"9900\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk());

        // 둘째 틱 종가는 9,800 이다. 고친 값(9,900)으로 판정되면 체결되고, 옛 값(9,000)이
        // 남아 있으면 체결되지 않는다 — 이 테스트가 그 연결을 확인한다.
        reach(ReplayFixture.SECOND_TICK);
        awaitFills(1);

        Map<String, Object> execution = execution(orderId);
        assertEquals(0, new BigDecimal("9800").compareTo((BigDecimal) execution.get("executed_price")),
                "체결가");
        assertOrderStatus(orderId, "FILLED");
        // 잠금 9,900 이 풀리고 실제 9,800 이 나갔다 — 차액 100 이 반환된 모습이다.
        assertCash("krw_cash", "44990200");
        assertCash("locked_krw", "0");
    }

    /** 배속을 올리고 그 게임 오프셋에 도달한 실제 시각으로 시계를 옮긴다. */
    private void resumeAndReach(int offset) throws Exception {
        speed(1);
        reach(offset);
    }

    /** 이미 진행 중인 재생을 그 게임 오프셋까지 옮긴다. */
    private void reach(int offset) {
        clock.set(fixture.realTimeAt(offset));
    }

    /**
     * 그 틱이 공개될 때까지 기다린다. "체결되지 않아야 한다"를 판정하기 전에 쓴다 — 틱이 아직
     * 공개되지 않은 것을 미체결로 오인하면 판정 로직이 없어도 통과한다.
     *
     * <p><b>공개는 스윕 완료가 아니다.</b> 재생이 커밋 후 발행이라 이 값이 보이는 시점에
     * 체결 리스너는 아직 돌지 않았을 수 있다. 그래서 이것만으로는 "그 틱의 판정이 끝났다"를
     * 말할 수 없고, 뒤에 {@link ReplayFixture#awaitQuiet} 로 가라앉기를 함께 기다린다.
     */
    private void awaitPublishedTick(long tickSeq) {
        ReplayFixture.awaitUntil(() -> tickSeq == publishedTick(),
                () -> "틱 %d 공개를 기다렸으나 %d 이다".formatted(tickSeq, publishedTick()));
    }

    /** 일차 전환을 기다린다. 전환은 발표 경계를 넘긴 뒤 폴러가 앵커를 다시 잡는 단계다. */
    private void awaitSeasonDay(int day) {
        ReplayFixture.awaitUntil(() -> day == seasonDay(),
                () -> "%d 일차 전환을 기다렸으나 %d 일차다".formatted(day, seasonDay()));
    }

    private int seasonDay() {
        return ((Number) fixture.row().get("anchor_season_day")).intValue();
    }

    /** 최초 공개 전에는 컬럼이 {@code null} 이다. 0 으로 읽어 "아직 아무 틱도 없다"로 다룬다. */
    private long publishedTick() {
        Number published = (Number) fixture.row().get("last_published_tick_seq");
        return published == null ? 0L : published.longValue();
    }

    private void speed(int speed) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.patch(REPLAY_URI)
                        .header(AUTH_TOKEN_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":" + speed + "}"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    /** 폴러는 실제 타이머로 도니 체결이 그 자리에서 보이지 않는다. 행이 생길 때까지만 기다린다. */
    private void awaitFills(int expected) {
        ReplayFixture.awaitUntil(() -> fillCount() >= expected,
                () -> "체결 %d 건을 기다렸으나 %d 건이다".formatted(expected, fillCount()));
    }

    private int fillCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM executions WHERE account_id = ?", Integer.class, ACCOUNT_ID);
    }

    private Map<String, Object> execution(long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM executions WHERE order_id = ?", orderId);
        assertEquals(1, rows.size(), "예약 %d 의 체결 행".formatted(orderId));
        return rows.getFirst();
    }

    private long givenOpenBuyOrder(String symbol, int quantity, String limitPrice,
                                   String lockedAmount) {
        return givenOpenOrder(symbol, "BUY", quantity, limitPrice, lockedAmount, null);
    }

    private long givenOpenSellOrder(String symbol, int quantity, String limitPrice) {
        return givenOpenOrder(symbol, "SELL", quantity, limitPrice, null, quantity);
    }

    /**
     * 예약 행을 직접 심는다. 등록 경로({@code POST /orders})는
     * {@code OrderPlacementApiTest} 가 담당하고, 여기서는 <b>이미 등록된 예약이 틱에 어떻게
     * 판정되는가</b>만 본다 — 등록까지 API 로 하면 등록 실패가 체결 실패로 보인다.
     */
    private long givenOpenOrder(String symbol, String side, int quantity, String limitPrice,
                                String lockedAmount, Integer lockedQuantity) {
        return jdbc.queryForObject("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), ?, 'KRW', CAST(? AS NUMERIC), ?, 'OPEN',
                        now() + interval '1 hour', 1, now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId(symbol), side, limitPrice, quantity,
                lockedAmount, lockedQuantity);
    }

    private void givenAccount(long accountId, long userId, String krwCash, String usdCash,
                              String lockedKrw, String lockedUsd) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID, krwCash, usdCash, lockedKrw, lockedUsd);
    }

    private void givenPosition(long accountId, String symbol, int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, CAST(? AS NUMERIC))
                """, accountId, instrumentId(symbol), quantity, totalCost);
    }

    /** 저장된 현금을 값으로 비교한다. NUMERIC 의 표현 자릿수가 아니라 금액이 같은지를 본다. */
    private void assertCash(String column, String expected) {
        BigDecimal actual = jdbc.queryForObject(
                "SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column);
    }

    private void assertPosition(String symbol, int quantity, String totalCost) {
        assertEquals(quantity, jdbc.queryForObject("""
                SELECT quantity FROM positions WHERE account_id = ? AND instrument_id = ?
                """, Integer.class, ACCOUNT_ID, instrumentId(symbol)), "보유 수량");
        BigDecimal actual = jdbc.queryForObject("""
                SELECT total_cost FROM positions WHERE account_id = ? AND instrument_id = ?
                """, BigDecimal.class, ACCOUNT_ID, instrumentId(symbol));
        assertEquals(0, new BigDecimal(totalCost).compareTo(actual), "취득 총액");
    }

    private void assertOrderStatus(long orderId, String expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId), "예약 상태");
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}
