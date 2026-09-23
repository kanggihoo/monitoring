package com.ssafy.a605.backend.domain.account.dto;

import java.math.BigDecimal;

import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.service.ExchangeQuote;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 부족분을 채우는 환전 요청. 프론트는 이 객체를 {@code POST /exchanges} 본문에 그대로 넣는다 —
 * 두 필드의 이름과 의미가 그 요청과 같다.
 *
 * <p><b>{@code sendAmount} 는 보내는 통화 기준이다.</b> 부족분은 받는 통화(부족한 쪽) 기준이라
 * 프론트가 역산해야 하는데, 실제 환전에는 반올림이 두 번 들어가 역산과 결과가 어긋난다. 그래서
 * 서버가 <b>실제 환전 계산을 만족하는 최소 금액</b>을 한 번만 계산해 준다 ({@code ord.md}).
 *
 * <p><b>계좌 도메인에 둔 것은 이것이 환전의 언어이기 때문이다.</b> 부족분을 알리는 곳은 주문
 * 미리보기({@code ord.md})와 청약 신청({@code ipo.md}) 둘인데, 둘 다 "환전으로 채우라"는 같은
 * 말을 한다. 어느 한쪽 도메인에 두면 나머지가 그 도메인을 들여다보게 된다.
 *
 * @param direction  환전 방향. 부족한 통화를 받는 방향이다
 * @param sendAmount 보내는 통화 기준 필요액. 환전 수수료를 포함한다
 */
public record ShortfallExchange(ExchangeDirection direction, String sendAmount) {

    /**
     * @param direction  환전 방향
     * @param sendAmount 보내는 통화 기준 필요액
     * @return 자릿수를 채운 환전 요청
     */
    public static ShortfallExchange of(ExchangeDirection direction, BigDecimal sendAmount) {
        return new ShortfallExchange(direction,
                Amounts.of(sendAmount, ExchangeQuote.sendScale(direction)));
    }
}
