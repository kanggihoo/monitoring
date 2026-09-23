package com.ssafy.a605.backend.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.websocket.WsDestination;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 랭킹 실시간 메시지 4종이 <b>실제 STOMP 클라이언트에 도착하는가</b>를 본다 ({@code rank.md} §실시간).
 *
 * <p>순위는 {@code RankingApiTest} 와 같은 방식으로 만든다 — 틱·경계 이벤트를 직접 발행해 실제
 * 스윕·계산기·리스너가 낸 프레임을 받는다. 폴러는 정지 상태라 여기서 발행하는 것 외에는 아무 프레임도
 * 오지 않는다.
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
class RankingRealtimeApiTest {

    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** 픽스처 1일차 첫 두 틱. 검증 종목 종가 10,100 → 9,800. */
    private static final long FIRST_TICK = 1L;
    private static final LocalDate FIRST_DATE = LocalDate.of(2020, 1, 2);
    private static final long SECOND_TICK = 2L;
    private static final LocalDate SECOND_DATE = LocalDate.of(2020, 1, 3);

    private static final long ACCOUNT_A = 940L;
    private static final long ACCOUNT_B = 941L;
    private static final long ACCOUNT_C = 942L;

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

    @Autowired
    private JdbcTemplate jdbc;

    private final List<StompSession> openSessions = new ArrayList<>();
    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;

