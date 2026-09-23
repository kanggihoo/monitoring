package com.ssafy.a605.backend.global.response;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 응답에 실을 금액·비율의 표현 규칙 ({@code _common.md} §6).
 *
 * <p><b>금액은 문자열이다.</b> JSON number 는 IEEE 754 double 이고, 1억 원대 금액과 소수 달러가
 * 섞이면 반올림 오차가 정산과 랭킹에서 드러난다. 3일차 최종 순위가 부동소수 오차로 뒤바뀌는
 * 것은 이 게임에서 치명적이다. 비율은 number 다.
 *
 * <p>자릿수는 통화가 정한다 — 원화 0, 달러 2, 환율 2, 비율 2. 1주 단위 정수 거래라 소수 원이
 * 발생하지 않으므로 원화를 정수로 내려도 잃는 것이 없다.
 *
 * <p><b>종목 가격만 상수가 아니다.</b> 종목 메타의 {@code priceScale} 을 따르므로 인자로 받는다 —
 * 전부 2자리로 통일하면 MATIC 이 {@code "0.50"} 이 되어 등락이 안 보이고, 전부 4자리로 통일하면
 * 엔비디아가 {@code "875.4300"} 이 된다.
 */
public final class Amounts {

    /** 원화 금액의 자릿수. */
    public static final int KRW_SCALE = 0;

    /** 달러 금액의 자릿수. 항상 채워 보낸다. */
    public static final int USD_SCALE = 2;

    /** 환율의 자릿수. 일별 매매기준율이다. */
    public static final int RATE_SCALE = 2;

    /** 비율(수익률·수수료율·비중)의 자릿수. 퍼센트 값이다. */
    public static final int RATIO_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Amounts() {
    }

    /**
     * 자릿수를 채운 금액 문자열.
     *
     * <p>{@code toPlainString} 이어야 한다 — {@code toString} 은 스케일이 큰 값을 지수 표기로
     * 바꿔 프론트의 파싱을 깨뜨린다.
     *
     * @param value 금액
     * @param scale 표현 자릿수. 통화가 정하거나 종목 메타의 {@code priceScale} 이다
     * @return 자릿수를 채운 문자열
     */
    public static String of(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 기준값 대비 비율(%). 수익률·비중이 모두 이 형태다.
     *
     * <p><b>나눗셈은 표현 자릿수에서 한 번만 한다.</b> 중간 결과를 더 긴 자릿수로 남겨 두면
     * 소수가 두 번 반올림되어 화면의 금액과 비율이 서로를 설명하지 못한다.
     *
     * @param value 분자. 손익이거나 평가액이다
     * @param base  분모. 취득 총액·시작 잔고·총평가액이다. 0 이 아니어야 한다
     * @return 퍼센트 값
     */
    public static BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value.multiply(HUNDRED).divide(base, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 표현 자릿수로 맞춘 비율. 금액과 달리 number 로 나간다.
     *
     * @param value 퍼센트 값. {@code -12.34} 는 -12.34%
     * @return 두 자리로 맞춘 비율
     */
    public static BigDecimal ratio(BigDecimal value) {
        return value.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
