package com.ssafy.a605.backend.domain.account.dto;

import static com.ssafy.a605.backend.global.response.Amounts.RATE_SCALE;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.ssafy.a605.backend.domain.account.entity.Exchange;
import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.service.ExchangeQuote;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 환전 한 건 (기획서 7.2.2 · 7.4.3, {@code acc.md}). 실행 응답과 이력 항목이 같은 형태다.
 *
 * <p>체결 내역과 합치지 않는다 — 환전은 종목·수량·손익이 없고 방향·환율만 있어서 필드가 전혀
 * 겹치지 않는다. 기획서 7.4.3 이 "환전 이력도 내역에 포함한다"고 한 것은 <b>화면</b>에서의
 * 통합이며, 프론트가 두 응답을 시간순으로 병합해 그린다.
 *
 * <p><b>실제 시각과 게임 시각을 둘 다 싣는다.</b> 전자는 사용자의 체험 순서, 후자는 시장에서
 * 일어난 시점이다 (기획서 7.4.1).
 *
 * @param exchangeId    환전 식별자
 * @param direction     환전 방향
 * @param rate          적용 환율
 * @param sendAmount    보내는 통화 기준 금액
 * @param feeRate       적용된 환전 수수료율(%)
 * @param fee           보내는 통화로 부과된 수수료
 * @param receiveAmount 받는 통화 기준 수령액
 * @param gameDate      환전 시점의 게임 거래일
 * @param tickSeq       환전 시점의 틱 순번
 * @param exchangedAt   환전 실제 시각
 */
public record ExchangeResponse(
        Long exchangeId,
        ExchangeDirection direction,
        String rate,
        String sendAmount,
        BigDecimal feeRate,
        String fee,
        String receiveAmount,
        LocalDate gameDate,
        Long tickSeq,
        Instant exchangedAt
) {

    /**
     * @param exchange 저장된 환전
     * @param gameDate 그 {@code tickSeq} 의 게임 거래일. 환전 행은 틱 순번만 갖고 있어
     *                 거래일은 편성에서 얻는다
     * @return 환전 응답
     */
    public static ExchangeResponse of(Exchange exchange, LocalDate gameDate) {
        int sendScale = ExchangeQuote.sendScale(exchange.getDirection());
        return new ExchangeResponse(
                exchange.getId(),
                exchange.getDirection(),
                Amounts.of(exchange.getRate(), RATE_SCALE),
                Amounts.of(exchange.getSendAmount(), sendScale),
                Amounts.ratio(exchange.getFeeRate()),
                Amounts.of(exchange.getFee(), sendScale),
                Amounts.of(exchange.getReceiveAmount(),
                        ExchangeQuote.receiveScale(exchange.getDirection())),
                gameDate,
                exchange.getTickSeq(),
                exchange.getExchangedAt());
    }
}
