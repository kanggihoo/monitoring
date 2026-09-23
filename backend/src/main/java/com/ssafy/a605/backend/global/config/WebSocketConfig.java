package com.ssafy.a605.backend.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.ssafy.a605.backend.global.websocket.ContractedDestinationInterceptor;
import com.ssafy.a605.backend.global.websocket.WsDestination;

/**
 * STOMP over WebSocket 전송 계층. destination 넷에 봉투를 실어 내보내는 통로만 세우고
 * 메시지를 만드는 로직은 갖지 않는다 (ADR-0004).
 *
 * <p><b>핸드셰이크는 인증을 요구하지 않는다</b> (ADR-0005 결정 3). {@code Upgrade} 헤더가 붙은
 * HTTP GET 이므로 기존 필터 체인을 그대로 타지만, {@code SecurityConfig} 가 {@link #ENDPOINT} 를
 * 공개 경로에 넣어 Principal 이 없는 STOMP 세션이 성립한다. <b>그래서 핸드셰이크 인증만으로
 * 보호된다는 전제가 더는 성립하지 않는다</b> — 개인 큐 구독은
 * {@link ContractedDestinationInterceptor} 가 인가로 거부하고, 세션 수명과 연결 수명은
 * {@link com.ssafy.a605.backend.global.websocket.SessionEndedDisconnector} 가 잇는다.
 *
 * <p><b>{@code @MessageMapping} 이 하나도 없다.</b> 애플리케이션 메시지가 전부 서버→클라이언트다
 * ({@code SEND} 프레임을 쓰지 않는다). 채팅 전송조차 REST 다 — 전송은 검증을 동반하고 그 결과를
 * 돌려받아야 하는데 WebSocket 에는 보낸 것과 돌아온 것을 짝지어 주는 장치가 없다.
 *
 * <p><b>메시지 컨버터를 오버라이드하지 않는다.</b> 클래스패스에 Jackson 3({@code tools.jackson})이
 * 있으면 Spring 이 그것을 자동 선택한다. 이 프로젝트에는 {@code spring.jackson.*} 설정도
 * {@code ObjectMapper} 커스터마이징 빈도 없어 REST 와 WS 의 직렬화가 갈릴 자리가 없다.
 * <b>Jackson 설정이 추가되면 이 판단을 재검토한다</b> — 그때는 REST 에만 적용되고 WS 는 기본
 * 컨버터를 쓰게 되어 두 경로의 표현이 갈린다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * WebSocket 엔드포인트. {@code /api/v1/{도메인}/{리소스}} 규칙 밖이며
     * {@code conventions/api.md} 의 예외 경로 표에 명시돼 있다 — <b>전송 채널이지 리소스가 아니다.</b>
     */
    public static final String ENDPOINT = "/ws";

    /**
     * 하트비트 10초. 틱 간격(31~36초)보다 짧아야 <b>죽은 연결</b>과 <b>아직 Tick 이 안 온 것</b>을
     * 구분할 수 있다.
     */
    private static final long[] HEARTBEAT = {10_000L, 10_000L};

    /** 브로커가 맡는 접두사. {@link WsDestination} 의 destination 이 이 아래에 있어야 한다. */
    private static final String TOPIC_PREFIX = "/topic";
    private static final String QUEUE_PREFIX = "/queue";

    private final TaskScheduler messageBrokerTaskScheduler;
    private final List<String> allowedOrigins;

    /**
     * @param messageBrokerTaskScheduler 하트비트를 보낼 스케줄러. 하트비트를 켜면 필수이며 없으면
     *                                   기동에 실패한다. {@code @EnableWebSocketMessageBroker} 가
     *                                   함께 등록하는 프레임워크 빈을 그대로 쓴다 — 우리 빈을 하나 더
     *                                   두면 {@code TaskScheduler} 가 둘이 되어 나중에 붙는
     *                                   {@code @Scheduled} 의 스케줄러 선택이 모호해진다.
     *                                   <b>{@code @Lazy} 가 필수다</b> — 이 스케줄러를 만드는 설정
     *                                   클래스가 이 configurer 를 주입받으므로 순환이 된다.
     * @param allowedOrigins             핸드셰이크를 허용할 출처 목록 (아래 참조). 미설정이면
     *                                   {@code app.base-url} 하나다
     */
    public WebSocketConfig(
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler,
            @Value("${app.allowed-origins}") List<String> allowedOrigins) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * <b>SockJS 를 쓰지 않는다</b> (근거는 ADR-0004).
     *
     * <p><b>허용 출처를 프론트 주소 하나로 명시한다.</b> 기본값(same-origin)으로 두면 두 환경에서
     * 모두 핸드셰이크가 403 으로 끊긴다.
     *
     * <ul>
     * <li>개발 — 프론트는 {@code :3000}, Spring 은 {@code :8080} 이다. rewrite 가 업그레이드 요청을
     *     넘기지 못해 브라우저가 백엔드에 직결한다.
     * <li>배포 — 프레임워크의 same-origin 판정은 {@code Origin} 헤더를 <b>서블릿이 본</b>
     *     scheme·host·port 와 비교하는데, {@code server.forward-headers-strategy} 를 켜지 않았으므로
     *     그 값은 nginx 뒤의 {@code http://localhost:8080} 이다. 브라우저가 보내는
     *     {@code https://<공개 호스트>} 와 절대 같아지지 않는다.
     * </ul>
     *
     * <p>{@code *} 로 열지 않으므로 허용 범위는 넓어지지 않는다. 기본값이 {@code app.base-url} 이고
     * 그것은 OAuth 복귀 주소로도 쓰여 배포에서 이미 정확해야 하는 값이다 — 틀리면 WS 보다 로그인이
     * 먼저 깨진다.
     *
     * <p><b>목록인 이유는 도메인 전환 하나다.</b> 공개 주소를 갈아탈 때 옛 주소와 새 주소가 한동안
     * 같이 열려 있어야, 이미 열어 둔 탭의 실시간 연결이 배포 순간에 끊기지 않는다. 평소에는 한 줄이며
     * 그때만 {@code APP_ALLOWED_ORIGINS} 에 둘을 적는다. <b>OAuth 복귀 주소는 함께 늘릴 수 없다</b> —
     * 성공 핸들러가 보낼 곳은 하나여야 하므로 그쪽은 {@code app.base-url} 하나로 남는다.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                .setAllowedOrigins(this.allowedOrigins.toArray(String[]::new))
                // 세션 종료와 연결 수명을 잇는 데 필요한 세션 ID 하나만 실어 온다
                // (`SessionEndedDisconnector`). **속성은 하나도 복사하지 않는다** — 기본 생성자는
                // 인증 주체까지 WS 속성에 옮겨 담는다. 세션을 새로 만들지도 않는다.
                .addInterceptors(new HttpSessionHandshakeInterceptor(List.of()));
    }

    /**
     * 내장 simple broker 를 쓴다. 외부 릴레이(RabbitMQ·ActiveMQ)를 붙이지 않는다 — relay 가 필요한
     * 것은 여러 인스턴스가 메시지를 나눠 가질 때이고 ADR-0001 이 단일 인스턴스를 전제한다.
     *
     * <p><b>{@code setApplicationDestinationPrefixes} 를 부르지 않는다.</b> 클라이언트가
     * {@code SEND} 를 보내지 않으므로 애플리케이션으로 라우팅할 destination 이 없다. 접두사를 두면
     * 받을 곳 없는 경로가 열린다.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(TOPIC_PREFIX, QUEUE_PREFIX)
                .setHeartbeatValue(HEARTBEAT)
                .setTaskScheduler(this.messageBrokerTaskScheduler);
        registry.setUserDestinationPrefix(WsDestination.USER_PREFIX);
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ContractedDestinationInterceptor());
    }
}
