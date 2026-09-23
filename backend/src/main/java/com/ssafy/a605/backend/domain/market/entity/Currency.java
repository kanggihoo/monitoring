package com.ssafy.a605.backend.domain.market.entity;

import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 결제 통화. 나스닥 종목과 코인은 USD, 국내 종목은 KRW 로 거래한다 (기획서 6장).
 * 종목의 카테고리(화면 탭)와는 다른 관심사라 별도 값으로 둔다.
 */
public enum Currency {
    KRW,
    USD;

    /**
     * 이 통화로 표현한 금액의 소수 자릿수 ({@code _common.md §6}). 원화는 0, 달러는 2 다.
     *
     * <p>자릿수를 아는 것은 통화 자신이지만 그 값은 표현 규약이라 {@link Amounts} 가 갖는다 —
     * {@code global} 은 도메인을 참조하지 않으므로 {@code Amounts} 가 통화를 받을 수는 없고,
     * 그래서 방향을 뒤집어 여기에 둔다. 그러지 않으면 이 삼항식이 도메인마다 복제된다.
     *
     * @return 금액 문자열의 소수 자릿수
     */
    public int scale() {
        return this == USD ? Amounts.USD_SCALE : Amounts.KRW_SCALE;
    }
}
