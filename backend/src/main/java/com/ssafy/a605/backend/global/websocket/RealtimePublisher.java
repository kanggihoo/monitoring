package com.ssafy.a605.backend.global.websocket;

import java.time.LocalDate;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 봉투를 만들어 내보내는 <b>유일한</b> 지점. 도메인 서비스들은 이것만 호출한다.
 *
 * <p>추상화를 씌우는 것이 아니라 반복을 막는 것이다 — 봉투를 바꾸면 프론트 전체와 재접속 동기화가
 * 함께 깨지므로, 그 구조물의 생성 지점이 메시지 19종만큼 늘어나서는 안 된다 (ADR-0004).
 *
 * <p>원시 타입과 payload 객체만 받는다. 도메인 타입을 인자로 받으면 {@code global → domain}
 * 역참조가 되어 계층 방향이 뒤집힌다 ({@code docs/architecture/overview.md} 3절).
 */
@Component
@RequiredArgsConstructor
public class RealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 재생 실행의 <b>커밋된</b> 결과만 호출 측에서 전달한다. 기존 비재생 메시지와 전송 통로를
     * 공유하되 봉투에 {@code replayEpoch} 가 더 붙는다 — 재생 세대가 바뀌면 클라이언트가
     * 이전 세대의 메시지를 버릴 수 있어야 하고, 그 필드는 재생 메시지에만 의미가 있다.
     *
     * @param destination 보낼 destination. 가격은 tick, 그 밖은 event 다
     * @param type        메시지 종류 ({@code market.prices} · {@code game.phase} · {@code game.clock})
     * @param tickSeq     공개 기준 틱. 시계 조작처럼 Tick 에 속하지 않는 사건은 {@code null}
     * @param gameDate    그 틱의 게임 거래일. {@code tickSeq} 와 같은 이유로 {@code null} 일 수 있다
     * @param replayEpoch 재생 실행 세대
     * @param payload     종류마다 다른 본문
     */
    public void broadcastReplay(WsDestination destination, String type, Long tickSeq, LocalDate gameDate,
            long replayEpoch, Object payload) {
        messagingTemplate.convertAndSend(destination.path(), new ReplayEnvelope(type, tickSeq, gameDate, replayEpoch, payload));
    }

    /**
     * 재생 메시지의 봉투. {@link WsEnvelope} 에 {@code replayEpoch} 를 더한 모양이며, 조립 지점을
     * {@link RealtimePublisher} 하나로 두는 ADR-0004 의 결정은 그대로다. 세대를 공통 봉투에
     * 올리지 않는 이유는 재생과 무관한 19종 메시지가 뜻 없는 값을 싣게 되기 때문이다.
     */
    private record ReplayEnvelope(String type, Long tickSeq, LocalDate gameDate, long replayEpoch, Object payload) { }

    /**
     * 접속한 전원에게 보낸다.
     *
     * @param destination 보낼 destination
     * @param type        메시지 종류 (예: {@code market.prices})
     * @param tickSeq     이 메시지가 속한 Tick 의 순번. Tick 에 속하지 않는 사건이면 {@code null}
     * @param gameDate    그 Tick 의 게임 거래일. Tick 에 속하지 않는 사건이면 {@code null}
     * @param payload     종류마다 다른 본문
     */
    public void broadcast(WsDestination destination, String type, Long tickSeq, LocalDate gameDate, Object payload) {
        messagingTemplate.convertAndSend(destination.path(), new WsEnvelope(type, tickSeq, gameDate, payload));
    }

    /**
     * 한 User 에게만 보낸다. 라우팅 키는 인증 주체의 이름이고 그것이 이미
     * {@code String.valueOf(userId)} 이므로 ({@code LoginUser.getName()}) 호출자는 userId 만 알면 된다.
     *
     * @param userId   받을 User 의 식별자
     * @param type     메시지 종류 (예: {@code account.valuation})
     * @param tickSeq  이 메시지가 속한 Tick 의 순번. Tick 에 속하지 않는 사건이면 {@code null}
     * @param gameDate 그 Tick 의 게임 거래일. Tick 에 속하지 않는 사건이면 {@code null}
     * @param payload  종류마다 다른 본문
     */
    public void sendToUser(Long userId, String type, Long tickSeq, LocalDate gameDate, Object payload) {
        messagingTemplate.convertAndSendToUser(String.valueOf(userId), WsDestination.PERSONAL_SUFFIX,
                new WsEnvelope(type, tickSeq, gameDate, payload));
    }
}
