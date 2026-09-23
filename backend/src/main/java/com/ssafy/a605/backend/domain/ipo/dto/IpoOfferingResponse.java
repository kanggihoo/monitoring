package com.ssafy.a605.backend.domain.ipo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOfferingStatus;
import com.ssafy.a605.backend.domain.market.entity.Currency;

/**
 * 공시된 공모 하나 (기획서 8.1 · 8.3, {@code ipo.md}).
 *
 * <p><b>목록과 상세가 같은 타입이다.</b> 상세는 "목록 항목의 모든 필드 + 공시 본문"이라
 * 필드를 두 벌 두면 열넷이 복제된다. 목록에만 없는 둘({@code summary}·{@code allocationRule})은
 * 값이 없을 때 실리지 않는다 — 목록에서 {@code null} 로 비워 보내면 "본문이 없는 공모"로 읽힌다.
 *
 * <p><b>경쟁률과 당첨 확률을 둘 다 서버가 계산한다.</b> 역수 관계지만 프론트가 한쪽을 뒤집으면
 * 반올림이 갈려 "경쟁률 3.32:1인데 확률 30.1%"처럼 서로 안 맞는 두 숫자가 한 화면에 뜬다.
 *
 * @param offeringId           공모 식별자
 * @param instrumentId         공모 대상 종목. 상장 전에는 거래할 수 없다
 * @param name                 종목명. <b>공시 시점에 공개된다</b> (기획서 3.5.2)
 * @param status               지금의 국면
 * @param currency             결제 통화. 증거금이 잠기는 지갑을 정한다
 * @param offerPrice           공모가. 자릿수는 종목의 {@code priceScale} 을 따른다
 * @param depositAmount        청약 단위 = 증거금 100%. 시즌 편성값이다
 * @param listingGameDate      상장 예정 게임 거래일 (8.1.1)
 * @param subscriptionClosesAt 청약 마감 실제 시각. <b>오늘 편성이 아니거나 재생이 정지 중이면
 *                             {@code null}</b> — 앵커가 진행 중인 일차 하나만 갖기 때문이다
 * @param listedAt             상장 실제 시각. 같은 이유로 {@code null} 일 수 있다
 * @param subscriberCount      현재 청약자 수 (8.3.1)
 * @param expectedWinnerCount  당첨 예정 인원. 청약자 수의 30%(반올림, 최소 1)이며 청약자가
 *                             없으면 0 이다
 * @param competitionRate      경쟁률 = 청약자 수 / 당첨 예정 인원. 청약자가 없으면 {@code null}
 * @param winProbability       예상 당첨 확률(%). 청약자가 없으면 {@code null}
 * @param mySubscription       내 청약. 신청하지 않았으면 {@code null}
 * @param summary              사업 요약 (8.1.1). 상세에만 실린다
 * @param allocationRule       당첨 인원 산정 방식의 <b>문장</b> (8.1.1). 상세에만 실린다 —
 *                             비율이 편성값이라 숫자로 주면 편성이 바뀔 때 프론트를 고쳐야 한다
 */
public record IpoOfferingResponse(
        Long offeringId,
        Long instrumentId,
        String name,
        IpoOfferingStatus status,
        Currency currency,
        String offerPrice,
        String depositAmount,
        LocalDate listingGameDate,
        Instant subscriptionClosesAt,
        Instant listedAt,
        int subscriberCount,
        int expectedWinnerCount,
        BigDecimal competitionRate,
        BigDecimal winProbability,
        IpoSubscriptionResponse mySubscription,
        @JsonInclude(JsonInclude.Include.NON_NULL) String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) String allocationRule
) {
}
