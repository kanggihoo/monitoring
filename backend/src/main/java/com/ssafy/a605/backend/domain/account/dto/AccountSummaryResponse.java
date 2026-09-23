package com.ssafy.a605.backend.domain.account.dto;

import static com.ssafy.a605.backend.global.response.Amounts.KRW_SCALE;
import static com.ssafy.a605.backend.global.response.Amounts.RATE_SCALE;
import static com.ssafy.a605.backend.global.response.Amounts.USD_SCALE;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 계좌 요약 (기획서 7.1, {@code acc.md}).
 *
 * <p>금액을 문자열로 싣는다. JSON number 는 IEEE 754 double 이고, 1억 원대 금액과 소수 달러가
 * 섞이면 반올림 오차가 정산과 랭킹에서 드러난다 ({@code _common.md} §6.1). 비율은 number 다.
 *
 * <p>자릿수는 통화가 정한다 — 원화 0, 달러 2, 환율 2, 비율 2 ({@code _common.md} §6.2).
 * 1주 단위 정수 거래라 소수 원이 발생하지 않으므로 원화를 정수로 내려도 잃는 것이 없다.
 *
 * <p><b>{@code krwCash} 는 잠긴 금액을 포함한 총 현금이다.</b> 주문 가능 금액은 프론트가
 * {@code krwCash - lockedKrw} 로 뺀다 — 기획서 7.2 비고가 두 값을 모두 표시하도록 요구하므로
 * 세 번째 필드를 두는 것보다 낫다.
 *
 * <p><b>{@code asOfGameDate} · {@code asOfTickSeq} · {@code usdKrwRate} 는 최초 공개 전에
 * {@code null} 이다.</b> 셋 다 공개 기준 틱에서 나오는 값이고, 1일차 브리핑에는 공개된 틱이
 * 없다. 게임 시계({@code GameClockSnapshot})가 {@code tickSeq} · {@code gameDate} ·
 * {@code gameMonth} 를 같은 이유로 같은 구간에 {@code null} 로 둔다.
 *
 * @param asOfGameDate          이 응답이 어느 게임 거래일까지 포함하는지. 최초 공개 전에는 {@code null}
 * @param asOfTickSeq           공개 기준 틱 순번. 프론트가 이보다 큰 틱만 이어붙인다. 최초 공개 전에는 {@code null}
 * @param usdKrwRate            원화 환산에 쓴 그 거래일의 매매기준율. 최초 공개 전에는 {@code null}
 * @param totalValuationKrw     총평가액 = 원화 현금 + 달러 현금 x 환율 + 보유 자산 평가액
 * @param krwCash               원화 현금 (잠긴 금액 포함)
 * @param usdCash               달러 현금 (잠긴 금액 포함)
 * @param lockedKrw             예약·청약이 점유한 원화
 * @param lockedUsd             예약·청약이 점유한 달러
 * @param holdingsValuationKrw  보유 자산의 원화 환산 평가액
 * @param startingBalanceKrw    시드 또는 소급 인덱스 잔고. 누적 수익률의 분모
 * @param cumulativeReturn      누적 수익률(%). 환율 변동이 여기 자연히 반영된다
 * @param dailyReturn           일간 수익률(%). 당일 개장 시점 평가액 대비
 */
public record AccountSummaryResponse(
        LocalDate asOfGameDate,
        Long asOfTickSeq,
        String usdKrwRate,
        String totalValuationKrw,
        String krwCash,
        String usdCash,
        String lockedKrw,
        String lockedUsd,
        String holdingsValuationKrw,
        String startingBalanceKrw,
        BigDecimal cumulativeReturn,
        BigDecimal dailyReturn
) {

    private static final BigDecimal ZERO_RATIO = Amounts.ratio(BigDecimal.ZERO);

    /**
     * 공개된 틱을 기준으로 평가한 요약.
     *
     * @param account              계좌
     * @param tick                 공개 기준 틱. 게임 거래일·틱 순번·환율의 출처
     * @param holdingsValuationKrw 보유 자산 원화 평가액
     * @param totalValuationKrw    총평가액
     * @param cumulativeReturn     누적 수익률(%)
     * @param dailyReturn          일간 수익률(%)
     * @return 계좌 요약 응답
     */
    public static AccountSummaryResponse of(Account account, GameTick tick,
                                            BigDecimal holdingsValuationKrw,
                                            BigDecimal totalValuationKrw,
                                            BigDecimal cumulativeReturn,
                                            BigDecimal dailyReturn) {
        return new AccountSummaryResponse(
                tick.getGameDate(),
                tick.getTickSeq(),
                Amounts.of(tick.getUsdKrw(), RATE_SCALE),
                Amounts.of(totalValuationKrw, KRW_SCALE),
                Amounts.of(account.getKrwCash(), KRW_SCALE),
                Amounts.of(account.getUsdCash(), USD_SCALE),
                Amounts.of(account.getLockedKrw(), KRW_SCALE),
                Amounts.of(account.getLockedUsd(), USD_SCALE),
                Amounts.of(holdingsValuationKrw, KRW_SCALE),
                Amounts.of(account.getStartingBalanceKrw(), KRW_SCALE),
                Amounts.ratio(cumulativeReturn),
                Amounts.ratio(dailyReturn));
    }

    /**
     * 최초 공개 전(1일차 브리핑)의 요약. 공개된 종가가 없어 환산할 환율도, 평가할 보유 자산도
     * 없으므로 총평가액은 원화 현금 그 자체이고 두 수익률은 0 이다.
     *
     * <p>호출 측이 달러 현금과 보유 포지션이 비어 있음을 이미 확인한다. 이 구간에는 거래·환전
     * 게이트가 모두 닫혀 있어 정상적으로는 비어 있고, 비어 있지 않다면 평가액을 만들 수 없다.
     *
     * @param account 계좌
     * @return 틱 관련 세 필드가 {@code null} 인 계좌 요약
     */
    public static AccountSummaryResponse beforeFirstPublish(Account account) {
        return new AccountSummaryResponse(
                null,
                null,
                null,
                Amounts.of(account.getKrwCash(), KRW_SCALE),
                Amounts.of(account.getKrwCash(), KRW_SCALE),
                Amounts.of(account.getUsdCash(), USD_SCALE),
                Amounts.of(account.getLockedKrw(), KRW_SCALE),
                Amounts.of(account.getLockedUsd(), USD_SCALE),
                Amounts.of(BigDecimal.ZERO, KRW_SCALE),
                Amounts.of(account.getStartingBalanceKrw(), KRW_SCALE),
                ZERO_RATIO,
                ZERO_RATIO);
    }

}
