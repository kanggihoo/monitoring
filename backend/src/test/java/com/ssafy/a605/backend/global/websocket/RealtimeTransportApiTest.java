package com.ssafy.a605.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.global.config.WebSocketConfig;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * WS 전송 계층을 <b>앱의 외부 경계</b>에서 검증한다. 어떤 클래스가 호출됐는지, 브로커의 구독
 * 테이블이 어떤 모양인지, 어떤 스레드에서 발행됐는지는 보지 않는다 — 실제 STOMP 클라이언트가
 * <b>받았는가 / 못 받았는가</b>만 본다.
 *
 * <p>기존 API 테스트와 같은 seam 이고 도달 방법만 다르다. {@code MockMvc} 는 WebSocket 핸드셰이크를
 * 하지 못하므로 실제 포트를 띄우고 실제 STOMP 클라이언트로 붙는다.
 *
 * <p><b>발행도 이 seam 을 통과시킨다.</b> 테스트가 발행 컴포넌트를 직접 부르지 않고 테스트 지원
 * 트리거를 HTTP 로 부른다. 그러면 트리거까지 함께 검증되고 seam 이 하나로 유지된다.
 */
@Tag("container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.test-support.enabled=true",
                "app.test-support.api-key=" + RealtimeTransportApiTest.API_KEY
        })
@Import({ContainersConfig.class, SessionSupport.class})
class RealtimeTransportApiTest {

    /** 32자 이상이어야 컨텍스트가 뜬다. */
    static final String API_KEY = "test-support-api-key-0123456789ab";

    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;
    private static final long[] CONTRACTED_HEARTBEAT = {10_000L, 10_000L};

    /** STOMP {@code ERROR} 프레임이 원인을 싣는 헤더. */
    private static final String ERROR_MESSAGE_HEADER = "message";

    /** 화면 소비자가 없는 세 destination 에 트리거가 싣는 자리 채움 {@code type}. */
    private static final String PLACEHOLDER_TYPE = "test.placeholder";

    /** 트리거가 순번 없이 불렸을 때 싣는 값. */
    private static final int DEFAULT_TICK_SEQ = 137;

    /** 프레임 하나를 기다리는 한도. */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(5);

    /** "오지 않아야 한다"를 판정하는 한도. 와야 할 것이 이미 온 뒤에만 쓴다. */
    private static final Duration ABSENCE_WINDOW = Duration.ofMillis(500);

    /** 구독 준비를 확인할 최대 시도 횟수. 보통 첫 시도에 끝난다. */
    private static final int READY_ATTEMPTS = 50;

    /** 한 시도에서 프레임을 기다리는 시간. */
    private static final Duration READY_POLL = Duration.ofMillis(200);

    /** 마지막 확인 브로드캐스트가 배달을 마치도록 가라앉히는 시간. */
    private static final Duration READY_SETTLE = Duration.ofMillis(200);

    /**
     * 세션 소멸 프레임을 기다리는 한도. 프레임 하나보다 넉넉하다 — 세션 소멸은 Redis 키 이벤트를
     * 거쳐 오므로 애플리케이션 안의 왕복보다 한 단계 늦다.
     */
    private static final Duration SESSION_END_TIMEOUT = Duration.ofSeconds(15);

    /** 백엔드가 소유한 로그아웃 경로. 앱 인증 헤더 경로는 위조 방지 토큰 검사에서 제외된다. */
    private static final String LOGOUT_PATH = "/api/auth/logout";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionSupport sessions;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<StompSession> openSessions = new ArrayList<>();

    private ThreadPoolTaskScheduler heartbeatScheduler;
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        this.heartbeatScheduler = new ThreadPoolTaskScheduler();
        this.heartbeatScheduler.afterPropertiesSet();