    private User ownerA;
    private User ownerB;
    private User ownerC;
    private String sessionC;
    private long krwInstrument;
    private long usdInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        ownerA = fixture.user("랭킹실시간A");
        ownerB = fixture.user("랭킹실시간B");
        ownerC = fixture.user("랭킹실시간C");
        sessionC = sessions.createSessionFor(ownerC);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        usdInstrument = instrumentId("REPLAY_TEST_C");

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
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
    }

    @Test
    @DisplayName("ranking.me_틱마다 개인 큐로 오고_봉투 tickSeq 와 payload asOfTickSeq 가 같다")
    void rankingMe_arrivesPerTick_withMatchingTickSeq() throws Exception {
        givenThreeAccounts();
        Subscriptions client = subscribeAsC();

        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        String frame = awaitType(client.personal(), "ranking.me", SECOND_TICK);
        assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo((int) SECOND_TICK);
        assertThat((Integer) JsonPath.read(frame, "$.payload.asOfTickSeq")).isEqualTo((int) SECOND_TICK);
        assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo(SECOND_DATE.toString());
        // C 는 달러 자산으로 1위다. 다른 사람의 행은 개인 큐에 오지 않는다.
        assertThat((Integer) JsonPath.read(frame, "$.payload.seasonRank")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(frame, "$.payload.totalParticipants")).isEqualTo(3);
        assertThat((Boolean) JsonPath.read(frame, "$.payload.blackout")).isFalse();
    }

    @Test
    @DisplayName("ranking.me_암전 중에는_순위·등락이 null 이고 수익률은 그대로다")
    void rankingMe_duringBlackout_masksRanks() throws Exception {
        givenThreeAccounts();
        Subscriptions client = subscribeAsC();
        givenClockAt(FIRST_TICK, ReplayState.BLACKOUT_START);

        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        String frame = awaitType(client.personal(), "ranking.me", SECOND_TICK);
        assertThat((Boolean) JsonPath.read(frame, "$.payload.blackout")).isTrue();
        assertThat(nullableOf(frame, "$.payload.seasonRank")).isNull();
        assertThat(nullableOf(frame, "$.payload.dailyRankDelta")).isNull();
        assertThat(nullableOf(frame, "$.payload.seasonReturnRate")).isNotNull();
        assertThat(nullableOf(frame, "$.payload.totalValuationKrw")).isNotNull();
    }

    @Test
    @DisplayName("ranking.top10Changed_암전 중 TOP 10 구성이 바뀌면_빈 객체 payload 로 온다")
    void top10Changed_duringBlackout_carriesEmptyPayload() throws Exception {
        // 첫 틱에는 A·B 만 순위에 있다.
        givenAccount(ACCOUNT_A, "100000000", "0");
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, krwInstrument, 10);
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, usdInstrument, 5);
        Subscriptions client = subscribeAsC();
        givenClockAt(FIRST_TICK, ReplayState.BLACKOUT_START);
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_C);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_C);
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
        client.event().clear();

        // C 가 새로 들어오며 TOP 10 구성이 바뀐다.
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, usdInstrument, 5);
        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        String frame = awaitType(client.event(), "ranking.top10Changed", SECOND_TICK);
        assertThat((Map<?, ?>) JsonPath.read(frame, "$.payload")).isEmpty();
        assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo((int) SECOND_TICK);
    }

    @Test
    @DisplayName("ranking.top10Changed_암전이 아니면_구성이 바뀌어도 오지 않는다")
    void top10Changed_outsideBlackout_isNotSent() throws Exception {
        givenAccount(ACCOUNT_A, "100000000", "0");
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, usdInstrument, 5);
        Subscriptions client = subscribeAsC();
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
        awaitType(client.personal(), "ranking.me", FIRST_TICK);
        client.event().clear();

        givenAccount(ACCOUNT_B, "90000000", "0");
        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        awaitType(client.personal(), "ranking.me", SECOND_TICK);
        assertThat(typesOn(client.event())).doesNotContain("ranking.top10Changed");
    }

    @Test
    @DisplayName("ranking.finalized_폐장 스냅샷이 커밋되면_일차와 finalized 가 온다")
    void finalized_afterSettlementCommit_isBroadcast() throws Exception {
        givenThreeAccounts();
        Subscriptions client = subscribeAsC();

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        String frame = awaitType(client.event(), "ranking.finalized", null);
        assertThat((Integer) JsonPath.read(frame, "$.payload.seasonDay")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(frame, "$.payload.finalized")).isTrue();
        assertThat(nullableOf(frame, "$.tickSeq")).isNull();
    }

    /**
     * <b>이전 판은 이 프레임에 시상 조회와 같은 본문이 실리는 것을 단언했다.</b> {@code /topic/event}
     * 는 미인증도 구독하므로 그 본문이 수상자의 닉네임·아바타·수익률을 그대로 브로드캐스트했다
     * (ADR-0005 결정 5). 이제 신호만 나가고 상세는 {@code GET /rankings/awards} 재조회다.
     */
    @Test
    @DisplayName("ranking.awarded_정산 발표 경계가 커밋되면_상세 없는 신호만 온다")
    void awarded_atAnnouncement_carriesSignalOnly() throws Exception {
        givenThreeAccounts();
        Subscriptions client = subscribeAsC();
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        awaitType(client.event(), "ranking.finalized", null);

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.END));

        String frame = awaitType(client.event(), "ranking.awarded", null);
        assertThat((Map<?, ?>) JsonPath.read(frame, "$.payload")).isEmpty();
        // 닉네임은 문자열로도 남아 있지 않아야 한다 — 필드 이름만 바꾸는 실수를 이것이 잡는다.
        assertThat(frame).doesNotContain(ownerC.getNickname());
    }

    /** A 1억(0%), B 9천만+원화 10주, C 4천만+달러 1만+달러 종목 5주 — C 가 1위다. */
    private void givenThreeAccounts() {
        givenAccount(ACCOUNT_A, "100000000", "0");
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, krwInstrument, 10);
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, usdInstrument, 5);
    }

    /** 그 틱까지 공개되고 그 경계까지 처리된 상태로 시계를 고정한다. 배속 0 이라 폴러는 더 진행하지 않는다. */
    private void givenClockAt(long tickSeq, int boundaryOffset) {
        fixture.store(1, BigDecimal.valueOf(boundaryOffset), ReplayFixture.START, BigDecimal.ZERO, tickSeq, 1,
                boundaryOffset);
    }

    /**
     * C 로 접속해 개인 큐와 이벤트 토픽을 구독한다. SUBSCRIBE 를 보냈다고 브로커가 이미 등록했다는
     * 뜻은 아니라서, 첫 틱을 반복 발행해 <b>실제로 한 프레임을 받아본 뒤</b> 돌려준다.
     */
    private Subscriptions subscribeAsC() throws Exception {
        Frames frames = connect(sessionC);
        Subscriptions client = new Subscriptions(subscribe(frames.session(), WsDestination.PERSONAL),
                subscribe(frames.session(), WsDestination.EVENT.path()));
        for (int attempt = 0; attempt < 50; attempt++) {
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
            if (client.personal().poll(200, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(100);
                client.personal().clear();
                client.event().clear();
                return client;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
    }

    private Frames connect(String sessionId) throws Exception {
        Frames frames = new Frames();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(AUTH_TOKEN_HEADER, sessionId);
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

    /**
     * 잠시 기다린 뒤 그 destination 에 쌓인 프레임의 종류를 모은다.
     *
     * <p>"오지 않아야 한다"를 <b>아무 프레임도 없음</b>으로 판정하지 않는다 — {@code /topic/event} 는
     * 여러 도메인이 공유하고 히트맵({@code social.heatmap})은 매 틱 브로드캐스트되므로, 빈 큐를
     * 기대하면 남의 메시지 때문에 실패한다. 찾는 종류가 없다는 것만 본다.
     */
    private static List<String> typesOn(BlockingQueue<String> sink) throws InterruptedException {
        Thread.sleep(500);
        List<String> frames = new ArrayList<>();
        sink.drainTo(frames);
        return frames.stream().map(frame -> (String) JsonPath.read(frame, "$.type")).toList();
    }

    /** 그 종류(그리고 틱이 주어졌으면 그 틱)의 프레임까지 소비한다. 앞선 틱의 프레임이 남아 있어도 흔들리지 않는다. */
    private static String awaitType(BlockingQueue<String> sink, String type, Long tickSeq) throws InterruptedException {
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

    private static Object nullableOf(String frame, String path) {
        try {
            return JsonPath.read(frame, path);
        } catch (PathNotFoundException e) {
            return null;
        }
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

    private void givenPosition(long accountId, long instrumentId, int quantity) {
        jdbc.update("INSERT INTO positions (account_id, instrument_id, quantity, total_cost) VALUES (?, ?, ?, 1)",
                accountId, instrumentId, quantity);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }

    private record Subscriptions(BlockingQueue<String> personal, BlockingQueue<String> event) { }

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
