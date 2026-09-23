package com.ssafy.a605.backend.domain.ipo.dto;

import java.math.BigDecimal;

import com.ssafy.a605.backend.domain.account.dto.ShortfallExchange;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 증거금이 모자라 청약이 거부됐을 때 함께 내려보내는 <b>사용자가 취할 행동</b>
 * ({@code ipo.md}, 기획서 8.2.3). 오류 응답의 {@code data} 가 된다.
 *
 * <p><b>거부 자리에 싣는 것은 청약에 미리보기가 없기 때문</b>이다. 주문은 사용자가 수량과
 * 가격을 고르므로 미리보기가 매 입력마다 같은 값을 성공 응답으로 주지만, 청약은 신청 단위가
 * 고정이라 계산해 볼 것이 없다 — 부족하다는 사실은 <b>신청을 눌러야</b> 알게 된다.
 *
 * <p>거부의 식별은 여전히 상태 코드와 메시지가 한다. 여기 담기는 것은 <i>왜 거부됐는지</i>가
 * 아니라 <i>무엇을 하면 되는지</i>다.
 *
 * @param shortfall         결제 통화 기준 부족분
 * @param shortfallExchange 부족분을 채우는 환전 요청. 프론트는 이 객체를 환전 본문에 그대로 넣는다
 */
public record InsufficientDeposit(String shortfall, ShortfallExchange shortfallExchange) {

    /**
     * @param currency  결제 통화
     * @param shortfall 결제 통화 기준 부족분
     * @param exchange  부족분을 채우는 환전 요청
     * @return 자릿수를 채운 행동 안내
     */
    public static InsufficientDeposit of(Currency currency, BigDecimal shortfall,
                                         ShortfallExchange exchange) {
        return new InsufficientDeposit(Amounts.of(shortfall, currency.scale()), exchange);
    }
}
