package com.ssafy.a605.backend.domain.ipo.dto;

/**
 * 상장 통보 ({@code ipo.listed}, 기획서 8.5.2). 유니버스가 바뀌는 순간이다.
 *
 * <p>이 메시지와 {@code ipo.disclosed} 를 받으면 프론트는 종목 목록을 재조회한다 — 거래 가능
 * 종목이 늘어나는 지점이 이 둘뿐이다.
 *
 * <p><b>배정 결과를 담지 않는다.</b> 누가 몇 주를 받았는지는 이미 {@code ipo.result} 가
 * 개인에게 전했고, 상장 시점에 바뀌는 것은 <b>포지션</b>이라 계좌 화면이 그것을 갱신한다.
 *
 * @param offeringId   상장한 공모
 * @param instrumentId 이제 거래할 수 있게 된 종목
 */
public record IpoListedResponse(Long offeringId, Long instrumentId) {
}
