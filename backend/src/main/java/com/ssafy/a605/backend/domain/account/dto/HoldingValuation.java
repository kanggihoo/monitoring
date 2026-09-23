package com.ssafy.a605.backend.domain.account.dto;

import java.math.BigDecimal;

/**
 * 한 틱 기준으로 평가한 보유 한 줄. 일차 폐장의 마감 보유 스냅샷이 이것을 그대로 저장한다
 * ({@code daily_holdings}, V42).
 *
 * <p><b>{@link AccountValuation} 과 달리 계좌 단위가 아니라 종목 단위다.</b> 스냅샷은 그
 * 일차에 <i>무엇을</i> 들고 끝냈는지를 남기는 것이고, 합계는 정산 행이 이미 들고 있다.
 *
 * @param accountId    보유 계좌
 * @param instrumentId 보유 종목
 * @param quantity     보유 수량. 1 이상이다
 * @param totalCost    취득 총액 (종목의 결제 통화)
 * @param valuationKrw 그 틱의 종가·환율로 평가한 원화 금액 (반올림 전)
 */
public record HoldingValuation(
        Long accountId,
        Long instrumentId,
        Integer quantity,
        BigDecimal totalCost,
        BigDecimal valuationKrw
) {
}
