package com.ssafy.a605.backend.domain.account;

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
 * 틱이 공개되면 계좌 평가액이 <b>실제 STOMP 클라이언트에 도착하는가</b>를 본다. 발행 지점을
 * 직접 부르지 않고, 폴러가 DB 를 커밋한 결과만 성공 근거로 삼는다 —
 * {@code ReplayRealtimeApiTest} 와 같은 방식이다.
 *
 * <p>검증의 핵심 둘: <b>봉투의 {@code tickSeq} 가 그 틱의 것과 같은가</b>(같은 틱의 조각이
 * 한 번에 렌더링되려면 그래야 한다), 그리고 <b>남의 평가액이 오지 않는가</b>(개인 큐다).
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
class AccountValuationRealtimeApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final String TYPE = "account.valuation";

    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** "오지 않아야 한다"를 판정하는 한도. 와야 할 것이 이미 온 뒤에만 쓴다. */
    private static final Duration ABSENCE_WINDOW = Duration.ofMillis(700);

    private static final long OWNER_ACCOUNT_ID = 930L;
    private static final long OTHER_ACCOUNT_ID = 931L;

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
        owner = fixture.user("평가액주인");
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
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?)", OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        // 신규 상장 공지가 전원에게 간다 (noti.md 의 NEW_LISTING). 픽스처의 상장 예정 종목이
        // 2 번 틱에 상장하므로 틱을 공개하는 테스트는 어느 계좌든 알림을 받는다.
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", OWNER_ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("계좌 평가액_틱이 공개되면_그 틱의 순번을 실은 개인 메시지가 도착한다")
    void valuationArrivesWithPublishedTick() throws Exception {
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "45000000", "12000");
        givenPosition(OWNER_ACCOUNT_ID, instrumentId("REPLAY_TEST_A"), 10, "90000.0000");
        BlockingQueue<String> personal = subscribePersonal(ownerSession);

        resume(1);
        moveTo(ReplayFixture.FIRST_TICK);

        String frame = awaitType(personal, TYPE);
        // 봉투의 공개 기준이 같은 틱의 market.prices 와 같아야 한다.
        assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo(1);
        assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo("2020-01-02");
        // payload 는 GET /api/v1/accounts/me 와 같은 형태다.
        assertThat((Integer) JsonPath.read(frame, "$.payload.asOfTickSeq")).isEqualTo(1);
        assertThat((String) JsonPath.read(frame, "$.payload.usdKrwRate")).isEqualTo("1200.00");
        // 45,000,000 + 12,000 x 1200 + (검증 종목 A 종가 10,100 x 10) = 59,501,000
        assertThat((String) JsonPath.read(frame, "$.payload.holdingsValuationKrw")).isEqualTo("101000");
        assertThat((String) JsonPath.read(frame, "$.payload.totalValuationKrw")).isEqualTo("59501000");
    }

    @Test
    @DisplayName("계좌 평가액_계좌가 둘이면_각자 자기 것만 받는다")
    void eachAccountReceivesItsOwn() throws Exception {
        User other = fixture.user("다른주인");
        String otherSession = sessions.createSessionFor(other);
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "45000000", "0");
        givenAccount(OTHER_ACCOUNT_ID, other.getId(), "100000000", "0");
        BlockingQueue<String> ownerQueue = subscribePersonal(ownerSession);
        BlockingQueue<String> otherQueue = subscribePersonal(otherSession);

        resume(1);
        moveTo(ReplayFixture.FIRST_TICK);

        assertThat((String) JsonPath.read(awaitType(ownerQueue, TYPE), "$.payload.totalValuationKrw"))
                .isEqualTo("45000000");
        assertThat((String) JsonPath.read(awaitType(otherQueue, TYPE), "$.payload.totalValuationKrw"))
                .isEqualTo("100000000");
        // 각자 한 통씩이다 — 남의 것이 섞여 오면 두 번째 평가액이 잡힌다.
        assertNoMoreOfType(ownerQueue, TYPE);
    }

    @Test
    @DisplayName("계좌 평가액_시세 없는 종목을 가진 계좌가 있어도_나머지 계좌는 받는다")
    void oneUnvaluableAccountDoesNotStopTheRest() throws Exception {
        User other = fixture.user("시세없는주인");
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "45000000", "0");
        givenAccount(OTHER_ACCOUNT_ID, other.getId(), "45000000", "0");
        // 유니버스 시드 종목은 검증용 시즌에 종가가 적재되어 있지 않다.
        givenPosition(OTHER_ACCOUNT_ID, instrumentId("NQX"), 10, "90000.0000");
        BlockingQueue<String> personal = subscribePersonal(ownerSession);

        resume(1);
        moveTo(ReplayFixture.FIRST_TICK);

        assertThat((String) JsonPath.read(awaitType(personal, TYPE), "$.payload.totalValuationKrw"))
                .isEqualTo("45000000");
    }

    @Test
    @DisplayName("계좌 평가액_틱이 여러 번 공개되면_틱마다 한 통씩 온다")
    void oneMessagePerTick() throws Exception {
        givenAccount(OWNER_ACCOUNT_ID, owner.getId(), "45000000", "12000");
        BlockingQueue<String> personal = subscribePersonal(ownerSession);

        resume(1);
        moveTo(ReplayFixture.SECOND_TICK);

        // 첫 틱은 환율 1200, 둘째 틱은 1210 이다.
        assertThat((String) JsonPath.read(awaitType(personal, TYPE), "$.payload.usdKrwRate"))
                .isEqualTo("1200.00");
        assertThat((String) JsonPath.read(awaitType(personal, TYPE), "$.payload.usdKrwRate"))
                .isEqualTo("1210.00");
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
     *
     * <p>한 세션의 SUBSCRIBE 는 보낸 순서대로 처리되므로, 뒤에 보낸 이벤트 구독으로 프레임이
     * 한 번 도착하면 앞의 개인 큐 구독은 이미 등록된 것이다. 고정 대기를 쓰지 않는 이유다.
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
            pause();
            if (events.poll(200, TimeUnit.MILLISECONDS) != null) {
                events.clear();
                personal.clear();
                return;
            }
        }
        throw new AssertionError("구독이 준비되지 않았다");
    }

    private void pause() throws Exception {
        speed(0);
    }

    /**
     * 창이 닫힐 때까지 그 종류가 더 오지 않는지 본다.
     *
     * <p><b>큐가 비기를 기다리지 않고 종류로 거른다.</b> 한 틱에 개인 큐로 가는 것이 평가액
     * 하나가 아니다 — 같은 발행에서 {@code account.positions} 가 뒤따른다. 아무것도 오지
     * 않기를 기대하면 자기 몫의 그 한 통을 남의 것으로 읽는다.
     */
    private static void assertNoMoreOfType(BlockingQueue<String> sink, String type)
            throws InterruptedException {
        long deadline = System.nanoTime() + ABSENCE_WINDOW.toNanos();
        for (long left = ABSENCE_WINDOW.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            String frame = sink.poll(left, TimeUnit.NANOSECONDS);
            if (frame == null) {
                return;
            }
            assertThat((String) JsonPath.read(frame, "$.type"))
                    .as("남의 %s 가 섞여 왔다: %s", type, frame)
                    .isNotEqualTo(type);
        }
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

    private void givenAccount(long accountId, long userId, String krwCash, String usdCash) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID, krwCash, usdCash);
    }

    private void givenPosition(long accountId, long instrumentId, int quantity, String totalCost) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, ?, ?, CAST(? AS NUMERIC))
                """, accountId, instrumentId, quantity, totalCost);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }

    /** 연결 수준 사건만 모은다. MESSAGE 는 구독별 핸들러가 받는다. */
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
