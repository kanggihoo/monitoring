package com.ssafy.a605.backend.global.websocket;

import java.time.LocalDate;

/**
 * 실시간 메시지의 봉투. STOMP 헤더는 전송 계층이 쓰고 이것은 <b>body 에 JSON 으로</b> 담긴다
 * ({@code docs/projects/market-replay-api/_common.md} §10).
 *
 * <pre>
 * {"type":"market.prices","tickSeq":137,"gameDate":"2022-04-15","payload":{...}}
 * </pre>
 *
 * <p>{@code tickSeq}·{@code gameDate} 를 STOMP 헤더가 아니라 body 에 두는 이유는 REST 응답과
 * 형태를 맞춰 프론트가 초기 로드와 실시간 수신에 <b>같은 파서</b>를 쓰게 하기 위해서다.
 *
 * <p><b>이 타입은 패키지 밖으로 나가지 않는다.</b> 조립 지점을 {@link RealtimePublisher} 하나로
 * 두는 것이 ADR-0004 의 결정이고, 그것을 주석이 아니라 접근 제한으로 지킨다 — record 의 정규
 * 생성자는 클래스보다 좁게 만들 수 없으므로 클래스 자체를 패키지 전용으로 둔다. 메시지 19종이
 * 각자 조립하면 한 곳에서 {@code gameDate} 를 빠뜨려도 컴파일이 통과하고 프론트에서만 깨진다.
 *
 * @param type     메시지 종류 (예: {@code market.prices})
 * @param tickSeq  이 메시지가 속한 Tick 의 순번. 폐장 자동 취소처럼 어떤 Tick 에도 속하지 않는
 *                 사건이 있으므로 {@code null} 일 수 있다
 * @param gameDate 그 Tick 의 게임 거래일. {@code tickSeq} 와 같은 이유로 {@code null} 일 수 있다
 * @param payload  종류마다 다른 본문
 */
record WsEnvelope(String type, Long tickSeq, LocalDate gameDate, Object payload) {
}
