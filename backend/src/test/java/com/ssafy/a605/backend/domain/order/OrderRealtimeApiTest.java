package com.ssafy.a605.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.websocket.WsDestination;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 예약이 체결되면 {@code order.filled} 가 <b>실제 STOMP 클라이언트에 도착하는가</b>를 본다
 * ({@code ord.md} §실시간). 발행 지점을 직접 부르지 않고 폴러가 커밋한 결과만 성공 근거로
 * 삼는다 — {@code AccountValuationRealtimeApiTest} 와 같은 방식이다.
 *
 * <p>회계는 {@code OrderFillApiTest} 가 본다. 여기서 보는 것은 <b>전달</b>이다: 봉투의
 * {@code tickSeq} 가 같은 틱의 {@code market.prices} 와 맞는가, payload 가 체결 내역 항목과
 * 같은 형태인가, 그리고 <b>남의 체결이 내 큐로 오지 않는가</b>.
 *
 * <p>payload 형태가 계약인 이유는 프론트가 이 메시지를 체결 내역 목록에 <b>그대로 이어
 * 붙이기</b> 때문이다. 형태가 갈리면 목록의 앞줄만 모양이 다른 화면이 된다.
 */
@Tag("container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class OrderRealtimeApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final String TYPE = "order.filled";
    private static final String EXPIRED = "order.expired";

    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** "오지 않아야 한다"를 판정하는 한도. 와야 할 것이 이미 온 뒤에만 쓴다. */
    private static final Duration ABSENCE_WINDOW = Duration.ofMillis(700);

    private static final long OWNER_ACCOUNT_ID = 950L;
    private static final long OTHER_ACCOUNT_ID = 951L;

    @LocalServerPort
    private int port;

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

    private final List<StompSession> openSessions = new ArrayList<>();
    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;
    private String admin;
    private User owner;
    private String ownerSession;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());
        owner = fixture.user("체결알림주인");
        ownerSession = sessions.createSessionFor(owner);

        heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.afterPropertiesSet();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        StringMessageConverter rawBody = new StringMessageConverter();
        rawBody.setContentTypeResolver(null);
        stompClient.setMessageConverter(rawBody);
        stompClient.setTaskScheduler(heartbeatScheduler);
    }

    @AfterEach
    void tearDown() {
        // 다음 테스트가 정지 상태에서 시작하도록 폴러를 먼저 멈춘다.
        clock.set(ReplayFixture.START);
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        openSessions.clear();
        heartbeatScheduler.destroy();
        // 시계를 되돌려도 진행 중인 한 번은 끝까지 간다. 폐장 정산이 그 안에서 행을 다시
        // 넣으면 아래의 삭제가 FK 로 막히므로, 가라앉기를 먼저 기다린다.
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());
        // 폐장을 넘기는 테스트가 있어 폐장 스냅샷이 남는다. 계좌와 체결을 참조하니 먼저 지운다.
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)",
                OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)",
                OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)",
                OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM orders WHERE account_id IN (?, ?)",
                OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)",
                OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("order.filled_예약이 체결되면_그 틱의 순번을 실은 체결 항목이 도착한다")
    void filledArrivesWithPublishedTick() throws Exception {
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "20600");
        // 지정가 10,300 x 2 = 20,600 을 잠갔다. 첫 틱 종가는 10,100 이라 조건을 충족한다.
        givenOpenBuyOrder(OWNER_ACCOUNT_ID, "REPLAY_TEST_A", 2, "10300", "20600");
        BlockingQueue<String> personal = subscribePersonal(ownerSession);

        resume(1);
        moveTo(ReplayFixture.FIRST_TICK);

        String frame = awaitType(personal, TYPE);
        // 봉투의 공개 기준이 같은 틱의 market.prices 와 같아야 한다 (_common.md §10).
        assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo(1);
        assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo("2020-01-02");

        // payload 는 GET /api/v1/executions 의 항목과 같은 형태다.
        assertThat((Integer) JsonPath.read(frame, "$.payload.executionId")).isPositive();
        assertThat((String) JsonPath.read(frame, "$.payload.orderType")).isEqualTo("LIMIT");
        assertThat((String) JsonPath.read(frame, "$.payload.side")).isEqualTo("BUY");
        assertThat((Integer) JsonPath.read(frame, "$.payload.quantity")).isEqualTo(2);
        // 체결가는 지정가가 아니라 그 틱의 종가다 (기획서 6.4.2).
        assertThat((String) JsonPath.read(frame, "$.payload.executedPrice")).isEqualTo("10100");
        assertThat((String) JsonPath.read(frame, "$.payload.currency")).isEqualTo("KRW");
        assertThat((String) JsonPath.read(frame, "$.payload.amount")).isEqualTo("20200");
        // 매수에는 수수료가 없고 실현손익도 없다 (ord.md §수수료).
        assertThat((Double) JsonPath.read(frame, "$.payload.appliedFeeRate")).isZero();
        assertThat((String) JsonPath.read(frame, "$.payload.fee")).isEqualTo("0");
        assertThat((String) JsonPath.read(frame, "$.payload.realizedPnl")).isNull();
        // 게임 시각과 실제 시각을 둘 다 싣는다 (기획서 7.4.1).
        assertThat((String) JsonPath.read(frame, "$.payload.gameDate")).isEqualTo("2020-01-02");
        assertThat((Integer) JsonPath.read(frame, "$.payload.tickSeq")).isEqualTo(1);
        assertThat((String) JsonPath.read(frame, "$.payload.executedAt")).endsWith("Z");
    }

    @Test
    @DisplayName("order.filled_남의 예약이 체결되면_내 큐로 오지 않는다")
    void othersFillDoesNotLeak() throws Exception {
        User other = fixture.user("남의체결주인");
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "0");
        givenAccount(OTHER_ACCOUNT_ID, other.getId(), "20600");
        givenOpenBuyOrder(OTHER_ACCOUNT_ID, "REPLAY_TEST_A", 2, "10300", "20600");
        BlockingQueue<String> ownerQueue = subscribePersonal(ownerSession);
        BlockingQueue<String> otherQueue = subscribePersonal(sessions.createSessionFor(other));

        resume(1);
        moveTo(ReplayFixture.FIRST_TICK);

        // 주인에게는 온다. 그것이 온 뒤에야 "나에게는 오지 않았다"를 판정할 수 있다.
        assertThat((Integer) JsonPath.read(awaitType(otherQueue, TYPE), "$.payload.tickSeq"))
                .isEqualTo(1);
        assertNoneOfType(ownerQueue, TYPE);
    }

    @Test
    @DisplayName("order.expired_폐장에 예약이 사라지면_틱 없는 통보가 한 통 도착한다")
    void expiredArrivesWithoutTick() throws Exception {
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "27000");
        // 1일차 종가는 10,100 · 9,800 · 10,300 · 9,600 이다. 지정가 9,000 이면 그날 체결되지
        // 않아 폐장까지 살아남는다. 두 건을 걸어 한 통에 모여 오는지도 함께 본다.
        givenOpenBuyOrder(OWNER_ACCOUNT_ID, "REPLAY_TEST_A", 2, "9000", "18000");
        givenOpenBuyOrder(OWNER_ACCOUNT_ID, "REPLAY_TEST_A", 1, "9000", "9000");
        BlockingQueue<String> personal = subscribePersonal(ownerSession);

        resume(1);
        moveTo(ReplayFixture.CLOSE);

        String frame = awaitType(personal, EXPIRED);
        // 폐장은 틱 경계와 일치하지 않아 어떤 틱에도 속하지 않는다 (ord.md §실시간).
        assertThat((Object) JsonPath.read(frame, "$.tickSeq")).isNull();
        assertThat((Object) JsonPath.read(frame, "$.gameDate")).isNull();
        // 봉투에 틱이 없으므로 시점은 payload 가 담는다.
        assertThat((String) JsonPath.read(frame, "$.payload.occurredAt")).endsWith("Z");

        // 한 순간에 일어난 하나의 사건이라 여러 건이 한 통으로 온다 — 체결이 건마다 한 통인
        // 것과 갈리는 지점이다.
        assertThat((Integer) JsonPath.read(frame, "$.payload.orders.length()")).isEqualTo(2);
        // 항목은 미체결 목록과 같은 형태다. 프론트가 orderId 로 짝지어 걷어낸다.
        assertThat((String) JsonPath.read(frame, "$.payload.orders[0].side")).isEqualTo("BUY");
        assertThat((String) JsonPath.read(frame, "$.payload.orders[0].limitPrice")).isEqualTo("9000");
        // 그 안의 lockedAmount 가 곧 반환된 잠금이다.
        assertThat((String) JsonPath.read(frame, "$.payload.orders[0].lockedAmount"))
                .isEqualTo("18000");
        assertThat((Integer) JsonPath.read(frame, "$.payload.orders[0].quantity")).isEqualTo(2);
    }

    @Test
    @DisplayName("order.expired_미체결이 없으면_통보가 오지 않는다")
    void noExpiredWithoutOpenOrders() throws Exception {
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "0");
        BlockingQueue<String> personal = subscribePersonal(ownerSession);

        resume(1);
        moveTo(ReplayFixture.CLOSE);

        // 폐장까지 갔으니 계좌 평가액은 온다. 그것이 온 뒤에야 부재를 판정할 수 있다.
        awaitType(personal, "account.valuation");
        assertNoneOfType(personal, EXPIRED);
    }

    @Test
    @DisplayName("order.expired_남의 예약이 사라지면_내 큐로 오지 않는다")
    void othersExpiryDoesNotLeak() throws Exception {
        User other = fixture.user("남의만료주인");
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "0");
        givenAccount(OTHER_ACCOUNT_ID, other.getId(), "9000");
        givenOpenBuyOrder(OTHER_ACCOUNT_ID, "REPLAY_TEST_A", 1, "9000", "9000");
        BlockingQueue<String> ownerQueue = subscribePersonal(ownerSession);
        BlockingQueue<String> otherQueue = subscribePersonal(sessions.createSessionFor(other));

        resume(1);
        moveTo(ReplayFixture.CLOSE);

        // 주인에게는 온다. 그것이 온 뒤에야 "나에게는 오지 않았다"를 판정할 수 있다.
        assertThat((Integer) JsonPath.read(awaitType(otherQueue, EXPIRED),
                "$.payload.orders.length()")).isEqualTo(1);
        assertNoneOfType(ownerQueue, EXPIRED);
    }

    /**
     * 그 종류의 프레임이 오지 않는 것을 확인한다. 개인 큐에는 {@code account.valuation} 이
     * 매 틱 오므로 "아무것도 오지 않는다"로는 판정할 수 없다 — 종류를 보고 걸러야 한다.
     *
     * <p><b>한 통도 받지 못했다면 부재를 판정할 수 없다.</b> 구독이 끊겼거나 틱이 아직 오지
     * 않은 것을 "새지 않았다"로 읽으면, 발행을 전체 브로드캐스트로 바꿔도 이 테스트가
     * 통과한다. 그래서 최소 한 통을 받았는지를 함께 요구한다.
     */
    private static void assertNoneOfType(BlockingQueue<String> sink, String type)
            throws InterruptedException {
        long deadline = System.nanoTime() + ABSENCE_WINDOW.toNanos();
        int received = 0;
        String frame;
        while (System.nanoTime() < deadline
                && (frame = sink.poll(ABSENCE_WINDOW.toMillis(), TimeUnit.MILLISECONDS)) != null) {
            received++;
            assertThat((String) JsonPath.read(frame, "$.type"))
                    .as("남의 체결이 내 큐로 샜다").isNotEqualTo(type);
        }
        assertThat(received).as("개인 큐가 한 통도 받지 못해 부재를 판정할 수 없다").isPositive();
    }

    /** 현재 앵커·배속 기준으로 그 게임 오프셋에 도달한 실제 시각으로 시계를 옮긴다. */
    private void moveTo(int offset) {
        clock.set(fixture.realTimeAt(offset));
    }

    private void resume(int speed) throws Exception {
        speed(speed);
    }

    private void speed(int speed) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.patch(REPLAY_URI)
                        .header(AUTH_TOKEN_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":" + speed + "}"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    /**
     * 그 사용자로 연결해 개인 큐를 구독한다. 전체 이벤트도 함께 구독하는 것은 <b>구독이 실제로
     * 등록됐는지 판정하기 위해서다</b> — SUBSCRIBE 를 보냈다고 브로커가 이미 등록했다는 뜻은
     * 아니고, 개인 큐에는 무해하게 불러낼 프레임이 없다.
     */
    private BlockingQueue<String> subscribePersonal(String session) throws Exception {
        Frames frames = new Frames();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(AUTH_TOKEN_HEADER, session);
        StompSession stomp = stompClient
                .connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, frames)
                .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        openSessions.add(stomp);
        frames.connectedHeaders().get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        BlockingQueue<String> personal = subscribe(stomp, WsDestination.PERSONAL);
        BlockingQueue<String> events = subscribe(stomp, WsDestination.EVENT.path());
        awaitReady(personal, events);
        return personal;
    }

    private static BlockingQueue<String> subscribe(StompSession stomp, String destination) {
        BlockingQueue<String> sink = new LinkedBlockingQueue<>();
        stomp.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((String) payload);
            }
        });
        return sink;
    }

    /**
     * 정지 상태에서 무해한 조작을 반복해 프레임을 한 번 받아본 뒤에야 이후의 수신·미수신을
     * 판정한다. 이미 정지 상태라 진행은 없고 시계 알림만 나간다.
     */
    private void awaitReady(BlockingQueue<String> personal, BlockingQueue<String> events)
            throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            speed(0);
            if (events.poll(200, TimeUnit.MILLISECONDS) != null) {
                events.clear();
                personal.clear();
                return;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
    }

    private static String awaitType(BlockingQueue<String> sink, String type) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            String frame = sink.poll(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(frame).as("%s 프레임이 %s 안에 도착하지 않았다", type, ARRIVAL_TIMEOUT).isNotNull();
            if (type.equals(JsonPath.read(frame, "$.type"))) {
                return frame;
            }
        }
        throw new AssertionError(type + " 프레임이 도착하지 않았다");
    }

    private void givenAccount(long accountId, long userId, String lockedKrw) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, CAST(? AS NUMERIC), 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID, lockedKrw);
    }

    /** 예약 행을 직접 심는다. 등록 경로는 {@code OrderPlacementApiTest} 가 담당한다. */
    private void givenOpenBuyOrder(long accountId, String symbol, int quantity, String limitPrice,
                                   String lockedAmount) {
        jdbc.update("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, locked_quantity, status, expires_at,
                                    created_tick_seq, created_at)
                VALUES (?, ?, 'BUY', CAST(? AS NUMERIC), ?, 'KRW', CAST(? AS NUMERIC), NULL, 'OPEN',
                        now() + interval '1 hour', 1, now())
                """, accountId, instrumentId(symbol), limitPrice, quantity, lockedAmount);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }

    /** 연결 프레임을 받아 두는 핸들러. 구독 전에 CONNECTED 를 기다리는 데 쓴다. */
    private static final class Frames extends StompSessionHandlerAdapter {

        private final CompletableFuture<StompHeaders> connectedHeaders = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.connectedHeaders.complete(headers);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                byte[] payload, Throwable exception) {
            this.connectedHeaders.completeExceptionally(exception);
        }

        CompletableFuture<StompHeaders> connectedHeaders() {
            return this.connectedHeaders;
        }
    }
}
