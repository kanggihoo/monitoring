package com.ssafy.a605.backend.domain.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.websocket.WsDestination;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * {@code news.published} 가 <b>실제 STOMP 클라이언트에 도착하는가</b>를 본다
 * ({@code nws.md} §실시간, {@code ws.md}).
 *
 * <p>이 계약은 문서에만 있고 발행하는 코드가 없었다 — REST 재조회가 화면을 채워 주어 발견이
 * 늦었다 (S15P21A605-131). 그래서 여기서 보는 것은 프레임의 도착 자체다.
 *
 * <p>좌표는 픽스처 틱과 V12 시드가 겹치는 곳이다. 1일차 틱 1·2·3 이 각각 2020-01-02 ·
 * 01-03 · 01-06 이고 시드의 1·2·3번 기사가 그 날짜에 하나씩 있다. 틱 4 (2022-03-31) 에는
 * 기사가 없어 "보내지 않는다"를 그대로 검증할 수 있다.
 *
 * <p>폴러는 정지 상태(배속 0)라 여기서 발행하는 것 외에는 틱 프레임이 오지 않는다.
 */
@Tag("container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
class NewsRealtimeApiTest {

    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);
    private static final String PUBLISHED = "news.published";

    /** 픽스처 1일차 첫 틱. 2020-01-02 — 시드 1번 기사 하나. 구독 준비 확인에 쓴다. */
    private static final long FIRST_TICK = 1L;
    private static final LocalDate FIRST_DATE = LocalDate.of(2020, 1, 2);
    private static final int FIRST_DATE_NEWS_ID = 1;

    /** 둘째 틱. 2020-01-03 — 시드 2번 기사 하나(US, major). */
    private static final long SECOND_TICK = 2L;
    private static final LocalDate SECOND_DATE = LocalDate.of(2020, 1, 3);
    private static final int SECOND_DATE_NEWS_ID = 2;

    /** 1일차 마지막 틱. 2022-03-31 — 이 날짜에는 기사가 없다. */
    private static final long EMPTY_TICK = 4L;
    private static final LocalDate EMPTY_DATE = LocalDate.of(2022, 3, 31);

    @LocalServerPort
    private int port;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationEventPublisher events;

    private final List<StompSession> openSessions = new ArrayList<>();
    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;
    private String session;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        session = sessions.createSessionFor(fixture.user("뉴스구독자"));

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
    }

    @Test
    @DisplayName("news.published_그 틱의 기사가_tick 토픽으로 온다")
    void newsPublished_arrivesOnTickTopic() throws Exception {
        BlockingQueue<String> tick = subscribeToTick();

        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        String frame = awaitType(tick, PUBLISHED, SECOND_TICK);
        assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo((int) SECOND_TICK);
        assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo(SECOND_DATE.toString());
        // payload 는 감싸지 않은 배열이다 — 프론트가 목록 앞에 그대로 붙인다.
        List<Integer> ids = JsonPath.read(frame, "$.payload[*].newsId");
        assertThat(ids).containsExactly(SECOND_DATE_NEWS_ID);
        assertThat((String) JsonPath.read(frame, "$.payload[0].publishedGameDate"))
                .isEqualTo(SECOND_DATE.toString());
    }

    /**
     * 브로드캐스트는 전원에게 같은 페이로드인데 관련도는 사람마다 다르다. 프론트가 자기
     * 보유·관심 목록으로 덮어쓴다 ({@code nws.md}).
     */
    @Test
    @DisplayName("news.published_페이로드가_피드 항목과 같은 모양이고 relevance 가 MARKET 이다")
    void newsPublished_carriesFeedShapeWithMarketRelevance() throws Exception {
        BlockingQueue<String> tick = subscribeToTick();

        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        String frame = awaitType(tick, PUBLISHED, SECOND_TICK);
        assertThat((String) JsonPath.read(frame, "$.payload[0].relevance")).isEqualTo("MARKET");
        assertThat((String) JsonPath.read(frame, "$.payload[0].channel")).isEqualTo("US");
        assertThat((String) JsonPath.read(frame, "$.payload[0].headline")).isNotBlank();
        assertThat((String) JsonPath.read(frame, "$.payload[0].body")).isNotBlank();
        assertThat((Boolean) JsonPath.read(frame, "$.payload[0].major")).isTrue();
        assertThat((List<?>) JsonPath.read(frame, "$.payload[0].tags")).isNotEmpty();
        assertThat((String) JsonPath.read(frame, "$.payload[0].publishedTime")).isNotBlank();
    }

    /** 하루 0건이 흔한데 매 틱 빈 배열을 밀면 아무것도 바꾸지 않는 프레임이 오간다 (기획서 10.1.2). */
    @Test
    @DisplayName("news.published_그 거래일에 기사가 없으면_아무 프레임도 오지 않는다")
    void newsPublished_whenNoNewsOnThatDate_sendsNothing() throws Exception {
        BlockingQueue<String> tick = subscribeToTick();

        events.publishEvent(new TickPublishedEvent(EMPTY_TICK, EMPTY_DATE));

        // "오지 않아야 한다"를 빈 큐로 판정하지 않는다 — /topic/tick 은 여러 도메인이 공유한다.
        assertThat(typesOn(tick)).doesNotContain(PUBLISHED);
    }

    /** 누적해서 다시 보내지 않는다. 놓친 기사는 피드 재조회가 채운다 (기획서 10.1.3). */
    @Test
    @DisplayName("news.published_연속된 두 틱_각 프레임에 그 날짜 기사만 담긴다")
    void newsPublished_onConsecutiveTicks_doesNotAccumulate() throws Exception {
        BlockingQueue<String> tick = subscribeToTick();

        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
        List<Integer> first = JsonPath.read(awaitType(tick, PUBLISHED, FIRST_TICK), "$.payload[*].newsId");
        assertThat(first).containsExactly(FIRST_DATE_NEWS_ID);

        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));
        List<Integer> second = JsonPath.read(awaitType(tick, PUBLISHED, SECOND_TICK), "$.payload[*].newsId");
        assertThat(second).containsExactly(SECOND_DATE_NEWS_ID).doesNotContain(FIRST_DATE_NEWS_ID);
    }

    /**
     * tick 토픽을 구독한다. SUBSCRIBE 를 보냈다고 브로커가 이미 등록했다는 뜻은 아니라서,
     * 첫 틱을 반복 발행해 <b>실제로 한 프레임을 받아본 뒤</b> 돌려준다 (S15P21A605-107).
     */
    private BlockingQueue<String> subscribeToTick() throws Exception {
        Frames frames = connect(session);
        BlockingQueue<String> sink = subscribe(frames.session(), WsDestination.TICK.path());
        for (int attempt = 0; attempt < 50; attempt++) {
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
            if (sink.poll(200, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(200);
                sink.clear();
                return sink;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
    }

    private Frames connect(String sessionId) throws Exception {
        Frames frames = new Frames();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(AUTH_TOKEN_HEADER, sessionId);
        StompSession stomp = stompClient
                .connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, frames)
                .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        openSessions.add(stomp);
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

    /**
     * 잠시 기다린 뒤 쌓인 프레임의 종류를 모은다. "오지 않아야 한다"를 <b>아무 프레임도 없음</b>
     * 으로 판정하지 않는다 — {@code /topic/tick} 은 가격·히트맵과 함께 쓰는 통로다.
     */
    private static List<String> typesOn(BlockingQueue<String> sink) throws InterruptedException {
        Thread.sleep(500);
        List<String> frames = new ArrayList<>();
        sink.drainTo(frames);
        return frames.stream().map(frame -> (String) JsonPath.read(frame, "$.type")).toList();
    }

    /** 그 종류·그 틱의 프레임까지 소비한다. 앞선 틱의 프레임이 남아 있어도 흔들리지 않는다. */
    private static String awaitType(BlockingQueue<String> sink, String type, Long tickSeq)
            throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            String frame = sink.poll(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(frame).as("%s 프레임이 %s 안에 도착하지 않았다", type, ARRIVAL_TIMEOUT).isNotNull();
            if (type.equals(JsonPath.read(frame, "$.type"))
                    && (tickSeq == null || tickSeq.intValue() == (Integer) JsonPath.read(frame, "$.tickSeq"))) {
                return frame;
            }
        }
        throw new AssertionError(type + " 프레임이 도착하지 않았다");
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
