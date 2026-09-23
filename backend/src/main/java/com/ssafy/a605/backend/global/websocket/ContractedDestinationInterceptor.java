package com.ssafy.a605.backend.global.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * 계약에 없는 destination 구독을 거부하고, 개인 큐 구독에 인증을 요구한다
 * ({@code ws.md} §오류 — "알 수 없는 destination 구독 → {@code ERROR} + 연결 종료").
 *
 * <p><b>인증용 인터셉터가 아니다.</b> 인증은 핸드셰이크에서 끝나며 그 경로에는 새 코드가 없다
 * (ADR-0004 결정 4). 여기서 더 보는 것은 <b>인가</b>다 — 미인증 연결이 허용되면서
 * (ADR-0005 결정 3) Principal 이 없는 세션이 처음으로 생겼고, 그 세션이 개인 큐를 구독할 수
 * 있어서는 안 된다.
 *
 * <p>거부하지 않으면 오타 난 구독이 브로커 접두사({@code /topic})에 걸려 <b>정상 등록된다.</b>
 * 그러면 메시지가 오지 않는다는 사실만 남고 이유는 어디에도 나타나지 않아, 프론트가 "왜 안 오지"로
 * 디버깅을 길게 한다.
 *
 * <p><b>미인증의 개인 큐 구독도 같은 모양으로 조용히 성공한다</b> — 관측 결과다. 계약에 있는
 * destination 이라 이 검사를 통과하고, 브로커는 세션별 경로로 바꿔 등록하므로 남의 메시지가
 * 새지는 않지만 아무것도 도착하지 않는다. 거부가 필요한 이유는 유출이 아니라 그 침묵이다 —
 * 프론트 분기만으로 개인 데이터 차단을 보장하지 않는다는 요구도 이 검사가 받는다.
 */
public class ContractedDestinationInterceptor implements ChannelInterceptor {

    /**
     * 계약 위반 거부의 사유 접두사. <b>프론트가 "재시도로 고쳐지지 않는 오류" 를 가르는 유일한
     * 근거다</b> ({@code lib/ws.ts} 의 같은 이름 상수).
     *
     * <p>접두사를 두는 이유는 분류를 문구에서 떼어 놓기 위해서다. 프론트가 한국어 문장을 매칭하면
     * 여기서 문장을 다듬는 순간 조용히 분류를 놓치고, 고쳐지지 않을 오류에 재연결을 계속 건다.
     */
    public static final String CONTRACT_VIOLATION = "CONTRACT_VIOLATION";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        String destination = accessor.getDestination();
        if (!WsDestination.contracted().contains(destination)) {
            // 이 예외는 STOMP ERROR 프레임이 되어 클라이언트로 나간다. 클라이언트가 스스로 보낸
            // 문자열만 되돌려 주므로 내부 구현이 노출되지 않는다.
            throw new MessagingException(message,
                    CONTRACT_VIOLATION + ": 계약에 없는 destination 입니다: " + destination);
        }
        if (WsDestination.PERSONAL.equals(destination) && accessor.getUser() == null) {
            throw new MessagingException(message,
                    CONTRACT_VIOLATION + ": 로그인이 필요한 destination 입니다: " + destination);
        }
        return message;
    }
}
