package com.ssafy.a605.backend.domain.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.websocket.WsDestination;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 재생 진행이 <b>실제 STOMP 클라이언트에 도착하는가</b>를 본다. 고정 샘플을 보내는 기존 테스트
 * 지원 트리거는 여기서 쓰지 않는다 — 폴러가 DB 를 커밋한 결과만 성공 근거로 삼는다.
 *
 * <p>{@code ReplayApiTest} 와 설정을 같게 두어 같은 컨텍스트·컨테이너를 쓴다.
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
class ReplayRealtimeApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 프레임 하나를 기다리는 한도. */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** "오지 않아야 한다"를 판정하는 한도. 와야 할 것이 이미 온 뒤에만 쓴다. */
    private static final Duration ABSENCE_WINDOW = Duration.ofMillis(700);

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

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());

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
    }

    @Nested
    @DisplayName("가격 틱 발행")
    class PriceTicks {

        @Test
        @DisplayName("가격 발행_틱을 공개하면_봉투와 payload 의 공개 기준이 일치해 도착한다")
        void priceFrameMatchesPublishedTick() throws Exception {
            Subscriptions client = subscribeAll();
            resume(1);

            moveTo(ReplayFixture.FIRST_TICK);

            String frame = awaitType(client.tick(), "market.prices");
            assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo(1);
            assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo("2020-01-02");
            assertThat((Integer) JsonPath.read(frame, "$.replayEpoch")).isEqualTo(0);
            // 봉투의 공개 기준과 본문의 공개 기준이 같아야 한다.
            assertThat((Integer) JsonPath.read(frame, "$.payload.asOfTickSeq")).isEqualTo(1);
            assertThat((String) JsonPath.read(frame, "$.payload.asOfGameDate")).isEqualTo("2020-01-02");
            // 검증용 전체 가격 스냅샷이다 — 일부 종목만 담지 않는다.
            // 상장 예정인 검증 종목 D 는 아직 빠져 있어 세 종목이다.
            assertThat((List<?>) JsonPath.read(frame, "$.payload.prices")).hasSize(3);
            assertThat((String) JsonPath.read(frame, "$.payload.usdKrwRate")).startsWith("1200");
            // USD 종목은 환율로 환산한 원화가 함께 온다. KRW 종목은 환산 없이 같은 값이다.
            assertThat((String) JsonPath.read(frame, "$.payload.prices[2].closeKrw"))
                    .isNotEqualTo(JsonPath.read(frame, "$.payload.prices[2].close"));
            assertThat((String) JsonPath.read(frame, "$.payload.prices[0].closeKrw"))
                    .isEqualTo(JsonPath.read(frame, "$.payload.prices[0].close"));
        }

        @Test
        @DisplayName("가격 발행_상장일이 지난 IPO 종목_페이로드에 포함된다")
        void priceFrameIncludesListedIpoInstrument() throws Exception {
            Subscriptions client = subscribeAll();
            resume(1);

            moveTo(ReplayFixture.SECOND_TICK);

            // 검증 종목 D 는 2020-01-03 상장이다. REST 조회와 이 페이로드가 같은 서비스를 쓰므로
            // 상장 필터가 한쪽에만 적용되는 일이 생길 수 없다.
            assertThat(tickSeqOf(awaitType(client.tick(), "market.prices"))).isEqualTo(1);
            String frame = awaitType(client.tick(), "market.prices");
            assertThat((Integer) JsonPath.read(frame, "$.payload.asOfTickSeq")).isEqualTo(2);
            assertThat((List<?>) JsonPath.read(frame, "$.payload.prices")).hasSize(4);
            List<Map<String, Object>> ipo = JsonPath.read(frame,
                    "$.payload.prices[?(@.instrumentId == " + ipoInstrumentId() + ")]");
            assertThat(ipo).hasSize(1);
            // REST 응답과 같은 형태다. 한 타입을 공유하므로 필드 집합이 갈릴 수 없고, 그것을
            // 여기서 한 번 못 박아 둔다 (`mkt.md` §실시간).
            assertThat(ipo.getFirst()).containsOnlyKeys(
                    "instrumentId", "close", "closeKrw", "prevClose", "changeRate", "marketClosed",
                    "tradedAmountKrw");
        }

        @Test
        @DisplayName("가격 발행_여러 틱이 밀린 경우_순번 순서대로 도착한다")
        void backlogArrivesInOrder() throws Exception {
            Subscriptions client = subscribeAll();
            resume(1);
            moveTo(ReplayFixture.THIRD_TICK);

            assertThat(tickSeqOf(awaitType(client.tick(), "market.prices"))).isEqualTo(1);
            assertThat(tickSeqOf(awaitType(client.tick(), "market.prices"))).isEqualTo(2);
            assertThat(tickSeqOf(awaitType(client.tick(), "market.prices"))).isEqualTo(3);
        }

        @Test
        @DisplayName("가격 발행_정지 중 시계를 옮기면_가격 프레임이 오지 않는다")
        void nothingArrivesWhilePaused() throws Exception {
            Subscriptions client = subscribeAll();
            // 정상 수신을 한 번 확인한 뒤라야 미수신을 판정할 수 있다.
            resume(1);
            moveTo(ReplayFixture.FIRST_TICK);
            assertThat(tickSeqOf(awaitType(client.tick(), "market.prices"))).isEqualTo(1);
            pause();
            drain(client.tick());

            clock.advance(Duration.ofHours(3));

            assertThat(client.tick().poll(ABSENCE_WINDOW.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Nested
    @DisplayName("시계 알림")
    class ClockNotifications {

        @Test
        @DisplayName("시계 알림_배속을 바꾸면_Tick 에 속하지 않는 재조회 알림이 온다")
        void speedChangeNotifiesClock() throws Exception {
            Subscriptions client = subscribeAll();

            resume(10);

            String frame = awaitType(client.event(), "game.clock");
            // 시계 조작은 Tick 에 속하지 않으므로 봉투의 공개 기준은 비어 있다.
            assertThat(nullableOf(frame, "$.tickSeq")).isNull();
            assertThat(nullableOf(frame, "$.gameDate")).isNull();
            assertThat((Integer) JsonPath.read(frame, "$.replayEpoch")).isEqualTo(0);
            assertThat((String) JsonPath.read(frame, "$.payload.reason")).isNotBlank();
        }

        @Test
        @DisplayName("시계 알림_정지하면_시계 재조회 알림이 온다")
        void pauseNotifiesClock() throws Exception {
            Subscriptions client = subscribeAll();
            resume(10);
            awaitType(client.event(), "game.clock");
            drain(client.event());

            pause();

            assertThat(typeOf(awaitType(client.event(), "game.clock"))).isEqualTo("game.clock");
        }

        @Test
        @DisplayName("시계 알림_Phase 경계_같은 공개 기준의 시계 스냅샷을 싣는다")
        void phaseBoundaryCarriesClockSnapshot() throws Exception {
            Subscriptions client = subscribeAll();
            resume(1);
            // 재개하면 브리핑 경계가 먼저 진행된다.
            String briefing = awaitType(client.event(), "game.phase");
            assertThat((String) JsonPath.read(briefing, "$.payload.phase")).isEqualTo("BRIEFING");

            moveTo(ReplayFixture.TRADING_OPEN);

            String frame = awaitType(client.event(), "game.phase");
            assertThat((String) JsonPath.read(frame, "$.payload.phase")).isEqualTo("TRADING");
            assertThat(((Number) JsonPath.read(frame, "$.payload.seasonDay")).intValue()).isEqualTo(1);
            // 시계 조작이 아니라 Tick 리듬의 사건이므로 봉투가 아니라 payload 가 공개 기준을 싣는다.
            assertThat((Integer) JsonPath.read(frame, "$.replayEpoch")).isEqualTo(0);
        }

        @Test
        @DisplayName("시계 알림_무료 구간 플래그 변경_Phase 가 아니라 시계 알림으로 온다")
        void feeFreeFlagNotifiesClockNotPhase() throws Exception {
            Subscriptions client = subscribeAll();
            resume(1);
            moveTo(ReplayFixture.TRADING_OPEN);
            // 거래 시작 Phase 까지 소비하고 나면 다음 경계는 무료 시작뿐이다.
            awaitUntilPhase(client.event(), "TRADING");
            drain(client.event());

            moveTo(ReplayFixture.FEE_FREE_START);

            // 무료 구간은 별도 Phase 가 아니라 시계 플래그다. 다음 프레임을 그대로 단정하지 않는다 —
            // 이 destination 은 여러 도메인이 공유하고 히트맵은 매 틱 브로드캐스트된다.
            String frame = awaitType(client.event(), "game.clock");
            assertThat((String) JsonPath.read(frame, "$.payload.reason")).isNotBlank();
        }
    }

    @Nested
    @DisplayName("실제 타이머 관통")
    class RealTimerPassThrough {

        /**
         * 시계를 옮기지 않고 진짜로 기다린다. 폴러 스케줄러 배선과 커밋 후 발행 경로가 실제
         * 타이머에서 동작하는지 보는 짧은 시나리오 하나다.
         */
        @Test
        @DisplayName("관통_실제 타이머 폴러가 진행하면_그 틱이 STOMP 로 도착한다")
        void realTimerAdvancesAndPublishes() throws Exception {
            Subscriptions client = subscribeAll();
            // 브리핑 300초 + 첫 틱 32초를 100배속으로 흘려보내면 실제 4초 남짓이다.
            resume(100);
            clock.followRealTime();

            String frame = awaitType(client.tick(), "market.prices");

            assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo(1);
        }
    }

    /** 픽스처가 심은 상장 예정 검증 종목. 상장 필터가 응답에서 종목을 넣고 빼는 것을 본다. */
    private long ipoInstrumentId() {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = 'REPLAY_TEST_D'", Long.class);
    }

    /** 현재 앵커·배속 기준으로 그 게임 오프셋에 도달한 실제 시각으로 시계를 옮긴다. */
    private void moveTo(int offset) {
        clock.set(fixture.realTimeAt(offset));
    }

    private void resume(int speed) throws Exception {
        change("{\"speed\":" + speed + "}");
    }

    private void pause() throws Exception {
        change("{\"speed\":0}");
    }

    private void change(String body) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(REPLAY_URI)
                        .header(AUTH_TOKEN_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    private Subscriptions subscribeAll() throws Exception {
        Frames frames = connect();
        Subscriptions client = new Subscriptions(subscribe(frames.session(), WsDestination.TICK.path()),
                subscribe(frames.session(), WsDestination.EVENT.path()));
        awaitReady(client);
        return client;
    }

    /**
     * SUBSCRIBE 를 보냈다고 브로커가 이미 등록했다는 뜻은 아니다. 정지 상태에서 무해한 조작을
     * 반복해 <b>실제로 한 프레임을 받아본 뒤</b>에야 이후의 수신·미수신을 판정한다.
     */
    private void awaitReady(Subscriptions client) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            // 이미 정지 상태라 진행은 없고 시계 알림만 나간다.
            pause();
            if (client.event().poll(200, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(100);
                client.event().clear();
                client.tick().clear();
                return;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
    }

    private Frames connect() throws Exception {
        Frames frames = new Frames();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(AUTH_TOKEN_HEADER, admin);
        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, frames)
                .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        openSessions.add(session);
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

    private static String await(BlockingQueue<String> sink) throws InterruptedException {
        String frame = sink.poll(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(frame).as("프레임이 %s 안에 도착하지 않았다", ARRIVAL_TIMEOUT).isNotNull();
        return frame;
    }

    /**
     * 관심 없는 종류가 섞여 오는 destination 에서 찾는 것 하나만 골라낸다.
     *
     * <p><b>{@code /topic/tick} 도 그런 통로다.</b> 계약상 네 종류가 흐르고
     * ({@code ws.md}) 그중 {@code news.published} 는 payload 가 객체가 아니라 배열이다.
     * 종류를 확인하지 않고 집으면 남의 프레임에 {@code $.payload.asOfTickSeq} 를 묻게 된다.
     */
    private static String awaitType(BlockingQueue<String> sink, String type) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            String frame = await(sink);
            if (type.equals(typeOf(frame))) {
                return frame;
            }
        }
        throw new AssertionError(type + " 프레임이 도착하지 않았다");
    }

    /** 그 Phase 를 싣고 온 프레임까지 소비한다. 앞선 경계의 프레임이 남아 있어도 흔들리지 않는다. */
    private static void awaitUntilPhase(BlockingQueue<String> sink, String phase) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            String frame = await(sink);
            if ("game.phase".equals(typeOf(frame)) && phase.equals(JsonPath.read(frame, "$.payload.phase"))) {
                return;
            }
        }
        throw new AssertionError(phase + " Phase 프레임이 도착하지 않았다");
    }

    private static void drain(BlockingQueue<String> sink) {
        sink.clear();
    }

    private static String typeOf(String frame) {
        return JsonPath.read(frame, "$.type");
    }

    private static int tickSeqOf(String frame) {
        return JsonPath.read(frame, "$.tickSeq");
    }

    private static Object nullableOf(String frame, String path) {
        try {
            return JsonPath.read(frame, path);
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    private record Subscriptions(BlockingQueue<String> tick, BlockingQueue<String> event) { }

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
