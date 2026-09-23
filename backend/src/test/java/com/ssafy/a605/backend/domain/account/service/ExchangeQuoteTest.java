package com.ssafy.a605.backend.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;

/**
 * 부족분 환전액의 계산. 컨테이너 없이 도는 이유는 이것이 순수 계산이기 때문이고, 따로 보는
 * 이유는 <b>여기가 틀리면 조용히 틀리기 때문</b>이다 — 1원·1센트가 모자란 안내는 응답이
 * 200 이고 형태도 옳으며, 사용자가 그 금액으로 환전한 뒤 주문이 다시 막혀야 드러난다.
 */
class ExchangeQuoteTest {

    @Test
    @DisplayName("최소 환전액_명세의 사례이면_역산 공식보다 한 칸 큰 값이 나온다")
    void minimumSendAmount_forTheSpecExample_beatsThePlainFormula() {
        BigDecimal sendAmount = ExchangeQuote.minimumSendAmount(
                ExchangeDirection.KRW_TO_USD, new BigDecimal("1130.23"), new BigDecimal("1243.50"));

        // ord.md 가 드는 사례다. 역산 공식이 주는 1,406,144 원은 실제로 넣으면 $1130.22 가
        // 나와 1센트가 모자란다.
        assertThat(sendAmount).isEqualByComparingTo("1406145");
        assertThat(received(ExchangeDirection.KRW_TO_USD, "1406144", "1243.50"))
                .isEqualByComparingTo("1130.22");
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "KRW_TO_USD, 668.98, 1200.00, 803178",
            "KRW_TO_USD, 1130.23, 1243.50, 1406145",
            "USD_TO_KRW, 8575750, 1200.00, 7150.04",
            "USD_TO_KRW, 1209455, 1210.00, 1000.05"
    })
    @DisplayName("최소 환전액_그 금액을 실제로 환전하면_부족분에 닿고 한 칸 아래는 모자란다")
    void minimumSendAmount_isTheSmallestAmountThatCovers(ExchangeDirection direction, BigDecimal target,
                                                         BigDecimal rate, BigDecimal expected) {
        BigDecimal sendAmount = ExchangeQuote.minimumSendAmount(direction, target, rate);

        assertThat(sendAmount).isEqualByComparingTo(expected);
        assertThat(ExchangeQuote.of(direction, sendAmount, rate).receiveAmount())
                .as("안내한 금액으로 환전하면 부족분이 채워진다")
                .isGreaterThanOrEqualTo(target);
        assertThat(ExchangeQuote.of(direction, oneUnitLess(direction, sendAmount), rate).receiveAmount())
                .as("한 칸 아래로는 채워지지 않는다 = 최소다")
                .isLessThan(target);
    }

    @Test
    @DisplayName("최소 환전액_받는 금액이 통화 자릿수보다 잘면_조용히 답하지 않고 던진다")
    void minimumSendAmount_withTooFineTarget_throws() {
        // 세 칸 검산 안에 만족하는 후보가 없어, 막지 않으면 모자란 금액을 정답인 척 돌려준다.
        assertThatThrownBy(() -> ExchangeQuote.minimumSendAmount(
                ExchangeDirection.KRW_TO_USD, new BigDecimal("1130.235"), new BigDecimal("1243.50")))
                .isInstanceOf(ArithmeticException.class);
    }

    private static BigDecimal received(ExchangeDirection direction, String sendAmount, String rate) {
        return ExchangeQuote.of(direction, new BigDecimal(sendAmount), new BigDecimal(rate))
                .receiveAmount();
    }

    private static BigDecimal oneUnitLess(ExchangeDirection direction, BigDecimal sendAmount) {
        return sendAmount.subtract(BigDecimal.ONE.movePointLeft(ExchangeQuote.sendScale(direction)));
    }
}
