package com.ssafy.a605.backend.global.websocket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.session.events.SessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * 세션이 죽으면 그 세션으로 맺은 WS 연결을 끊는다.
 *
 * <p>핸드셰이크의 Principal 은 연결이 죽을 때까지 바뀌지 않으므로(ADR-0004 결정 4), 서버가 끊지
 * 않으면 <b>로그아웃한 세션의 연결이 개인 메시지를 계속 받는다.</b> 프론트 알림만으로는 개인 데이터
 * 차단을 보장할 수 없다. 같은 이유로 {@code role} 변경 시의 세션 무효화도 여기서 WS 에 도달한다.
 *
 * <p><b>세션 단위로 끊고 사용자 단위로 끊지 않는다.</b> 다른 브라우저·기기의 세션은 영향받지 않는다
 * (ADR-0002 의 "1 유저 n 세션 독립 동작").
 *
 * <p>끊기 전에 {@link #SESSION_ENDED} 를 {@code ERROR} 프레임에 실어 보낸다 ({@code ws.md} §오류).
 * 단순 네트워크 단절과 구분되어야 하기 때문이다 — 클라이언트는 재연결만 할지 미인증으로 전환할지를
 * 이 사유로 판단한다.
 *
 * <p><b>연결을 직접 닫지 않는다.</b> {@code ERROR} 프레임을 클라이언트 아웃바운드 채널로 보내면
 * {@code StompSubProtocolHandler} 가 전송 직후 세션을 닫는다. {@code WebSocketSession} 을 직접 쥐려면
 * 프레임워크가 공개하지 않는 구독 레지스트리를 들여다봐야 한다.
 *
 * <p>미인증 연결은 세션이 없어 이 표에 들어오지 않는다. 그 연결의 수명은 이 경로 밖이다.
 */
@Slf4j
@Component
public class SessionEndedDisconnector {

    /**
     * 세션 종료로 끊었다는 사유. {@code ERROR} 프레임의 {@code message} 헤더에 실린다.
     *
     * <p><b>사람이 읽는 문구가 아니라 계약 토큰이다</b> — 프론트가 문자열로 분기하므로 문구를
     * 다듬으면 그 분기가 조용히 죽는다.
     */
    public static final String SESSION_ENDED = "SESSION_ENDED";

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /** 세션 ID → 그 세션으로 맺은 STOMP 세션 ID. 한 세션이 여러 탭을 가질 수 있다. */
    private final Map<String, Set<String>> stompSessionsBySession = new ConcurrentHashMap<>();

    private final MessageChannel clientOutboundChannel;

    /**
     * @param clientOutboundChannel 서버→클라이언트 프레임이 지나는 채널. 프레임워크 빈을 그대로 쓴다
     */
    public SessionEndedDisconnector(@Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this.clientOutboundChannel = clientOutboundChannel;
    }

    /**
     * <b>{@code SessionConnectedEvent} 가 아니라 {@code SessionConnectEvent} 를 듣는다.</b> 세션 ID 는
     * 핸드셰이크 속성에 실려 오고, 그 속성이 붙어 있는 것은 클라이언트가 보낸 {@code CONNECT} 쪽
     * 메시지다 — {@code CONNECTED} 응답 메시지에는 없다.
     *
     * @param event 도착한 {@code CONNECT} 프레임
     */
    @EventListener
    public void on(SessionConnectEvent event) {
        String sessionId = httpSessionId(event.getMessage().getHeaders());
        String stompSessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId == null || stompSessionId == null) {
            return; // 미인증 연결이다.
        }
        this.stompSessionsBySession
                .computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet())
                .add(stompSessionId);
    }

    /**
     * @param event 끊긴 STOMP 연결
     */
    @EventListener
    public void on(SessionDisconnectEvent event) {
        String sessionId = httpSessionId(event.getMessage().getHeaders());
        if (sessionId == null) {
            return;
        }
        // 마지막 연결이 빠지면 표에서 세션까지 지운다 — 안 지우면 빈 집합이 영구히 쌓인다.
        this.stompSessionsBySession.computeIfPresent(sessionId, (key, stompSessionIds) -> {
            stompSessionIds.remove(event.getSessionId());
            return stompSessionIds.isEmpty() ? null : stompSessionIds;
        });
    }

    /**
     * 로그아웃·유휴 만료·강제 무효화가 모두 이 이벤트로 온다.
     *
     * @param event 소멸한 세션
     */
    @EventListener
    public void on(SessionDestroyedEvent event) {
        // ponytail: 표에서 빼는 것과 등록하는 것 사이에 경쟁이 있다. 핸드셰이크는 끝났지만
        // CONNECT 프레임이 이 시점 뒤에 닿는 연결은 죽은 세션 아래 다시 등록되어 이 종료를
        // 놓친다 — 그 연결은 자기 DISCONNECT 때 정리되므로 표가 무한히 자라지는 않는다.
        // 이미 소멸한 세션으로는 새 핸드셰이크가 성립하지 않으므로(세션 조회가 null 이다)
        // 창은 밀리초 단위이고, 최악의 경우는 로그아웃 직전에 정당하게 인증한 연결 하나다.
        // 닫으려면 최근 소멸 세션 목록과 그 만료 정책이 필요하고 그 값이 이 창보다 크지 않다.
        Set<String> stompSessionIds = this.stompSessionsBySession.remove(event.getSessionId());
        if (stompSessionIds == null) {
            return; // 그 세션으로 맺은 WS 연결이 없다.
        }
        for (String stompSessionId : stompSessionIds) {
            sendSessionEnded(stompSessionId);
        }
    }

    private void sendSessionEnded(String stompSessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(SESSION_ENDED);
        accessor.setSessionId(stompSessionId);
        try {
            this.clientOutboundChannel.send(MessageBuilder.createMessage(EMPTY_PAYLOAD, accessor.getMessageHeaders()));
        } catch (RuntimeException e) {
            // 이미 닫힌 연결이다. 세션이 죽은 것 자체는 되돌릴 수 없으므로 여기서 끝낸다.
            log.debug("Failed to notify STOMP session {} of session end", stompSessionId, e);
        }
    }

    /** 핸드셰이크 인터셉터가 심어 둔 세션 ID. 미인증 핸드셰이크에는 없다. */
    private static String httpSessionId(MessageHeaders headers) {
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(headers);
        return (attributes == null)
                ? null
                : (String) attributes.get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
    }
}
