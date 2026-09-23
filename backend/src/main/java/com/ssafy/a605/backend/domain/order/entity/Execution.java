package com.ssafy.a605.backend.domain.order.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ssafy.a605.backend.domain.market.entity.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 체결 기록. 불변이며 복기·시즌 리포트·MDD·승률의 원천이다. 부분 체결이 없으므로
 * 한 주문에 한 행이다.
 *
 * <p>적용 수수료율과 실현 손익을 저장하는 이유는 둘 다 시점에 의존하기 때문이다.
 * 무료 타임에는 요율이 0 이라, 나중에 체결 이력을 재생해 계산하면 그 시점 요율을 알 수 없어
 * 값이 달라진다. 표시용이 아니라 회계값이다.
 */
@Entity
@Table(name = "executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    // 예약에서 온 체결이면 그 주문. 시장가면 null 이다.
    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private OrderSide side;

    @Column(nullable = false)
    private Integer quantity;

    // 체결가 = 그 틱의 종가. 예약이라도 지정가가 아니다.
    @Column(name = "executed_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal executedPrice;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    // 체결가 x 수량. 수수료를 포함하지 않는다.
    @Column(nullable = false, precision = 38, scale = 4)
    private BigDecimal amount;

    @Column(name = "applied_fee_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal appliedFeeRate;

    @Column(nullable = false, precision = 38, scale = 4)
    private BigDecimal fee;

    // 매도만 채워지고 매수는 null 이다.
    @Column(name = "realized_pnl", precision = 38, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "tick_seq", nullable = false)
    private Long tickSeq;

    // 게임 거래일. tick_seq 로 조인해도 얻지만 저장한다 — 체결이 불변이라 갱신 이상이 없고,
    // 편성이 재적재되어 tick_seq 가 다른 거래일을 가리키게 되어도 확정된 기록이 흔들리지 않는다.
    @Column(name = "game_date", nullable = false)
    private LocalDate gameDate;

    // 실제 시각과 게임 시각을 둘 다 기록한다. 전자는 사용자의 체험 순서,
    // 후자는 시장에서 일어난 시점이다.
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    /**
     * @param accountId      체결된 계좌
     * @param instrumentId   체결된 종목
     * @param orderId        예약에서 온 체결이면 그 주문, 시장가면 null
     * @param orderType      시장가인지 예약인지
     * @param side           체결 방향
     * @param quantity       체결 수량
     * @param executedPrice  체결가 (그 틱의 종가)
     * @param currency       결제 통화
     * @param amount         체결 금액
     * @param appliedFeeRate 체결 시점에 실제 적용된 요율(%)
     * @param fee            부과된 수수료
     * @param realizedPnl    실현 손익. 매수면 null
     * @param tickSeq        체결 시점의 틱
     * @param gameDate       체결 시점의 게임 거래일
     * @param executedAt     체결 실제 시각
     */
    public Execution(Long accountId, Long instrumentId, Long orderId, OrderType orderType, OrderSide side,
                     Integer quantity, BigDecimal executedPrice, Currency currency, BigDecimal amount,
                     BigDecimal appliedFeeRate, BigDecimal fee, BigDecimal realizedPnl,
                     Long tickSeq, LocalDate gameDate, Instant executedAt) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.orderId = orderId;
        this.orderType = orderType;
        this.side = side;
        this.quantity = quantity;
        this.executedPrice = executedPrice;
        this.currency = currency;
        this.amount = amount;
        this.appliedFeeRate = appliedFeeRate;
        this.fee = fee;
        this.realizedPnl = realizedPnl;
        this.tickSeq = tickSeq;
        this.gameDate = gameDate;
        this.executedAt = executedAt;
    }
}