        this.stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // body 를 파싱된 객체가 아니라 원문으로 받는다 — 봉투가 JSON 으로 나갔는지를 그 자체로 본다.
        StringMessageConverter rawBody = new StringMessageConverter();
        // 이 컨버터가 지원하는 타입은 text/plain 인데 서버는 application/json 을 붙인다. 타입 해석을
        // 끄지 않으면 프레임이 조용히 버려져 "안 왔다"로 보인다.
        rawBody.setContentTypeResolver(null);
        this.stompClient.setMessageConverter(rawBody);
        // 클라이언트도 하트비트를 요구한다 (프론트와 같은 설정). 스케줄러가 없으면 접속 시도가 거부된다.
        this.stompClient.setTaskScheduler(this.heartbeatScheduler);
    }

    @AfterEach
    void tearDown() {
        this.openSessions.forEach(RealtimeTransportApiTest::disconnectQuietly);
        this.openSessions.clear();
        this.heartbeatScheduler.destroy();
    }

    /**
     * 구독 거부를 검증하는 테스트는 서버가 ERROR 프레임을 보낸 뒤 연결을 닫는다. 그때
     * {@code isConnected()} 가 아직 참으로 보이는 순간이 있어 DISCONNECT 전송이 실패하는데,
     * <b>정리 단계의 실패는 그 테스트의 단언과 무관하다</b> — 걸러내지 않으면 통과한 테스트가
     * 간헐적으로 빨갛게 된다.
     */
    private static void disconnectQuietly(StompSession session) {
        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException ignored) {
            // 이미 닫힌 세션이다. 정리할 것이 없다.
        }
    }

    @Nested
    @DisplayName("핸드셰이크")
    class Handshake {

        @Test
        @DisplayName("접속_유효한 세션 쿠키_CONNECTED 를 받는다")
        void connect_withSessionCookie_receivesConnectedFrame() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "쿠키")));

            Frames frames = connect(cookieHeaders(sessionId));

            assertThat(frames.connected()).isNotNull();
        }

        @Test
        @DisplayName("접속_X-Auth-Token 헤더만_CONNECTED 를 받는다")
        void connect_withAuthTokenHeader_receivesConnectedFrame() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "헤더")));

            Frames frames = connect(authTokenHeaders(sessionId));

            assertThat(frames.connected()).isNotNull();
        }

        /**
         * <b>ADR-0004 결정 5(미인증 연결 금지)를 뒤집은 자리다.</b> 이전 판은 같은 요청에 401 을
         * 단언했고, 그 단언이 {@code SecurityConfig} 에 {@code /ws} 예외가 없다는 사실을
         * 고정하고 있었다. 이제 핸드셰이크는 인증을 요구하지 않으므로 401 이 아니다 —
         * {@code Upgrade} 헤더 없는 평범한 GET 이라 WebSocket 핸들러가 400 으로 돌려준다.
         */
        @Test
        @DisplayName("핸드셰이크_세션 없음_인가가 아니라 업그레이드 실패로 거부된다")
        void handshake_withoutSession_isNotRejectedByAuthorization() throws Exception {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(httpBaseUrl() + WebSocketConfig.ENDPOINT)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isNotEqualTo(401);
        }

        @Test
        @DisplayName("접속_세션 없음_CONNECTED 를 받는다")
        void connect_withoutSession_receivesConnectedFrame() throws Exception {
            Frames frames = connect(new WebSocketHttpHeaders());

            assertThat(frames.connected()).isNotNull();
        }

        @Test
        @DisplayName("접속_CONNECTED 프레임_하트비트 10초를 광고한다")
        void connect_connectedFrame_advertisesTenSecondHeartbeat() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "하트비트")));

            Frames frames = connect(authTokenHeaders(sessionId));

            assertThat(frames.connected().getHeartbeat()).isEqualTo(CONTRACTED_HEARTBEAT);
        }
    }

    @Nested
    @DisplayName("구독")
    class Subscription {

        /**
         * <b>검사를 넣기 전에 관측한 것을 고정한다.</b> 검사가 없을 때 이 구독은 조용히
         * 성공했다 — ERROR 도 없고 연결도 살아 있고 메시지만 오지 않았다. 남의 메시지가 새지는
         * 않지만(브로커가 세션별 경로로 바꿔 등록한다) 그 침묵이 곧 디버깅 비용이고,
         * 프론트 분기만으로 개인 데이터 차단을 보장할 수 없다는 요구가 여기서 받힌다.
         */
        @Test
        @DisplayName("구독_미인증이 개인 큐를_ERROR 프레임으로 거부된다")
        void subscribe_toPersonalQueueWithoutSession_isRejected() throws Exception {
            Frames frames = connect(new WebSocketHttpHeaders());

            frames.session().subscribe(WsDestination.PERSONAL, collectingTo(new LinkedBlockingQueue<>()));

            assertThat(frames.nextError())
                    .contains(ContractedDestinationInterceptor.CONTRACT_VIOLATION)
                    .contains(WsDestination.PERSONAL);
        }

        @Test
        @DisplayName("구독_미인증이 공개 토픽 셋을_전부 받는다")
        void subscribe_toPublicTopicsWithoutSession_receivesBroadcasts() throws Exception {
            String publisherSessionId = sessions.createSessionFor(userRepository.save(new User(null, "발행")));
            Frames frames = connect(new WebSocketHttpHeaders());
            BlockingQueue<Frame> tick = subscribe(frames.session(), WsDestination.TICK.path());
            BlockingQueue<Frame> event = subscribe(frames.session(), WsDestination.EVENT.path());
            BlockingQueue<Frame> chat = subscribe(frames.session(), WsDestination.CHAT.path());
            awaitSubscribed(publisherSessionId, tick, tick, event, chat);

            triggerBroadcast(publisherSessionId, null);

            assertThat(typeOf(await(tick))).isEqualTo("market.prices");
            assertThat(typeOf(await(event))).isEqualTo(PLACEHOLDER_TYPE);
            assertThat(typeOf(await(chat))).isEqualTo(PLACEHOLDER_TYPE);
        }

        /**
         * 거부하지 않으면 오타 난 구독이 브로커 접두사에 걸려 정상 등록되고, 메시지가 오지 않는다는
         * 사실만 남는다.
         */
        @Test
        @DisplayName("구독_계약에 없는 destination_ERROR 프레임을 받는다")
        void subscribe_toUncontractedDestination_receivesErrorFrame() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "오타")));
            Frames frames = connect(authTokenHeaders(sessionId));

            frames.session().subscribe("/topic/tick-typo", collectingTo(new LinkedBlockingQueue<>()));

            assertThat(frames.nextError())
                    .contains(ContractedDestinationInterceptor.CONTRACT_VIOLATION)
                    .contains("/topic/tick-typo");
        }
    }

    /**
     * 세션이 죽으면 그 세션의 연결이 끊긴다. <b>세션 소멸은 Redis 키 이벤트를 거쳐 비동기로
     * 도착하므로</b> 프레임을 기다리는 것으로 판정한다 — 즉시 단언하면 이벤트가 오기 전이다.
     */
    @Nested
    @DisplayName("세션 수명")
    class SessionLifetime {

        /**
         * 사유를 보내는 것만으로는 부족하다 — <b>연결이 실제로 닫혀야</b> 그 세션이 개인 메시지를
         * 더 받지 못한다. 프레임과 종료를 함께 본다.
         */
        @Test
        @DisplayName("로그아웃_그 세션의 연결에_사유가 실린 ERROR 가 오고 연결이 닫힌다")
        void logout_sendsSessionEndedThenClosesItsConnection() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "로그아웃")));
            Frames frames = connect(authTokenHeaders(sessionId));

            logout(sessionId);

            assertThat(frames.nextError(SESSION_END_TIMEOUT))
                    .contains(SessionEndedDisconnector.SESSION_ENDED);
            awaitClosed(frames.session());
        }

        /**
         * 같은 세션의 다른 탭도 함께 끊긴다 — 로그아웃이 그 탭에서만 유효한 시늉이 되지 않는다.
         */
        @Test
        @DisplayName("로그아웃_같은 세션의 다른 탭도_함께 끊긴다")
        void logout_endsEveryConnectionOfThatSession() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "두탭")));
            Frames first = connect(authTokenHeaders(sessionId));
            Frames second = connect(authTokenHeaders(sessionId));

            logout(sessionId);

            assertThat(first.nextError(SESSION_END_TIMEOUT)).contains(SessionEndedDisconnector.SESSION_ENDED);
            assertThat(second.nextError(SESSION_END_TIMEOUT)).contains(SessionEndedDisconnector.SESSION_ENDED);
        }

        /** 노트북에서 로그아웃했다고 휴대폰까지 끊기지 않는다. 세션 단위로 끊고 사용자 단위로 끊지 않는다. */
        @Test
        @DisplayName("로그아웃_같은 사용자의 다른 세션은_유지된다")
        void logout_leavesOtherSessionsOfTheSameUserAlone() throws Exception {
            User user = userRepository.save(new User(null, "두기기"));
            String laptop = sessions.createSessionFor(user);
            String phone = sessions.createSessionFor(user);
            Frames laptopFrames = connect(authTokenHeaders(laptop));
            Frames phoneFrames = connect(authTokenHeaders(phone));
            BlockingQueue<Frame> phoneTick = subscribe(phoneFrames.session(), WsDestination.TICK.path());

            logout(laptop);
            assertThat(laptopFrames.nextError(SESSION_END_TIMEOUT))
                    .contains(SessionEndedDisconnector.SESSION_ENDED);

            // 살아 있다는 것은 "ERROR 가 안 왔다"가 아니라 "메시지가 계속 온다"로 본다.
            triggerBroadcast(phone, null);
            assertThat(typeOf(await(phoneTick))).isEqualTo("market.prices");
        }

        /** 끊긴 클라이언트는 미인증 공개 연결로 다시 붙는다. 서버가 막지 않는다. */
        @Test
        @DisplayName("로그아웃 후_미인증으로 다시 붙어_공개 토픽을 계속 받는다")
        void afterLogout_reconnectsUnauthenticatedAndKeepsReceivingPublicTopics() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "재접속")));
            Frames before = connect(authTokenHeaders(sessionId));
            String publisherSessionId = sessions.createSessionFor(userRepository.save(new User(null, "재접속발행")));
            logout(sessionId);
            before.nextError(SESSION_END_TIMEOUT);

            Frames after = connect(new WebSocketHttpHeaders());
            BlockingQueue<Frame> tick = subscribe(after.session(), WsDestination.TICK.path());

            triggerBroadcast(publisherSessionId, null);

            assertThat(typeOf(await(tick))).isEqualTo("market.prices");
        }
    }

    @Nested
    @DisplayName("발행")
    class Publish {

        @Test
        @DisplayName("발행_네 destination 구독_봉투 넷을 받는다")
        void publish_afterSubscribingAll_receivesFourEnvelopes() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "구독자")));
            Frames frames = connect(authTokenHeaders(sessionId));
            BlockingQueue<Frame> tick = subscribe(frames.session(), WsDestination.TICK.path());
            BlockingQueue<Frame> event = subscribe(frames.session(), WsDestination.EVENT.path());
            BlockingQueue<Frame> chat = subscribe(frames.session(), WsDestination.CHAT.path());
            BlockingQueue<Frame> personal = subscribe(frames.session(), WsDestination.PERSONAL);
            awaitSubscribed(sessionId, personal, tick, event, chat, personal);

            triggerBroadcast(sessionId, null);

            assertThat(typeOf(await(tick))).isEqualTo("market.prices");
            assertThat(typeOf(await(event))).isEqualTo(PLACEHOLDER_TYPE);
            assertThat(typeOf(await(chat))).isEqualTo(PLACEHOLDER_TYPE);
            assertThat(typeOf(await(personal))).isEqualTo(PLACEHOLDER_TYPE);
        }

        /**
         * {@code /user/queue/me} 라우팅의 핵심. 구독 문자열은 둘이 같지만 도착하는 내용은 자기 것뿐이어야
         * 한다 — 남의 자산이 보이면 안 된다.
         */
        @Test
        @DisplayName("발행_사용자 둘이 붙은 상태의 개인 메시지_자기 것만 받는다")
        void publish_personalMessage_reachesOnlyItsOwner() throws Exception {
            String mineSessionId = sessions.createSessionFor(userRepository.save(new User(null, "나")));
            String othersSessionId = sessions.createSessionFor(userRepository.save(new User(null, "남")));
            Frames mine = connect(authTokenHeaders(mineSessionId));
            Frames others = connect(authTokenHeaders(othersSessionId));
            BlockingQueue<Frame> minePersonal = subscribe(mine.session(), WsDestination.PERSONAL);
            BlockingQueue<Frame> othersPersonal = subscribe(others.session(), WsDestination.PERSONAL);
            // 남도 브로드캐스트를 구독한다 — 그 연결이 살아 있음을 확인해야 "안 왔다"가 의미를 갖는다.
            BlockingQueue<Frame> othersTick = subscribe(others.session(), WsDestination.TICK.path());
            // 두 연결 세 구독이라 준비 창이 가장 넓다. 확인용 브로드캐스트는 mine 세션으로
            // 쏘므로 개인 메시지가 others 에게 가지 않아 아래 부재 단정이 그대로 성립한다.
            awaitSubscribed(mineSessionId, othersTick, minePersonal, othersPersonal, othersTick);

            triggerBroadcast(mineSessionId, null);

            assertThat(typeOf(await(minePersonal))).isEqualTo(PLACEHOLDER_TYPE);
            assertThat(typeOf(await(othersTick))).isEqualTo("market.prices");
            assertThat(othersPersonal.poll(ABSENCE_WINDOW.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        }

        /**
         * {@code tickSeq}·{@code gameDate} 가 STOMP 헤더가 아니라 <b>body</b> 에 있어야 한다.
         * REST 응답과 형태가 같아야 프론트가 초기 로드와 실시간 수신에 같은 파서를 쓴다.
         */
        @Test
        @DisplayName("발행_수신한 프레임_봉투 네 필드가 body 에 담긴다")
        void publish_receivedFrame_carriesEnvelopeInBody() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "봉투")));
            Frames frames = connect(authTokenHeaders(sessionId));
            BlockingQueue<Frame> tick = subscribe(frames.session(), WsDestination.TICK.path());
            awaitSubscribed(sessionId, tick, tick);

            triggerBroadcast(sessionId, null);
            Frame received = await(tick);

            assertThat(JsonPath.<String>read(received.body(), "$.type")).isEqualTo("market.prices");
            assertThat(JsonPath.<Integer>read(received.body(), "$.tickSeq")).isEqualTo(DEFAULT_TICK_SEQ);
            assertThat(JsonPath.<String>read(received.body(), "$.gameDate")).isEqualTo("2022-04-15");
            assertThat(JsonPath.<String>read(received.body(), "$.payload.usdKrwRate")).isEqualTo("1243.50");
            assertThat(received.headers().getFirst("tickSeq")).isNull();
            assertThat(received.headers().getFirst("gameDate")).isNull();
        }

        /**
         * 프론트의 틱 스토어가 확정된 순번 이하를 버리므로, 화면 확인은 순번을 지정할 수 있어야
         * 성립한다. 봉투와 payload 가 <b>같은</b> 값을 실어야 한다 — 프론트가 둘 중 어느 쪽을 읽어도
         * 같은 Tick 으로 판정한다.
         */
        @Test
        @DisplayName("발행_tickSeq 를 지정한 호출_봉투와 payload 가 그 값을 함께 싣는다")
        void publish_withGivenTickSeq_carriesItInBothEnvelopeAndPayload() throws Exception {
            String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "순번")));
            Frames frames = connect(authTokenHeaders(sessionId));
            BlockingQueue<Frame> tick = subscribe(frames.session(), WsDestination.TICK.path());
            // 준비 확인은 기본 순번으로 쏘고 큐를 비운다. 그래야 아래 단정이 9001 을 실은
            // 프레임을 본다 - 확인용 프레임이 남으면 기본 순번을 읽고 실패한다.
            awaitSubscribed(sessionId, tick, tick);

            triggerBroadcast(sessionId, 9001L);
            Frame received = await(tick);

            assertThat(JsonPath.<Integer>read(received.body(), "$.tickSeq")).isEqualTo(9001);
            assertThat(JsonPath.<Integer>read(received.body(), "$.payload.asOfTickSeq")).isEqualTo(9001);
        }
    }

    private String httpBaseUrl() {
        return "http://localhost:" + this.port;
    }

    private WebSocketHttpHeaders cookieHeaders(String sessionId) {
        Cookie cookie = this.sessions.sessionCookie(sessionId);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie.getName() + "=" + cookie.getValue());
        return headers;
    }

    private WebSocketHttpHeaders authTokenHeaders(String sessionId) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(AUTH_TOKEN_HEADER, sessionId);
        return headers;
    }

    private Frames connect(WebSocketHttpHeaders handshakeHeaders) throws Exception {
        Frames frames = new Frames();
        StompSession session = this.stompClient
                .connectAsync("ws://localhost:" + this.port + WebSocketConfig.ENDPOINT, handshakeHeaders, frames)
                .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        this.openSessions.add(session);
        frames.connectedHeaders().get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return frames;
    }

    /**
     * 위조 방지 토큰 검사는 앱 인증 헤더 경로에서 제외되므로 세션 ID 만으로 부를 수 있다.
     *
     * @param tickSeq 실을 순번. {@code null} 이면 인자를 붙이지 않아 기본값 경로를 탄다
     */
    private void triggerBroadcast(String sessionId, Long tickSeq) throws IOException, InterruptedException {
        String uri = httpBaseUrl() + TestSupportConfig.BROADCAST_PATH
                + ((tickSeq != null) ? "?tickSeq=" + tickSeq : "");
        HttpResponse<Void> response = this.httpClient.send(
                HttpRequest.newBuilder(URI.create(uri))
                        .header(AUTH_TOKEN_HEADER, sessionId)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(204);
    }

    /**
     * 연결이 닫히기를 기다린다. ERROR 프레임 전송과 소켓 종료가 같은 순간이 아니라, 프레임을
     * 받은 직후에는 {@code isConnected()} 가 아직 참으로 보이는 구간이 있다.
     */
    private static void awaitClosed(StompSession session) throws InterruptedException {
        long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
        while (session.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.isConnected())
                .as("연결이 %s 안에 닫히지 않았다", ARRIVAL_TIMEOUT)
                .isFalse();
    }

    private void logout(String sessionId) throws IOException, InterruptedException {
        HttpResponse<Void> response = this.httpClient.send(
                HttpRequest.newBuilder(URI.create(httpBaseUrl() + LOGOUT_PATH))
                        .header(AUTH_TOKEN_HEADER, sessionId)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(204);
    }

    /**
     * 구독이 <b>실제로 등록됐음을 프레임 하나로 확인한 뒤</b> 돌려준다.
     *
     * <p><b>SUBSCRIBE 는 응답이 없는 프레임이다.</b> {@code session.subscribe} 는 보내고 바로
     * 반환하므로 서버가 그 구독을 등록했는지 알 수 없다. 그 상태로 브로드캐스트를 트리거하면
     * 두 경로가 경합한다 — 구독은 WebSocket 으로 들어와 브로커가 처리하고 브로드캐스트는 별도
     * HTTP 요청이 서버에서 발행한다. 브로드캐스트가 먼저 닿으면 그 메시지는 구독자가 없는
     * 목적지로 나가 사라지고, STOMP 에 재전송이 없으므로 테스트에는 "프레임이 오지 않았다"로
     * 보인다. 준비가 많은 테스트에서 그 창이 가장 넓다.
     *
     * <p><b>영수증으로는 못 기다린다.</b> {@code enableSimpleBroker} 는 SUBSCRIBE 에 RECEIPT 를
     * 보내지 않는다 (실측: {@code setAutoReceipt} 로 요구해도 5 초 안에 오지 않는다). 그래서
     * 브로드캐스트를 반복해 쏘며 프레임이 오는지 본다 — 온 것이 곧 그 구독이 브로커에
     * 등록됐다는 증거다. {@code RankingRealtimeApiTest} 가 같은 방식으로 준비를 확인한다.
     *
     * <p>고정 대기를 늘리는 방식이 아니다. 관찰 가능한 조건에 폴링하므로 준비되면 즉시
     * 진행하고, 느린 환경에서도 한도까지 재시도한다.
     *
     * @param sessionId 브로드캐스트를 트리거할 세션. 개인 메시지는 이 세션에만 간다
     * @param probe     등록을 확인할 큐. 이 큐에 프레임이 오면 준비된 것으로 본다
     * @param sinks     준비 확인이 남긴 프레임을 비울 큐 전부. 검증은 빈 상태에서 시작한다
     */
    @SafeVarargs
    private void awaitSubscribed(String sessionId, BlockingQueue<Frame> probe,
                                 BlockingQueue<Frame>... sinks) throws Exception {
        for (int attempt = 0; attempt < READY_ATTEMPTS; attempt++) {
            triggerBroadcast(sessionId, null);
            if (probe.poll(READY_POLL.toMillis(), TimeUnit.MILLISECONDS) != null) {
                // 확인용 프레임이 남으면 검증이 그것을 본 메시지로 착각한다. 마지막 브로드캐스트가
                // 아직 배달 중일 수 있으므로 잠깐 가라앉힌 뒤 비운다.
                Thread.sleep(READY_SETTLE.toMillis());
                for (BlockingQueue<Frame> sink : sinks) {
                    sink.clear();
                }
                return;
            }
        }
        throw new AssertionError("구독이 %d 회 시도 안에 준비되지 않았다".formatted(READY_ATTEMPTS));
    }

    private static BlockingQueue<Frame> subscribe(StompSession session, String destination) {
        BlockingQueue<Frame> sink = new LinkedBlockingQueue<>();
        session.subscribe(destination, collectingTo(sink));
        return sink;
    }

    private static StompFrameHandler collectingTo(BlockingQueue<Frame> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add(new Frame(headers, (String) payload));
            }
        };
    }

    private static Frame await(BlockingQueue<Frame> sink) throws InterruptedException {
        Frame frame = sink.poll(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(frame).as("프레임이 %s 안에 도착하지 않았다", ARRIVAL_TIMEOUT).isNotNull();
        return frame;
    }

    private static String typeOf(Frame frame) {
        return JsonPath.read(frame.body(), "$.type");
    }

    /** 수신한 프레임 하나. 헤더와 body 를 함께 보관해 봉투가 어디에 담겼는지 판정한다. */
    private record Frame(StompHeaders headers, String body) {
    }

    /** 연결 수준 사건을 모으는 세션 핸들러. MESSAGE 는 구독별 핸들러가 받는다. */
    private static final class Frames extends StompSessionHandlerAdapter {

        private final CompletableFuture<StompHeaders> connectedHeaders = new CompletableFuture<>();
        private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
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

        /** ERROR 프레임만 이 경로로 온다. 원인은 {@code message} 헤더와 body 에 나뉘어 실린다. */
        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            this.errors.add(headers.getFirst(ERROR_MESSAGE_HEADER) + " " + payload);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            this.errors.add(String.valueOf(exception.getMessage()));
        }

        CompletableFuture<StompHeaders> connectedHeaders() {
            return this.connectedHeaders;
        }

        StompHeaders connected() {
            return this.connectedHeaders.getNow(null);
        }

        StompSession session() {
            return this.session;
        }

        String nextError() throws InterruptedException {
            return nextError(ARRIVAL_TIMEOUT);
        }

        String nextError(Duration timeout) throws InterruptedException {
            String error = this.errors.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(error).as("ERROR 프레임이 %s 안에 도착하지 않았다", timeout).isNotNull();
            return error;
        }
    }
}
