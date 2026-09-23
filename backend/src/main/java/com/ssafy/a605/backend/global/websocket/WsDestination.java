package com.ssafy.a605.backend.global.websocket;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 계약이 정한 브로드캐스트 destination. 문자열을 흘리지 않기 위해 열거값으로 고정한다
 * ({@code docs/projects/market-replay-api/ws.md}).
 *
 * <p>타입마다 destination 을 만들지 않았다. {@code /topic/tick} 하나에 네 종류가 흐르며,
 * 프론트는 그것을 봉투의 {@code type} 으로 가른 뒤 {@code tickSeq} 로 묶어 한 번에 그린다.
 * 그래서 봉투에 {@code type} 이 남아 있다 (→ {@link WsEnvelope}).
 */
public enum WsDestination {

    /** 매 틱 브로드캐스트. */
    TICK("/topic/tick"),

    /** 비주기 브로드캐스트 (phase 전환·IPO 공시·랭킹 확정 등). */
    EVENT("/topic/event"),

    /** 채팅. 게임 시계와 무관하게 사람이 말할 때마다 온다. */
    CHAT("/topic/chat");

    /** 개인 destination 의 접두사. Spring 이 이 아래에 세션별 경로를 만든다. */
    public static final String USER_PREFIX = "/user";

    /** 접두사를 뗀 나머지. 발행은 이 부분만 넘기고 Spring 이 userId 를 끼워 넣는다. */
    static final String PERSONAL_SUFFIX = "/queue/me";

    /**
     * 개인 메시지의 구독 destination. 열거값에 넣지 않는 이유는 <b>발행 방법이 다르기</b> 때문이다 —
     * 브로드캐스트는 destination 으로 보내고, 개인 메시지는 userId 로 보낸다
     * (→ {@link RealtimePublisher#sendToUser}). 구독 문자열은 모든 사용자가 같고 도착하는 내용만 갈린다.
     *
     * <p>두 조각에서 조립한다. 따로 적으면 접두사를 바꿀 때 한쪽만 고쳐 라우팅이 조용히 어긋난다.
     */
    public static final String PERSONAL = USER_PREFIX + PERSONAL_SUFFIX;

    private static final Set<String> CONTRACTED = Stream.concat(
                    Arrays.stream(values()).map(WsDestination::path), Stream.of(PERSONAL))
            .collect(Collectors.toUnmodifiableSet());

    private final String path;

    WsDestination(String path) {
        this.path = path;
    }

    /**
     * @return STOMP destination 문자열
     */
    public String path() {
        return this.path;
    }

    /**
     * 계약이 허용하는 구독 destination 전부 — 브로드캐스트 셋과 개인 하나.
     *
     * <p>이 밖의 구독은 거부한다 (→ {@link ContractedDestinationInterceptor}).
     *
     * @return 구독 가능한 destination 문자열 집합
     */
    public static Set<String> contracted() {
        return CONTRACTED;
    }
}
