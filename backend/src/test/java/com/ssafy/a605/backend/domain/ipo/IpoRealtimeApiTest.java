package com.ssafy.a605.backend.domain.ipo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.math.BigDecimal;
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
 * 공모 소식 다섯 종이 <b>실제 STOMP 클라이언트에 도착하는가</b>를 본다 ({@code ipo.md} §실시간).
 * 발행 지점을 직접 부르지 않고 폴러가 커밋한 결과만 성공 근거로 삼는다.
 *
 * <p>회계는 {@code IpoDrawApiTest} 와 {@code IpoListingApiTest} 가 본다. 여기서 보는 것은
 * <b>전달</b>이다: 어느 destination 으로 가는가, 봉투의 {@code tickSeq} 가 그 사건의 틱인가,
 * 그리고 <b>당락이 전체에 새지 않는가</b> — 전체 공지는 인원만 말하고 누가 당첨됐는지는
 * 개인 큐로만 간다 (기획서 8.4.3).
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
class IpoRealtimeApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    private static final long ACCOUNT_ID = 994L;
    private static final String DEPOSIT = "10000000";

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
    private String ownerSession;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());
        User owner = fixture.user("공모알림주인");
        ownerSession = sessions.createSessionFor(owner);
        givenAccount(owner.getId());

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
        clock.set(ReplayFixture.START);
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        openSessions.clear();
        heartbeatScheduler.destroy();
        ReplayFixture.awaitQuiet(() -> fixture.row().toString());
        jdbc.update("DELETE FROM ipo_subscriptions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM ipo_offerings WHERE season_id = ?", ReplayFixture.SEASON_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_ID);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Test
    @DisplayName("ipo.disclosed_공시 틱에_목록 항목과 같은 형태로 도착한다")
    void disclosedArrivesOnEvent() throws Exception {
        long offeringId = givenOffering(2, 3, 4);
        Sinks sinks = subscribeAll();

        run(ReplayFixture.SECOND_TICK);

        String frame = awaitType(sinks.events(), "ipo.disclosed");
        // 프론트는 이 payload 를 목록에 그대로 끼워 넣는다. 형태가 갈리면 앞줄만 다른 화면이 된다.
        assertThat(payloadLong(frame, "offeringId")).isEqualTo(offeringId);
        assertThat(JsonPath.<String>read(frame, "$.payload.status")).isEqualTo("OPEN");
        assertThat(JsonPath.<Integer>read(frame, "$.tickSeq")).isEqualTo(2);
        // 전체에게 가는 메시지라 보는 사람이 없다.
        assertThat(JsonPath.<Object>read(frame, "$.payload.mySubscription")).isNull();
    }

    @Test
    @DisplayName("ipo.competition_청약 창이 열린 동안_틱마다 도착한다")
    void competitionArrivesOnTick() throws Exception {
        long offeringId = givenOffering(1, 3, 4);
        givenSubscription(offeringId);
        Sinks sinks = subscribeAll();

        run(ReplayFixture.SECOND_TICK);

        // 가격과 같은 리듬으로 흐르므로 /topic/tick 이다 (ws.md).
        String frame = awaitType(sinks.ticks(), "ipo.competition");
        assertThat(payloadLong(frame, "offeringId")).isEqualTo(offeringId);
        assertThat(JsonPath.<Integer>read(frame, "$.payload.subscriberCount")).isEqualTo(1);
        // 청약자가 하나면 전원 당첨이라 확률 100% 다 (기획서 8.4.2 의 최소 1명).
        // 비율은 JSON number 라(_common.md §6) 자릿수가 아니라 값으로 비교한다 — 문자열로 보면
        // 파서가 100.00 을 100.0 으로 읽는 것에 단정이 걸린다.
        assertThat(new BigDecimal(JsonPath.<Object>read(frame, "$.payload.winProbability").toString()))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("ipo.announced_추첨 직후_인원만 전체에 알린다")
    void announcedCarriesCountOnly() throws Exception {
        long offeringId = givenOffering(1, 2, 4);
        givenSubscription(offeringId);
        Sinks sinks = subscribeAll();

        run(ReplayFixture.SECOND_TICK);

        String frame = awaitType(sinks.events(), "ipo.announced");
        assertThat(payloadLong(frame, "offeringId")).isEqualTo(offeringId);
        assertThat(JsonPath.<Integer>read(frame, "$.payload.winnerCount")).isEqualTo(1);
        // 명단이 실리면 남의 계좌 사정이 전원에게 나간다 (기획서 8.4.3).
        assertThat(frame).doesNotContain("won").doesNotContain("allocatedQuantity");
    }

    @Test
    @DisplayName("ipo.result_추첨 직후_당락은 그 사람에게만 간다")
    void resultArrivesOnPersonalQueue() throws Exception {
        long offeringId = givenOffering(1, 2, 4);
        givenSubscription(offeringId);
        Sinks sinks = subscribeAll();

        run(ReplayFixture.SECOND_TICK);

        String frame = awaitType(sinks.personal(), "ipo.result");
        // payload 가 조회 응답의 mySubscription.result 와 같은 모양이라, 프론트는 화면에 이미
        // 있는 청약 항목을 이 값으로 그대로 채운다 (ipo.md §실시간).
        assertThat(JsonPath.<Boolean>read(frame, "$.payload.won")).isTrue();
        assertThat(JsonPath.<Integer>read(frame, "$.payload.allocatedQuantity")).isEqualTo(833);
        assertThat(JsonPath.<Object>read(frame, "$.payload.drawnAt")).isNotNull();
    }

    @Test
    @DisplayName("ipo.listed_상장 틱에_바뀐 종목을 알린다")
    void listedArrivesOnEvent() throws Exception {
        long offeringId = givenOffering(1, 2, 3);
        givenSubscription(offeringId);
        Sinks sinks = subscribeAll();

        run(ReplayFixture.THIRD_TICK);

        String frame = awaitType(sinks.events(), "ipo.listed");
        assertThat(payloadLong(frame, "offeringId")).isEqualTo(offeringId);
        // 프론트는 이 신호로 종목 목록을 재조회한다 — 유니버스가 늘어나는 순간이다.
        assertThat(payloadLong(frame, "instrumentId")).isEqualTo(instrumentId());
        assertThat(JsonPath.<Integer>read(frame, "$.tickSeq")).isEqualTo(3);
    }

    /** 그 틱까지 재생시킨다. 구독이 붙은 뒤에 시작해야 프레임을 놓치지 않는다. */
    private void run(int offset) throws Exception {
        speed(1);
        clock.set(fixture.realTimeAt(offset));
    }

    private Sinks subscribeAll() throws Exception {
        Frames frames = new Frames();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(AUTH_TOKEN_HEADER, ownerSession);
        StompSession stomp = stompClient
                .connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, frames)
                .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        openSessions.add(stomp);
        frames.connectedHeaders().get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        BlockingQueue<String> personal = subscribe(stomp, WsDestination.PERSONAL);
        BlockingQueue<String> events = subscribe(stomp, WsDestination.EVENT.path());
        BlockingQueue<String> ticks = subscribe(stomp, WsDestination.TICK.path());
        awaitReady(personal, events, ticks);
        return new Sinks(personal, events, ticks);
    }

    /**
     * 구독이 실제로 붙었는지 확인한다. 배속 변경이 game.phase 를 EVENT 로 밀어내므로 그것이
     * 돌아오면 준비된 것이다 — 고정 대기를 쓰지 않는다.
     */
    private void awaitReady(BlockingQueue<String> personal, BlockingQueue<String> events,
                            BlockingQueue<String> ticks) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            speed(0);
            if (events.poll(200, TimeUnit.MILLISECONDS) != null) {
                events.clear();
                personal.clear();
                ticks.clear();
                return;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
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

    private static String awaitType(BlockingQueue<String> sink, String type) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            String frame = sink.poll(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(frame).as("%s 프레임이 %s 안에 도착하지 않았다", type, ARRIVAL_TIMEOUT).isNotNull();
            if (type.equals(JsonPath.read(frame, "$.type"))) {
                return frame;
            }
        }
        throw new AssertionError(type + " 프레임이 도착하지 않았다");
    }

    private static long payloadLong(String frame, String field) {
        return ((Number) JsonPath.read(frame, "$.payload." + field)).longValue();
    }

    private void speed(int speed) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.patch(REPLAY_URI)
                        .header(AUTH_TOKEN_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":" + speed + "}"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    private long givenOffering(int disclosureTick, int closeTick, int listingTick) {
        return jdbc.queryForObject("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                                           deposit_amount, disclosure_tick_seq, close_tick_seq,
                                           listing_tick_seq, allocation_rule)
                VALUES (?, ?, 'KRW', 12000, CAST(? AS NUMERIC), ?, ?, ?, ?)
                RETURNING id
                """, Long.class, ReplayFixture.SEASON_ID, instrumentId(), DEPOSIT,
                disclosureTick, closeTick, listingTick,
                "청약자 수의 30%를 무작위 추첨한다. 모든 청약자의 당첨 확률은 같다.");
    }

    private void givenSubscription(long offeringId) {
        jdbc.update("""
                INSERT INTO ipo_subscriptions (offering_id, account_id, deposit_amount, subscribed_at)
                VALUES (?, ?, CAST(? AS NUMERIC), now())
                """, offeringId, ACCOUNT_ID, DEPOSIT);
        jdbc.update("UPDATE accounts SET locked_krw = locked_krw + CAST(? AS NUMERIC) WHERE id = ?",
                DEPOSIT, ACCOUNT_ID);
    }

    private void givenAccount(long userId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, userId, ReplayFixture.SEASON_ID);
    }

    private long instrumentId() {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class,
                "REPLAY_TEST_D");
    }

    /** 세 destination 의 프레임 큐. 어느 통로로 왔는지가 계약이라 섞지 않는다. */
    private record Sinks(BlockingQueue<String> personal, BlockingQueue<String> events,
                         BlockingQueue<String> ticks) {
    }

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
            return connectedHeaders;
        }
    }
}
