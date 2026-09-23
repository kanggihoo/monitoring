package com.ssafy.a605.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.moderation.service.ForbiddenWordFilter;
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
 * 채팅의 HTTP 경계와 실제 STOMP 도착을 본다 ({@code soc.md}).
 *
 * <p>읽기는 미인증도 되고 쓰기는 로그인이 필요하다는 정책의 경계, 금칙어가 차단이 아니라 마스킹인
 * 것, 쿨다운이 429 로 나가는 것이 이 테스트의 핵심이다.
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
class ChatApiTest {

    private static final String MESSAGES_URI = "/api/v1/chat/messages";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02. */
    private static final long FIRST_TICK = 1L;

    /**
     * 마스킹 검증용 단어. 적재된 사전에 없는 것을 골라 넣고 쓴 뒤 지운다 —
     * 사전 전체를 지우면 같은 DB 를 쓰는 뒤 테스트가 빈 사전을 보게 된다.
     */
    private static final String TEST_FORBIDDEN = "금칙어";

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
    private ForbiddenWordFilter forbiddenWords;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<StompSession> openSessions = new ArrayList<>();
    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;

    private User speaker;
    private User other;
    private String session;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        jdbc.update("DELETE FROM chat_messages");
        forbiddenWords.reload();
        speaker = fixture.user("채팅발화자");
        other = fixture.user("채팅상대");
        session = sessions.createSessionFor(speaker);
        givenOperatingAt(FIRST_TICK);

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
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM forbidden_words WHERE word = ?", TEST_FORBIDDEN);
        forbiddenWords.reload();
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("히스토리_세션이 없어도_읽을 수 있다")
        void getMessages_withoutSession_isPermitted() throws Exception {
            givenMessage(speaker, "미인증도 이건 보인다");

            mockMvc.perform(get(MESSAGES_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].content").value("미인증도 이건 보인다"));
        }

        @Test
        @DisplayName("전송_세션이 유효하지 않으면_401 이고 쓰이지 않는다")
        void send_withInvalidSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(post(MESSAGES_URI)
                            .header(AUTH_TOKEN_HEADER, "not-a-session")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"미인증은 못 쓴다\"}"))
                    .andExpect(status().isUnauthorized());

