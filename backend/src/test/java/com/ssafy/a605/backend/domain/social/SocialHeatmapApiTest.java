package com.ssafy.a605.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.websocket.WsDestination;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 히트맵이 <b>실제 스윕 결과에서</b> 만들어져 REST 와 STOMP 로 나가는지 본다 ({@code soc.md}).
 *
 * <p>틱 이벤트를 직접 발행한다 — 폴러가 틱을 커밋한 뒤 같은 객체를 발행하므로 여기서는 그 뒤의
 * 일만 검증한다. 집계는 프로세스 상태라, 컨텍스트를 공유하는 다른 테스트가 남긴 집계가 등락의
 * 직전 값이 되지 않도록 계좌를 만들기 전에 빈 틱을 한 번 발행해 기준을 비운다.
 */
@Tag("container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
class SocialHeatmapApiTest {

    private static final String HEATMAP_URI = "/api/v1/social/heatmap";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200, 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;
    private static final LocalDate FIRST_DATE = LocalDate.of(2020, 1, 2);

    /** 픽스처 1일차 둘째 틱. 게임 거래일 2020-01-03, 환율 1210, 검증 종목 종가 9,800. */
    private static final long SECOND_TICK = 2L;
    private static final LocalDate SECOND_DATE = LocalDate.of(2020, 1, 3);

    private static final long ACCOUNT_A = 960L;
    private static final long ACCOUNT_B = 961L;
    private static final long ACCOUNT_C = 962L;

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
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<StompSession> openSessions = new ArrayList<>();
    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;

    private User ownerA;
    private User ownerB;
    private User ownerC;
    private String session;
    private long krxInstrument;
    private long nasdaqInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        ownerA = fixture.user("히트맵A");
        ownerB = fixture.user("히트맵B");
        ownerC = fixture.user("히트맵C");
        session = sessions.createSessionFor(ownerC);
        krxInstrument = instrumentId("REPLAY_TEST_A");
        nasdaqInstrument = instrumentId("REPLAY_TEST_C");
        // 다른 테스트가 남긴 집계를 직전 값으로 삼지 않도록 빈 집계로 덮는다.
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

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
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        openSessions.clear();
        heartbeatScheduler.destroy();
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("히트맵_세션이 없으면_401 을 받는다")
        void getHeatmap_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(HEATMAP_URI))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("집계")
    class Aggregation {

        @Test
        @DisplayName("히트맵_틱 하나가 지나면_네 버킷의 합이 전체 평가액과 같고 비중 합이 100 이다")
        void getHeatmap_afterTick_bucketsSumToTotal() throws Exception {
            givenThreeAccounts();
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

            mockMvc.perform(heatmap())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    // A 1억 + B 9천만+10주x10,100 + C 4천만+1만달러x1200+5주x10,100x1200
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("302701000"))
                    .andExpect(jsonPath("$.data.buckets", hasSize(4)))
                    .andExpect(jsonPath("$.data.buckets[0].category").value("NASDAQ"))
                    .andExpect(jsonPath("$.data.buckets[0].valuationKrw").value("60600000"))
                    .andExpect(jsonPath("$.data.buckets[0].weight").value(20.02))
                    .andExpect(jsonPath("$.data.buckets[1].category").value("KRX"))
                    .andExpect(jsonPath("$.data.buckets[1].valuationKrw").value("101000"))
                    .andExpect(jsonPath("$.data.buckets[1].weight").value(0.03))
                    // 보유가 없는 카테고리도 버킷으로 온다 — 화면이 매 틱 같은 모양이어야 한다.
                    .andExpect(jsonPath("$.data.buckets[2].category").value("CRYPTO"))
                    .andExpect(jsonPath("$.data.buckets[2].valuationKrw").value("0"))
                    // 원화·달러 현금을 합쳐 하나의 버킷이다.
                    .andExpect(jsonPath("$.data.buckets[3].category").value("CASH"))
                    .andExpect(jsonPath("$.data.buckets[3].valuationKrw").value("242000000"))
                    .andExpect(jsonPath("$.data.buckets[3].weight").value(79.95));
        }

        @Test
        @DisplayName("히트맵_다음 틱에 종목 값이 내려가면_그만큼 현금 비중이 올라간다")
        void getHeatmap_nextTick_carriesWeightDelta() throws Exception {
            givenThreeAccounts();
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

            events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

            mockMvc.perform(heatmap())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(SECOND_TICK))
                    .andExpect(jsonPath("$.data.buckets[0].weight").value(19.67))
                    .andExpect(jsonPath("$.data.buckets[0].weightDelta").value(-0.35))
                    .andExpect(jsonPath("$.data.buckets[3].weight").value(80.30))
                    .andExpect(jsonPath("$.data.buckets[3].weightDelta").value(0.35));
        }

        @Test
        @DisplayName("히트맵_개인 식별 정보와 참가자 수가_어떤 필드로도 나가지 않는다")
        void getHeatmap_carriesNoIdentifyingFields() throws Exception {
            givenThreeAccounts();
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

            String body = mockMvc.perform(heatmap()).andReturn().getResponse().getContentAsString();

            Map<String, Object> data = JsonPath.read(body, "$.data");
            assertThat(data).containsOnlyKeys("asOfTickSeq", "asOfGameDate", "totalValuationKrw", "buckets");
            Map<String, Object> bucket = JsonPath.read(body, "$.data.buckets[0]");
            assertThat(bucket).containsOnlyKeys("category", "valuationKrw", "weight", "weightDelta");
        }
    }

    @Nested
    @DisplayName("실시간")
    class Realtime {

        @Test
        @DisplayName("social.heatmap_매 틱 전체에 오고_봉투 tickSeq 와 payload asOfTickSeq 가 같다")
        void heatmap_isBroadcastPerTick() throws Exception {
            givenThreeAccounts();
            BlockingQueue<String> event = subscribeEvent();

            events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

            String frame = awaitType(event, "social.heatmap");
            assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo((int) SECOND_TICK);
            assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo(SECOND_DATE.toString());
            assertThat((Integer) JsonPath.read(frame, "$.payload.asOfTickSeq")).isEqualTo((int) SECOND_TICK);
            assertThat((List<?>) JsonPath.read(frame, "$.payload.buckets")).hasSize(4);
            assertThat((String) JsonPath.read(frame, "$.payload.buckets[3].category")).isEqualTo("CASH");
        }
    }

    /** A 현금만, B 원화 종목, C 달러 현금과 달러 종목 — 네 버킷 중 셋이 채워진다. */
    private void givenThreeAccounts() {
        givenAccount(ACCOUNT_A, "100000000", "0");
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, krxInstrument, 10);
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, nasdaqInstrument, 5);
    }

    private MockHttpServletRequestBuilder heatmap() {
        return get(HEATMAP_URI).header(AUTH_TOKEN_HEADER, session);
    }

    /**
     * 이벤트 토픽을 구독하고, SUBSCRIBE 가 브로커에 등록되기를 <b>실제로 한 프레임을 받아</b>
     * 확인한다. 확인에 쓴 틱의 프레임은 비우고 돌려준다.
     */
    private BlockingQueue<String> subscribeEvent() throws Exception {
        Frames frames = connect();
        BlockingQueue<String> sink = subscribe(frames.session(), WsDestination.EVENT.path());
        for (int attempt = 0; attempt < 50; attempt++) {
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
            if (sink.poll(200, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(100);
                sink.clear();
                return sink;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
    }

    private Frames connect() throws Exception {
        Frames frames = new Frames();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(AUTH_TOKEN_HEADER, session);
        StompSession stompSession = stompClient
                .connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, frames)
                .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        openSessions.add(stompSession);
        frames.connectedHeaders().get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return frames;
    }

    private static BlockingQueue<String> subscribe(StompSession session, String destination) {
        BlockingQueue<String> sink = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
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

    /** 관심 없는 종류가 섞여 오는 destination 에서 찾는 것 하나만 골라낸다. */
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

    private void givenAccount(long accountId, String krwCash, String usdCash) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, ownerOf(accountId).getId(), ReplayFixture.SEASON_ID, krwCash, usdCash);
    }

    private User ownerOf(long accountId) {
        return accountId == ACCOUNT_A ? ownerA : accountId == ACCOUNT_B ? ownerB : ownerC;
    }

    /** 취득 총액은 평가에 쓰이지 않지만 수량이 있으면 0 일 수 없다 (V14 CHECK). */
    private void givenPosition(long accountId, long instrumentId, int quantity) {
        jdbc.update("INSERT INTO positions (account_id, instrument_id, quantity, total_cost) VALUES (?, ?, ?, 1)",
                accountId, instrumentId, quantity);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }

    /** 연결 수준 사건만 모은다. MESSAGE 는 구독별 핸들러가 받는다. */
    private static final class Frames extends StompSessionHandlerAdapter {

        private final CompletableFuture<StompHeaders> connectedHeaders = new CompletableFuture<>();
        private StompSession session;

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
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

        StompSession session() {
            return this.session;
        }
    }
}
