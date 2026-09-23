package com.ssafy.a605.backend.domain.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;

/**
 * 주문 한 건의 금액 계산 (기획서 6.1.3 · 6.6). <b>미리보기와 체결이 이것 하나를 쓴다</b> —
 * 규칙이 두 벌이면 "예상 2,630.23 달러"를 보고 누른 주문이 다른 금액으로 체결된다.
 *
 * <p><b>금액을 결제 통화 자릿수로 확정한다.</b> 기준가는 종목마다 자릿수가 다르므로(MATIC 은
 * 네 자리) 수량을 곱하면 센트보다 잘은 소수가 남는데, 그것을 그대로 두면 화면에 보인 금액과
 * 실제로 빠져나가는 금액이 갈라지고 잔고에 먼지가 쌓인다. 여기서 한 번 맞춰 두면
 * {@code 총액 = 금액 ± 수수료} 가 표시된 값끼리도 성립한다.
 *
 * <p><b>수수료는 매도에만 붙는다.</b> 매수는 언제나 0 이므로 무료 타임(12:00~12:10)도 매도에만
 * 의미가 있고, 그 판단은 게임 시계의 {@code feeFree} 하나뿐이다 ({@code _common.md} §7.2).
 *
 * <p>이 규칙 덕분에 <b>예약 매수가 잠글 금액이 곧 체결 금액</b>이 된다. 수수료 몫을 더 잠글
 * 것이 없으므로 등록 시점 요율과 체결 시점 요율이 갈라질 여지 자체가 없고, 미리보기가
 * 안내하는 부족분도 실제로 막히는 문턱과 항상 같다.
 *
 * @param currency       결제 통화
 * @param referencePrice 기준가. 시장가는 공개 기준 종가, 예약은 지정가다
 * @param amount         체결 예정 금액 = 기준가 x 수량. 수수료를 포함하지 않는다
 * @param feeRate        적용된 요율(%). <b>매수는 언제나 0</b> 이고, 매도는 무료 타임이면 0 이다
 * @param fee            수수료. 매수는 언제나 0
 * @param totalAmount    매수는 금액과 같고, 매도는 {@code 금액 - 수수료}
 */
public record OrderQuote(
        Currency currency,
        BigDecimal referencePrice,
        BigDecimal amount,
        BigDecimal feeRate,
        BigDecimal fee,
        BigDecimal totalAmount
) {

    /** 거래 수수료율(%). <b>매도에만 부과한다</b> — 매수는 언제나 0 이다. */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.15");

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    /**
     * 기준가와 수량으로 한 건을 계산한다.
     *
     * @param side           주문 방향. 수수료가 붙는지를 이것이 정한다
     * @param currency       결제 통화
     * @param referencePrice 기준가
     * @param quantity       주문 수량. 1 이상이어야 한다
     * @param feeFree        무료 타임 진행 중인가. 매수에는 영향이 없다
     * @return 금액·수수료·총액이 채워진 계산 결과
     */
    public static OrderQuote of(OrderSide side, Currency currency, BigDecimal referencePrice,
                                int quantity, boolean feeFree) {
        int scale = scale(currency);
        BigDecimal amount = referencePrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(scale, RoundingMode.HALF_UP);
        // 수수료는 매도에만 붙는다. 매수가 언제나 0 이라 무료 타임도 매도에만 의미가 있다.
        BigDecimal feeRate = side == OrderSide.SELL && !feeFree ? FEE_RATE : BigDecimal.ZERO;
        BigDecimal fee = amount.multiply(feeRate).divide(PERCENT, scale, RoundingMode.HALF_UP);
        BigDecimal totalAmount = side == OrderSide.BUY ? amount : amount.subtract(fee);

        return new OrderQuote(currency, referencePrice, amount, feeRate, fee, totalAmount);
    }

    /**
     * 결제 통화의 금액 자릿수. 원화는 0, 달러는 2 다 ({@code _common.md} §6.2).
     *
     * <p>값은 {@link Currency#scale()} 이 갖는다. 호출부가 여럿이라 이름만 남겨 둔다.
     *
     * @param currency 결제 통화
     * @return 그 통화의 표현 자릿수
     */
    public static int scale(Currency currency) {
        return currency.scale();
    }
}
