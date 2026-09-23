package com.ssafy.a605.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import com.ssafy.a605.backend.domain.notification.entity.NotificationKind;
import com.ssafy.a605.backend.domain.notification.service.NotificationService;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.websocket.WsDestination;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 알림 센터의 HTTP 경계와, 알림 생성이 실제 STOMP 개인 큐에 도착하는지를 본다 ({@code noti.md}).
 *
 * <p>알림 행은 {@link NotificationService#create} 로 만든다 — 사건별 발행 연동은 별도 작업이고,
 * 여기서는 진입점 하나가 저장·전달을 계약대로 하는지만 본다.
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
class NotificationApiTest {

    private static final String NOTIFICATIONS_URI = "/api/v1/notifications";
    private static final String READ_URI = "/api/v1/notifications/read";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02. */
    private static final long FIRST_TICK = 1L;

    private static final long ACCOUNT_A = 950L;
    private static final long ACCOUNT_B = 951L;

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
    private NotificationService notifications;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<StompSession> openSessions = new ArrayList<>();
    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;

    private User ownerA;
    private User ownerB;
    private String sessionA;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        ownerA = fixture.user("알림A");
        ownerB = fixture.user("알림B");
        sessionA = sessions.createSessionFor(ownerA);

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
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", ACCOUNT_A, ACCOUNT_B);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_A, ACCOUNT_B);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("알림 목록_세션이 없으면_401 을 받는다")
        void getNotifications_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(NOTIFICATIONS_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("읽음 처리_헤더의 세션이 유효하지 않으면_401 을 받는다")
        void readAll_withInvalidSession_returnsUnauthorized() throws Exception {
            // 쿠키도 헤더도 없는 POST 는 CSRF 가 먼저 403 으로 거른다 (InquiryApiTest 와 같은 규칙).
            // 인증 자체의 거부를 보려면 헤더 경로로 들어가야 한다.
            mockMvc.perform(post(READ_URI).header(AUTH_TOKEN_HEADER, "not-a-session"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("알림 목록_시즌 계좌가 없으면_404 를 받는다")
        void getNotifications_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(list())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }
    }

    @Nested
    @DisplayName("목록")
    class Listing {

        @Test
        @DisplayName("알림 목록_최신순으로 오고_unreadCount 는 읽지 않은 행 수다")
        void getNotifications_latestFirst_withUnreadCount() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            givenAccount(ACCOUNT_B, ownerB);
            long first = create(ACCOUNT_A, NotificationKind.ORDER_FILLED, "첫째", 11L, FIRST_TICK);
            long second = create(ACCOUNT_A, NotificationKind.ORDER_EXPIRED, "둘째", 22L, null);
            long third = create(ACCOUNT_A, NotificationKind.AWARD, "셋째", null, null);
            jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ?", first);
            // 다른 계좌의 알림은 내 목록에도 미읽음 수에도 없다.
            create(ACCOUNT_B, NotificationKind.AWARD, "남의 것", null, null);

            mockMvc.perform(list())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unreadCount").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content", hasSize(3)))
                    .andExpect(jsonPath("$.data.content[0].notificationId").value(third))
                    .andExpect(jsonPath("$.data.content[0].kind").value("AWARD"))
                    .andExpect(jsonPath("$.data.content[0].referenceId").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[0].read").value(false))
                    .andExpect(jsonPath("$.data.content[1].notificationId").value(second))
                    // 폐장 자동 취소처럼 틱에 속하지 않는 사건은 tickSeq 와 gameDate 가 함께 null 이다.
                    .andExpect(jsonPath("$.data.content[1].tickSeq").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[1].gameDate").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[2].notificationId").value(first))
                    .andExpect(jsonPath("$.data.content[2].read").value(true))
                    .andExpect(jsonPath("$.data.content[2].tickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.content[2].gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.content[2].title").value("첫째"))
                    .andExpect(jsonPath("$.data.content[2].referenceId").value(11));
        }

        @Test
        @DisplayName("알림 목록_페이지를 나눠도_unreadCount 는 전체 기준이다")
        void getNotifications_paged_unreadCountIsGlobal() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            for (int i = 0; i < 3; i++) {
                create(ACCOUNT_A, NotificationKind.AWARD, "알림 " + i, null, null);
            }

            mockMvc.perform(list().param("page", "1").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unreadCount").value(3))
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.hasNext").value(false));
        }
    }

    @Nested
    @DisplayName("읽음 처리")
    class Reading {

        @Test
        @DisplayName("읽음 처리_204 이고_내 미읽음 전부가 읽음이 되며 다시 불러도 멱등이다")
        void readAll_marksEverythingRead_idempotently() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            givenAccount(ACCOUNT_B, ownerB);
            for (int i = 0; i < 3; i++) {
                create(ACCOUNT_A, NotificationKind.AWARD, "알림 " + i, null, null);
            }
            create(ACCOUNT_B, NotificationKind.AWARD, "남의 것", null, null);

            mockMvc.perform(read()).andExpect(status().isNoContent());

            mockMvc.perform(list())
                    .andExpect(jsonPath("$.data.unreadCount").value(0))
                    .andExpect(jsonPath("$.data.content[0].read").value(true))
                    .andExpect(jsonPath("$.data.content[1].read").value(true))
                    .andExpect(jsonPath("$.data.content[2].read").value(true));
            Object firstReadAt = jdbc.queryForObject(
                    "SELECT min(read_at) FROM notifications WHERE account_id = ?", Object.class, ACCOUNT_A);

            mockMvc.perform(read()).andExpect(status().isNoContent());

            // 이미 읽은 행의 시각은 그대로다.
            assertThat(jdbc.queryForObject("SELECT min(read_at) FROM notifications WHERE account_id = ?",
                    Object.class, ACCOUNT_A)).isEqualTo(firstReadAt);
            // 다른 계좌는 건드리지 않는다.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE account_id = ? AND read_at IS NULL",
                    Integer.class, ACCOUNT_B)).isEqualTo(1);
        }

        @Test
        @DisplayName("읽음 처리_시즌 계좌가 없으면_404 를 받는다")
        void readAll_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(read()).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("실시간")
    class Realtime {

        @Test
        @DisplayName("noti.created_알림이 만들어지면_개인 큐로 항목과 unreadCount 가 온다")
        void create_sendsNotiCreatedToOwner() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            BlockingQueue<String> personal = subscribePersonal(sessionA);

            long id = create(ACCOUNT_A, NotificationKind.ORDER_FILLED, "예약 매수가 체결됐습니다", 9812L, FIRST_TICK);

            String frame = awaitType(personal, "noti.created");
            assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo((int) FIRST_TICK);
            assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo("2020-01-02");
            assertThat(((Number) JsonPath.read(frame, "$.payload.notification.notificationId")).longValue()).isEqualTo(id);
            assertThat((String) JsonPath.read(frame, "$.payload.notification.kind")).isEqualTo("ORDER_FILLED");
            assertThat((Integer) JsonPath.read(frame, "$.payload.notification.referenceId")).isEqualTo(9812);
            assertThat((Boolean) JsonPath.read(frame, "$.payload.notification.read")).isFalse();
            assertThat((Integer) JsonPath.read(frame, "$.payload.unreadCount")).isEqualTo(1);

            mockMvc.perform(list()).andExpect(jsonPath("$.data.unreadCount").value(1));
        }

        @Test
        @DisplayName("noti.created_호출자의 트랜잭션 안에서 만들면_커밋 뒤에만 나가고 롤백되면 나가지 않는다")
        void create_insideTransaction_sendsOnlyAfterCommit() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            BlockingQueue<String> personal = subscribePersonal(sessionA);

            transaction.executeWithoutResult(status -> {
                notifications.create(ACCOUNT_A, NotificationKind.AWARD, "롤백", "이 알림은 없던 일이 된다", null, null);
                status.setRollbackOnly();
            });
            assertThat(personal.poll(500, TimeUnit.MILLISECONDS)).isNull();

            transaction.executeWithoutResult(status ->
                    notifications.create(ACCOUNT_A, NotificationKind.AWARD, "커밋", "이 알림은 남는다", null, null));
            String frame = awaitType(personal, "noti.created");
            assertThat((String) JsonPath.read(frame, "$.payload.notification.title")).isEqualTo("커밋");
            assertThat(nullableOf(frame, "$.tickSeq")).isNull();
            assertThat((Integer) JsonPath.read(frame, "$.payload.unreadCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("noti.created_전원 공지도_계좌마다 자기 미읽음 수를 받는다")
        void createForAll_sendsPerAccountUnreadCount() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            givenAccount(ACCOUNT_B, ownerB);
            // A 만 이미 하나 안 읽었다. 공지가 한 통 더 얹히면 A 는 2, B 는 1 이어야 한다 —
            // 한 번에 쓰면서 수를 하나로 계산하면 이 차이가 사라진다.
            create(ACCOUNT_A, NotificationKind.AWARD, "먼저 온 것", null, null);
            BlockingQueue<String> personal = subscribePersonal(sessionA);

            notifications.createForAll(List.of(ACCOUNT_A, ACCOUNT_B), NotificationKind.NOTICE,
                    "수수료 무료 타임이 시작됐습니다", "지금부터 10분간 매도 수수료가 없습니다.", null, null);

            String frame = awaitType(personal, "noti.created");
            assertThat((String) JsonPath.read(frame, "$.payload.notification.title"))
                    .isEqualTo("수수료 무료 타임이 시작됐습니다");
            assertThat((Integer) JsonPath.read(frame, "$.payload.unreadCount")).isEqualTo(2);
            // 한 문장으로 넣고 RETURNING 으로 식별자를 받는다. 행 순서는 보장되지 않으므로
            // 계좌와 식별자의 짝이 어긋나면 남의 알림 식별자가 이 사람에게 간다.
            Long mine = jdbc.queryForObject(
                    "SELECT id FROM notifications WHERE account_id = ? AND kind = 'NOTICE'",
                    Long.class, ACCOUNT_A);
            assertThat(((Number) JsonPath.read(frame, "$.payload.notification.notificationId")).longValue())
                    .isEqualTo(mine);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM notifications WHERE account_id = ? AND read_at IS NULL",
                    Integer.class, ACCOUNT_B)).isEqualTo(1);
        }

        @Test
        @DisplayName("noti.created_다른 사람의 알림은_내 개인 큐에 오지 않는다")
        void create_forAnotherAccount_isNotDeliveredToMe() throws Exception {
            givenAccount(ACCOUNT_A, ownerA);
            givenAccount(ACCOUNT_B, ownerB);
            BlockingQueue<String> personal = subscribePersonal(sessionA);

            create(ACCOUNT_B, NotificationKind.AWARD, "남의 것", null, null);

            assertThat(personal.poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    private long create(long accountId, NotificationKind kind, String title, Long referenceId, Long tickSeq) {
        return notifications.create(accountId, kind, title, title + " 본문", referenceId, tickSeq).getId();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list() {
        return get(NOTIFICATIONS_URI).header(AUTH_TOKEN_HEADER, sessionA);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder read() {
        return post(READ_URI).header(AUTH_TOKEN_HEADER, sessionA);
    }

    private void givenAccount(long accountId, User owner) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, owner.getId(), ReplayFixture.SEASON_ID);
    }

    /**
     * 개인 큐를 구독하고, SUBSCRIBE 가 브로커에 등록되기를 <b>실제로 한 프레임을 받아</b> 확인한다.
     * 확인용 알림은 지운다 — 이후 테스트의 목록·미읽음 수에 섞이면 안 된다.
     */
    private BlockingQueue<String> subscribePersonal(String sessionId) throws Exception {
        Frames frames = connect(sessionId);
        BlockingQueue<String> sink = subscribe(frames.session(), WsDestination.PERSONAL);
        for (int attempt = 0; attempt < 50; attempt++) {
            long probe = create(ACCOUNT_A, NotificationKind.NOTICE, "구독 확인", null, null);
            String frame = sink.poll(200, TimeUnit.MILLISECONDS);
            jdbc.update("DELETE FROM notifications WHERE id = ?", probe);
            if (frame != null) {
                Thread.sleep(100);
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

    private static Object nullableOf(String frame, String path) {
        try {
            return JsonPath.read(frame, path);
        } catch (PathNotFoundException e) {
            return null;
        }
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
