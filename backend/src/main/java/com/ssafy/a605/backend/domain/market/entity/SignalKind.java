package com.ssafy.a605.backend.domain.market.entity;

import lombok.Getter;

/**
 * 신호등의 세 가지 질문 (기획서 9.2.1).
 *
 * <p><b>질문 문장을 {@code signal_criteria} 컬럼이 아니라 여기 둔다.</b> 그 테이블은
 * {@code UNIQUE (kind, level)} 이라 kind 당 3행이고, 컬럼을 추가하면 같은 문장이 세 번 저장된다.
 * 게다가 세 문장은 기획서 9.2.1 이 못 박은 고정값이지 시즌 편성값이 아니다 — 바뀌면 그건 다른
 * 게임이다. 반면 임계값의 {@code description} 은 편성값이라 테이블에 남는다.
 */
@Getter
public enum SignalKind {
    GROWTH("이 회사는 크고 있나요?"),
    SAFETY("빚이 무겁지 않나요?"),
    VALUATION("지금 비싼 편인가요?");

    private final String question;

    SignalKind(String question) {
        this.question = question;
    }
}
