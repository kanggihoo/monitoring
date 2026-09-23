package com.ssafy.a605.backend.domain.ipo.dto;

import java.time.Instant;

import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 내 청약 한 건 ({@code ipo.md}). 공모 목록·상세의 {@code mySubscription} 이자 신청 응답이다 —
 * 같은 것을 두 형태로 두면 프론트가 두 번 그린다.
 *
 * @param subscribedAt  신청 실제 시각
 * @param depositAmount 잠긴 증거금. 결제 통화 기준이다
 * @param result        추첨 결과. 추첨 전에는 {@code null}
 */
public record IpoSubscriptionResponse(Instant subscribedAt, String depositAmount, Result result) {

    /**
     * 추첨 결과 (기획서 8.4 · 8.5). <b>전부 저장된 값</b>이다 — 추첨은 무작위라 재계산할 수 없고,
     * 배정 수량은 공모가나 편성값이 바뀌면 다시 계산되어 달라진다.
     *
     * @param won               당첨 여부
     * @param allocatedQuantity 배정 수량. 낙첨이면 0
     * @param allocatedAmount   배정 금액 = 수량 x 공모가. 낙첨이면 {@code "0"}
     * @param refundedAmount    반환된 증거금. 낙첨이면 전액
     * @param drawnAt           추첨 실제 시각
     */
    public record Result(boolean won, int allocatedQuantity, String allocatedAmount,
                         String refundedAmount, Instant drawnAt) {
    }

    /**
     * @param subscription 청약
     * @param currency     결제 통화. 금액의 자릿수를 정한다
     * @param drawnAt      그 공모의 추첨 시각. 추첨 전이면 {@code null}
     * @return 자릿수를 채운 청약 응답
     */
    public static IpoSubscriptionResponse of(IpoSubscription subscription, Currency currency,
                                             Instant drawnAt) {
        int scale = currency.scale();
        return new IpoSubscriptionResponse(
                subscription.getSubscribedAt(),
                Amounts.of(subscription.getDepositAmount(), scale),
                result(subscription, scale, drawnAt));
    }

    /**
     * 결과는 <b>청약이 정산됐을 때만</b> 만든다. 공모가 추첨을 마쳤어도 이 청약에 결과가 없으면
     * (스윕이 이 계좌에서 실패한 경우) 결과를 지어내지 않는다 — {@code null} 이 "아직 없다"를
     * 정확히 말한다.
     */
    private static Result result(IpoSubscription subscription, int scale, Instant drawnAt) {
        if (subscription.getWon() == null) {
            return null;
        }
        return new Result(subscription.getWon(), subscription.getAllocatedQuantity(),
                Amounts.of(subscription.getAllocatedAmount(), scale),
                Amounts.of(subscription.getRefundedAmount(), scale), drawnAt);
    }
}
