package com.ssafy.a605.backend.domain.order.dto;

import java.math.BigDecimal;

import com.ssafy.a605.backend.domain.account.dto.ShortfallExchange;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.service.OrderQuote;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 주문 미리보기 (기획서 6.1.3 · 6.5.4, {@code ord.md}). 주문 화면이 사용자 입력마다 부르며
 * 어떤 상태도 바꾸지 않는다.
 *
 * <p>이 응답이 답하는 질문은 "이 주문이 지금 가능한가, 얼마가 드는가" 둘이다. 그래서 잔고
 * 네 값을 다시 싣지 않고 <b>결제 통화의 잠기지 않은 현금 하나만</b> 담는다 — 나머지는 계좌
 * 요약이 이미 주고 프론트는 매 틱 그것을 갱신받는다.
 *
 * <p><b>예약 매도의 잠긴 수량은 여기 없다.</b> 그 값은 포지션 응답에 있으므로 중복하지
 * 않는다 ({@code acc.md}).
 *
 * @param currency          결제 통화. 나스닥·코인 {@code USD}, 국내 {@code KRW}
 * @param referencePrice    기준가. 시장가는 현재 종가, 예약은 지정가다. 자릿수는 종목 메타의
 *                          {@code priceScale} 을 따른다
 * @param estimatedAmount   예상 체결 금액 = 기준가 x 수량
 * @param appliedFeeRate    적용 요율(%). <b>매수는 언제나 0</b> 이고, 매도는 무료 타임이면 0 이다 —
 *                          수수료는 매도에만 부과한다
 * @param estimatedFee      예상 수수료. 매수는 언제나 {@code "0"}
 * @param totalAmount       매수는 금액과 같고, 매도는 {@code 금액 - 수수료}
 * @param availableCash     잠기지 않은 <b>결제 통화</b> 현금. <b>내림</b>한 값이다 — 이 필드는
 *                          "여기까지 쓸 수 있다"는 상한이므로 반올림하면 표시된 값으로는 낼 수
 *                          없는 주문이 가능해 보인다
 * @param shortfall         결제 통화 기준 부족분. 부족하지 않으면 {@code "0"} 이고 매도는 항상
 *                          {@code "0"} 이다
 * @param shortfallExchange 부족분을 채우는 환전 요청. 부족하지 않으면 {@code null}
 * @param feeFree           무료 타임 진행 중인가
 */
public record OrderPreviewResponse(
        Currency currency,
        String referencePrice,
        String estimatedAmount,
        BigDecimal appliedFeeRate,
        String estimatedFee,
        String totalAmount,
        String availableCash,
        String shortfall,
        ShortfallExchange shortfallExchange,
        boolean feeFree
) {

    /**
     * @param quote             주문 계산 결과
     * @param priceScale        기준가의 표현 자릿수. 종목 메타의 값이다
     * @param availableCash     잠기지 않은 결제 통화 현금. 이미 통화 자릿수로 내림한 값이다
     * @param shortfall         결제 통화 기준 부족분. 부족하지 않으면 0
     * @param shortfallExchange 부족분을 채우는 환전 요청. 부족하지 않으면 {@code null}
     * @param feeFree           무료 타임 진행 중인가
     * @return 미리보기 응답
     */
    public static OrderPreviewResponse of(OrderQuote quote, short priceScale, BigDecimal availableCash,
                                          BigDecimal shortfall, ShortfallExchange shortfallExchange,
                                          boolean feeFree) {
        int scale = OrderQuote.scale(quote.currency());
        return new OrderPreviewResponse(
                quote.currency(),
                Amounts.of(quote.referencePrice(), priceScale),
                Amounts.of(quote.amount(), scale),
                Amounts.ratio(quote.feeRate()),
                Amounts.of(quote.fee(), scale),
                Amounts.of(quote.totalAmount(), scale),
                Amounts.of(availableCash, scale),
                Amounts.of(shortfall, scale),
                shortfallExchange,
                feeFree);
    }
}
