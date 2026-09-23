package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 환전 한 건의 계산 결과. 미리보기와 실행이 <b>같은 계산</b>을 쓴다 — 두 벌로 두면 미리 보여준
 * 수령액과 실제 수령액이 어긋나고, 그 차이가 곧 "환전했는데 주문이 또 막힌다"가 된다
 * ({@code ord.md} §{@code shortfallExchange}).
 *
 * <p><b>반올림이 두 번 들어간다.</b> 수수료는 보내는 통화 자릿수로 반올림하고, 수령액은 받는
 * 통화 자릿수로 <b>내림</b>한다. 내림인 이유는 {@code ord.md} 가 부족분 환전액을 "실제 환전
 * 계산을 만족하는 최소 정수"로 정의하면서 이 규칙을 전제로 계산하기 때문이다 — 반올림으로
 * 바꾸면 그 문서의 1센트 사례가 성립하지 않는다. 남는 소수가 사용자에게 유리하게 붙지 않는
 * 방향이기도 하다.
 *
 * @param direction     환전 방향
 * @param rate          적용 환율. 그 게임 거래일의 값이며 하루 안에서는 변하지 않는다
 * @param sendAmount    보내는 통화 기준 금액
 * @param feeRate       적용된 수수료율(%)
 * @param fee           보내는 통화로 부과된 수수료
 * @param receiveAmount 받는 통화 기준 수령액
 */
