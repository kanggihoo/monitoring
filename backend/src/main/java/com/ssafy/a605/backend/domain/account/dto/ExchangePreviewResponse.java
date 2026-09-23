package com.ssafy.a605.backend.domain.account.dto;

import static com.ssafy.a605.backend.global.response.Amounts.RATE_SCALE;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.service.ExchangeQuote;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 환전 미리보기 (기획서 7.2.3, {@code acc.md}).
 *
 * <p>이 응답이 답하는 질문은 "이 환전이 가능한가" 하나다. 그래서 원화·달러 잔고를 둘 다
 * 내려주지 않고 <b>보내는 통화의 잠기지 않은 현금 하나만</b> 싣는다 — 네 값
 * ({@code krwCash}·{@code usdCash}·{@code lockedKrw}·{@code lockedUsd})은 계좌 요약이 이미
 * 주고 프론트는 매 틱 그것을 갱신받는다.
 *
 * <p>필드 이름이 {@code availableKrw} 가 아닌 이유: {@code direction} 이 {@code USD_TO_KRW} 면
 * 보내는 통화가 달러라, 이름에 통화를 박으면 값과 어긋난다. 통화는 응답의 {@code direction} 이
 * 말한다. {@code ord.md} 의 주문 미리보기가 같은 성격의 필드를 이미 {@code availableCash} 로
 * 부르고 있어 이름을 맞췄다.
 *
 * <p>환율은 하루 1개 값이므로 같은 거래일 안에서는 언제 환전해도 동일하다 (기획서 7.2 비고).
 * 미리보기와 실제 환전 사이에 환율이 바뀌는 일은 틱이 넘어갈 때만 생긴다.
 *
 * @param direction     환전 방향
 * @param rate          적용 환율 = 현재 게임 거래일의 값
 * @param sendAmount    보내는 통화 기준 금액
 * @param feeRate       환전 수수료율(%). 항상 0.05 이며 무료 타임 대상이 아니다
 * @param fee           보내는 통화로 부과되는 수수료
 * @param receiveAmount 받는 통화 기준 수령액
 * @param availableCash 잠기지 않은 <b>보내는 통화</b> 현금. 통화는 {@code direction} 이 결정한다.
 *                      <b>내림</b>한 값이다 — 이 필드는 "여기까지 보낼 수 있다"는 상한이므로
 *                      반올림하면 표시된 값을 그대로 보냈을 때 잔고를 넘어 거절된다
 */
public record ExchangePreviewResponse(
        ExchangeDirection direction,
        String rate,
        String sendAmount,
        BigDecimal feeRate,
        String fee,
        String receiveAmount,
        String availableCash
) {

    /**
     * @param quote         환전 계산 결과
     * @param availableCash 잠기지 않은 보내는 통화 현금
     * @return 미리보기 응답
     */
    public static ExchangePreviewResponse of(ExchangeQuote quote, BigDecimal availableCash) {
        int sendScale = ExchangeQuote.sendScale(quote.direction());
        return new ExchangePreviewResponse(
                quote.direction(),
                Amounts.of(quote.rate(), RATE_SCALE),
                Amounts.of(quote.sendAmount(), sendScale),
                Amounts.ratio(quote.feeRate()),
                Amounts.of(quote.fee(), sendScale),
                Amounts.of(quote.receiveAmount(), ExchangeQuote.receiveScale(quote.direction())),
                // 상한은 내림이다. 잔고가 표시 자릿수보다 잘게 남을 수 있고(체결가가 소수
                // 여덟 자리까지 간다) 반올림하면 표시값이 실제 잔고보다 커진다.
                Amounts.of(availableCash.setScale(sendScale, RoundingMode.FLOOR), sendScale));
    }
}
