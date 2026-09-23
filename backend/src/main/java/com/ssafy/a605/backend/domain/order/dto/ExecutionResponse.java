package com.ssafy.a605.backend.domain.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.Execution;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderType;
import com.ssafy.a605.backend.domain.order.service.OrderQuote;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 체결 한 건 (기획서 6.1.3 · 7.4.1, {@code ord.md}). 불변이며 복기·시즌 리포트·MDD·승률의
 * 원천이다.
 *
 * <p><b>{@code appliedFeeRate} 와 {@code realizedPnl} 은 저장값이다.</b> 매도 요율이 시점에
 * 의존하므로(무료 타임 0%) 나중에 체결 이력을 재생해 계산하면 값이 달라진다
 * ({@code _common.md} §6.3). 표시용이 아니라 회계값이다.
 *
 * <p><b>실제 시각과 게임 시각을 둘 다 싣는다</b> — 기획서 7.4.1 의 명시 요구다. 실제 시각은
 * 사용자의 체험 순서, 게임 시각은 시장에서 일어난 시점이다.
 *
 * @param executionId    체결 식별자
 * @param orderType      이 체결이 시장가에서 왔는지 예약에서 왔는지
 * @param side           체결 방향
 * @param instrumentId   체결된 종목
 * @param quantity       체결 수량
 * @param executedPrice  체결가 = <b>그 틱의 종가</b>. 예약이라도 지정가가 아니다 (6.4.2).
 *                       자릿수는 종목 메타의 {@code priceScale} 을 따른다
 * @param currency       결제 통화
 * @param amount         {@code executedPrice × quantity}. 수수료를 포함하지 않는다
 * @param appliedFeeRate 체결 시점에 실제 적용된 요율(%). <b>매수는 언제나 0</b>
 * @param fee            부과된 수수료. 매수는 언제나 {@code "0"}
 * @param realizedPnl    실현손익. <b>매도만</b> 채워지고 매수는 {@code null}
 * @param gameDate       체결 시점의 게임 거래일. 체결 행이 직접 들고 있다
 * @param tickSeq        체결 시점의 틱
 * @param executedAt     체결 실제 시각
 */
public record ExecutionResponse(
        Long executionId,
        OrderType orderType,
        OrderSide side,
        Long instrumentId,
        Integer quantity,
        String executedPrice,
        Currency currency,
        String amount,
        BigDecimal appliedFeeRate,
        String fee,
        String realizedPnl,
        LocalDate gameDate,
        Long tickSeq,
        Instant executedAt,
        Long orderId
) {

    /**
     * @param execution  저장된 체결
     * @param priceScale 체결가의 표현 자릿수. 종목 메타의 값이다
     * @return 체결 응답
     */
    public static ExecutionResponse of(Execution execution, short priceScale) {
        int scale = OrderQuote.scale(execution.getCurrency());
        return new ExecutionResponse(
                execution.getId(),
                execution.getOrderType(),
                execution.getSide(),
                execution.getInstrumentId(),
                execution.getQuantity(),
                Amounts.of(execution.getExecutedPrice(), priceScale),
                execution.getCurrency(),
                Amounts.of(execution.getAmount(), scale),
                Amounts.ratio(execution.getAppliedFeeRate()),
                Amounts.of(execution.getFee(), scale),
                execution.getRealizedPnl() == null ? null : Amounts.of(execution.getRealizedPnl(), scale),
                execution.getGameDate(),
                execution.getTickSeq(),
                execution.getExecutedAt(), execution.getOrderId());
    }
}