public record ExchangeQuote(
        ExchangeDirection direction,
        BigDecimal rate,
        BigDecimal sendAmount,
        BigDecimal feeRate,
        BigDecimal fee,
        BigDecimal receiveAmount
) {

    /**
     * 환전 수수료율(%). <b>무료 타임 대상이 아니다</b> (기획서 6.7.4) — 거래 수수료만 0 이
     * 되는 것을 사용자가 오해하지 않도록 응답에도 함께 싣는다 ({@code acc.md}).
     */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.05");

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    /**
     * 이론값을 구할 때만 쓰는 중간 자릿수. 답은 실제 계산으로 검산하므로 이 값이 정하는 것은
     * 검산 시작점뿐이다.
     */
    private static final int NET_SCALE = 12;

    /**
     * 보내는 금액과 환율로 한 건을 계산한다.
     *
     * @param direction 환전 방향
     * @param amount    보내는 통화 기준 금액. 그 통화의 자릿수에 맞아야 한다
     * @param rate      그 게임 거래일의 원/달러 매매기준율
     * @return 수수료와 수령액이 채워진 계산 결과
     */
    public static ExchangeQuote of(ExchangeDirection direction, BigDecimal amount, BigDecimal rate) {
        int sendScale = sendScale(direction);
        BigDecimal sendAmount = amount.setScale(sendScale, RoundingMode.UNNECESSARY);
        BigDecimal fee = sendAmount.multiply(FEE_RATE).divide(PERCENT, sendScale, RoundingMode.HALF_UP);
        BigDecimal net = sendAmount.subtract(fee);
        BigDecimal receiveAmount = direction == ExchangeDirection.KRW_TO_USD
                ? net.divide(rate, Amounts.USD_SCALE, RoundingMode.FLOOR)
                : net.multiply(rate).setScale(Amounts.KRW_SCALE, RoundingMode.FLOOR);

        return new ExchangeQuote(direction, rate, sendAmount, FEE_RATE, fee, receiveAmount);
    }

    /**
     * 목표 수령액을 채우는 데 필요한 <b>최소</b> 보내는 금액.
     *
     * <p><b>역산 공식으로 나누기만 해서는 안 된다.</b> 실제 환전에는 반올림이 두 번 들어가기
     * 때문이다 — 수수료는 보내는 통화 자릿수로 반올림하고, 수령액은 받는 통화 자릿수로
     * 내림한다. {@code ord.md} 가 드는 사례가 그것이다: 부족분 $1130.23 · 환율 1243.50 에서
     * 공식이 준 1,406,144 원을 실제로 넣으면 $1130.22 가 나와 1센트가 모자라고, 사용자는
     * "환전했는데 주문이 또 막힌다"를 본다. 그래서 이 값은 공식의 몫이 아니라 <b>실제 계산을
     * 만족하는 최소 금액</b>으로 정의된다.
     *
     * <p>근사한 뒤 검산한다. 검산 폭이 위아래 한 칸인 것은 <b>증명된 범위</b>다 — 수수료
     * 반올림의 오차가 반 칸을 넘지 못하므로 답은 근사값에서 한 칸 안에 있고, 한 칸 위는 항상
     * 충분하다. 그래서 반복도 상한도 필요 없다.
     *
     * @param direction     환전 방향
     * @param receiveAmount 채워야 할 받는 통화 기준 금액. 그 통화의 자릿수에 맞아야 한다
     * @param rate          그 게임 거래일의 원/달러 매매기준율
     * @return 보내는 통화 기준 최소 금액
     * @throws ArithmeticException 받는 금액이 그 통화의 자릿수보다 잘 때
     */
    public static BigDecimal minimumSendAmount(ExchangeDirection direction, BigDecimal receiveAmount,
                                               BigDecimal rate) {
        // 자릿수 전제를 던져서 강제한다 — of() 가 보내는 금액에 대해 하는 것과 같다. 더 잘은
        // 값이 들어오면 검산 세 칸 안에 만족하는 후보가 아예 없어, 모자란 금액을 정답인 척
        // 돌려주게 된다. 조용히 틀리면 안 되는 것을 담당하는 계산이다.
        BigDecimal target = receiveAmount.setScale(receiveScale(direction), RoundingMode.UNNECESSARY);
        BigDecimal approximate = approximateSendAmount(direction, target, rate);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(sendScale(direction));

        BigDecimal lower = approximate.subtract(unit);
        if (lower.signum() > 0 && covers(direction, lower, rate, target)) {
            return lower;
        }
        return covers(direction, approximate, rate, target)
                ? approximate
                : approximate.add(unit);
    }

    /**
     * 반올림을 무시한 이론값. 수수료를 뗀 나머지가 목표 수령액이 되는 금액이며, 올림이라
     * 반올림 오차를 빼면 항상 목표 이상이다.
     */
    private static BigDecimal approximateSendAmount(ExchangeDirection direction,
                                                    BigDecimal receiveAmount, BigDecimal rate) {
        BigDecimal net = direction == ExchangeDirection.KRW_TO_USD
                ? receiveAmount.multiply(rate)
                : receiveAmount.divide(rate, NET_SCALE, RoundingMode.CEILING);
        BigDecimal netRatio = BigDecimal.ONE.subtract(FEE_RATE.movePointLeft(2));
        return net.divide(netRatio, sendScale(direction), RoundingMode.CEILING);
    }

    /** 이 금액을 실제로 환전하면 목표 수령액에 닿는가. 공식이 아니라 실제 계산으로 묻는다. */
    private static boolean covers(ExchangeDirection direction, BigDecimal sendAmount, BigDecimal rate,
                                  BigDecimal receiveAmount) {
        return of(direction, sendAmount, rate).receiveAmount().compareTo(receiveAmount) >= 0;
    }

    /**
     * 보내는 통화의 자릿수. 원화는 0, 달러는 2 다 ({@code _common.md} §6.2).
     *
     * @param direction 환전 방향
     * @return 보내는 통화의 표현 자릿수
     */
    public static int sendScale(ExchangeDirection direction) {
        return direction == ExchangeDirection.KRW_TO_USD ? Amounts.KRW_SCALE : Amounts.USD_SCALE;
    }

    /**
     * 받는 통화의 자릿수. 보내는 통화의 반대다.
     *
     * @param direction 환전 방향
     * @return 받는 통화의 표현 자릿수
     */
    public static int receiveScale(ExchangeDirection direction) {
        return direction == ExchangeDirection.KRW_TO_USD ? Amounts.USD_SCALE : Amounts.KRW_SCALE;
    }
}