            assertThat(countMessages()).isZero();
        }

        @Test
        @DisplayName("전송_자격 증명이 아예 없으면_거부되고 쓰이지 않는다")
        void send_withoutCredentials_isRejected() throws Exception {
            // 쿠키도 헤더도 없는 POST 는 인증 이전에 CSRF 가 거른다. 어느 쪽이든 쓰이지 않는 것이 정책이다.
            mockMvc.perform(post(MESSAGES_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"미인증은 못 쓴다\"}"))
                    .andExpect(status().is4xxClientError());

            assertThat(countMessages()).isZero();
        }
    }

    @Nested
    @DisplayName("전송")
    class Sending {

        @Test
        @DisplayName("전송_201 이고_저장된 메시지가 응답으로 온다")
        void send_returnsCreatedMessage() throws Exception {
            mockMvc.perform(send("지금 들어가는 거 아니지"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.content").value("지금 들어가는 거 아니지"))
                    .andExpect(jsonPath("$.data.nickname").value(speaker.getNickname()))
                    .andExpect(jsonPath("$.data.avatarId").value(speaker.getAvatarId().intValue()))
                    .andExpect(jsonPath("$.data.gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.withdrawn").value(false))
                    .andExpect(jsonPath("$.data.deleted").value(false));
        }

        @Test
        @DisplayName("전송_빈 본문과 200자 초과는_400 이다")
        void send_withInvalidContent_returnsBadRequest() throws Exception {
            mockMvc.perform(send("   ")).andExpect(status().isBadRequest());
            mockMvc.perform(send("가".repeat(201))).andExpect(status().isBadRequest());

            assertThat(countMessages()).isZero();
        }

        @Test
        @DisplayName("전송_3초 안에 다시 보내면_429 이고 Retry-After 가 실린다")
        void send_withinCooldown_returnsTooManyRequests() throws Exception {
            mockMvc.perform(send("첫 줄")).andExpect(status().isCreated());

            mockMvc.perform(send("바로 또"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));

            assertThat(countMessages()).isEqualTo(1);
        }

        @Test
        @DisplayName("전송_쿨다운이 지나면_다시 보낼 수 있다")
        void send_afterCooldown_isAccepted() throws Exception {
            mockMvc.perform(send("첫 줄")).andExpect(status().isCreated());

            clock.advance(Duration.ofSeconds(4));

            mockMvc.perform(send("이제 된다")).andExpect(status().isCreated());
            assertThat(countMessages()).isEqualTo(2);
        }

        @Test
        @DisplayName("전송_열람 전용 시간이면_409 다")
        void send_duringReviewOnly_returnsConflict() throws Exception {
            // 발표 경계까지 처리된 상태가 REVIEW_ONLY 다.
            givenClockAt(FIRST_TICK, ReplayState.END);

            mockMvc.perform(send("열람 전용에는 못 쓴다"))
                    .andExpect(status().isConflict());

            assertThat(countMessages()).isZero();
        }

        @Test
        @DisplayName("전송_금칙어가 있으면_차단이 아니라 마스킹되어 저장된다")
        void send_withForbiddenWord_isMaskedNotRejected() throws Exception {
            givenForbiddenWord(TEST_FORBIDDEN);

            mockMvc.perform(send(TEST_FORBIDDEN + " 개폭락"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.content").value("*** 개폭락"));

            assertThat(jdbc.queryForObject("SELECT content FROM chat_messages", String.class))
                    .isEqualTo("*** 개폭락");
        }
    }

    @Nested
    @DisplayName("히스토리")
    class History {

        @Test
        @DisplayName("히스토리_최신순으로 오고_커서로 과거를 이어 받는다")
        void getMessages_isLatestFirstAndPaged() throws Exception {
            for (int i = 1; i <= 3; i++) {
                givenMessage(speaker, "메시지 " + i);
            }

            String body = mockMvc.perform(get(MESSAGES_URI).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(2)))
                    .andExpect(jsonPath("$.data.items[0].content").value("메시지 3"))
                    .andExpect(jsonPath("$.data.items[1].content").value("메시지 2"))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andReturn().getResponse().getContentAsString();

            String cursor = JsonPath.read(body, "$.data.nextCursor");
            mockMvc.perform(get(MESSAGES_URI).param("size", "2").param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].content").value("메시지 1"))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        }

        @Test
        @DisplayName("히스토리_지난 일차의 대화는_커서를 끝까지 올려도 오지 않는다")
        void getMessages_excludesEarlierSeasonDays() throws Exception {
            givenMessage(speaker, "오늘 대화");
            // 일차만 다른 행 하나. 행은 남되 조회에서만 빠지는 것이 이 기능이다.
            jdbc.update("""
                    INSERT INTO chat_messages (user_id, content, tick_seq, season_day, sent_at)
                    VALUES (?, ?, NULL, 2, now())
                    """, speaker.getId(), "어제 대화");

            mockMvc.perform(get(MESSAGES_URI).param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].content").value("오늘 대화"))
                    .andExpect(jsonPath("$.data.hasNext").value(false));
            assertThat(countMessages()).isEqualTo(2);
        }

        @Test
        @DisplayName("히스토리_커서가 우리 모양이 아니면_400 이다")
        void getMessages_withBrokenCursor_returnsBadRequest() throws Exception {
            mockMvc.perform(get(MESSAGES_URI).param("cursor", "!!!not-a-cursor!!!"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("히스토리_보낸 사람이 탈퇴했으면_닉네임이 비고 withdrawn 이 참이다")
        void getMessages_withWithdrawnSender_isAnonymised() throws Exception {
            givenMessage(other, "탈퇴 전에 남긴 말");
            givenWithdrawn(other);

            mockMvc.perform(get(MESSAGES_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].withdrawn").value(true))
                    .andExpect(jsonPath("$.data.items[0].nickname").value(nullValue()))
                    // 메시지 자체는 남는다 — 탈퇴가 남의 대화를 지우지 않는다.
                    .andExpect(jsonPath("$.data.items[0].content").value("탈퇴 전에 남긴 말"));
        }

        @Test
        @DisplayName("히스토리_관리자가 삭제한 메시지는_행은 남고 본문은 비어 온다")
        void getMessages_withDeletedMessage_hidesContent() throws Exception {
            long messageId = givenMessage(speaker, "지워질 말");
            jdbc.update("UPDATE chat_messages SET deleted_at = now() WHERE id = ?", messageId);

            mockMvc.perform(get(MESSAGES_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].deleted").value(true))
                    .andExpect(jsonPath("$.data.items[0].content").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("실시간")
    class Realtime {

        @Test
        @DisplayName("chat.message_전송하면_전체 채팅 토픽으로 온다")
        void send_isBroadcastToChatTopic() throws Exception {
            BlockingQueue<String> chat = subscribeChat();

            mockMvc.perform(send("모두에게 간다")).andExpect(status().isCreated());

            String frame = awaitType(chat, "chat.message");
            assertThat((String) JsonPath.read(frame, "$.payload.content")).isEqualTo("모두에게 간다");
            assertThat((String) JsonPath.read(frame, "$.payload.nickname")).isEqualTo(speaker.getNickname());
            // 채팅은 게임 시계와 독립이지만 공개 기준은 함께 싣는다.
            assertThat((Integer) JsonPath.read(frame, "$.tickSeq")).isEqualTo((int) FIRST_TICK);
            assertThat((String) JsonPath.read(frame, "$.gameDate")).isEqualTo("2020-01-02");
        }
    }

    private MockHttpServletRequestBuilder send(String content) {
        return post(MESSAGES_URI)
                .header(AUTH_TOKEN_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content));
    }

    /** 운영 시간(거래 구간)으로 재생을 고정한다. 배속 0 이라 폴러가 더 진행하지 않는다. */
    private void givenOperatingAt(long tickSeq) {
        givenClockAt(tickSeq, ReplayState.TRADING_OPEN);
    }

    private void givenClockAt(long tickSeq, int boundaryOffset) {
        fixture.store(1, BigDecimal.valueOf(boundaryOffset), ReplayFixture.START, BigDecimal.ZERO, tickSeq, 1,
                boundaryOffset);
    }

    private void givenForbiddenWord(String word) {
        jdbc.update("INSERT INTO forbidden_words (word, created_at) VALUES (?, now())", word);
        forbiddenWords.reload();
    }

    /**
     * 탈퇴를 흉내 낸다 — 탈퇴는 users 를 물리 삭제하므로 연결된 OAuth 행을 먼저 지운다.
     * 채팅 메시지는 남는다. 그것이 익명화의 전제다.
     */
    private void givenWithdrawn(User user) {
        jdbc.update("DELETE FROM oauth_accounts WHERE user_id = ?", user.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    private long givenMessage(User sender, String content) {
        // 일차는 틱에서 가져온다 — 조회가 진행 중인 일차로 좁히므로 픽스처가 그 일차에 있어야 한다.
        jdbc.update("""
                INSERT INTO chat_messages (user_id, content, tick_seq, season_day, sent_at)
                VALUES (?, ?, ?, (SELECT season_day FROM game_ticks WHERE tick_seq = ?), now())
                """, sender.getId(), content, FIRST_TICK, FIRST_TICK);
        return jdbc.queryForObject("SELECT max(id) FROM chat_messages", Long.class);
    }

    private int countMessages() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_messages", Integer.class);
    }

    private BlockingQueue<String> subscribeChat() throws Exception {
        Frames frames = connect();
        BlockingQueue<String> sink = subscribe(frames.session(), WsDestination.CHAT.path());
        // SUBSCRIBE 를 보냈다고 브로커가 등록했다는 뜻은 아니다. 실제로 한 프레임을 받아 확인하고
        // 확인에 쓴 메시지는 지운다 — 이후 단정에 섞이면 안 된다.
        for (int attempt = 0; attempt < 50; attempt++) {
            mockMvc.perform(send("구독 확인 " + attempt));
            clock.advance(Duration.ofSeconds(4));
            if (sink.poll(200, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(100);
                sink.clear();
                jdbc.update("DELETE FROM chat_messages");
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
