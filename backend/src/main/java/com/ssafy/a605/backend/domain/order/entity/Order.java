package com.ssafy.a605.backend.domain.order.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
 * 예약(지정가) 주문. 시장가는 미체결 상태를 절대 거치지 않으므로 이 테이블에 들어오지 않고
 * Execution 에만 남는다.
 *
 * <p>체결가는 지정가가 아니라 그 틱의 종가이며, 차이는 항상 사용자에게 유리하다 -
 * 매수는 지정가보다 싸게, 매도는 비싸게 체결된다 (기획서 6.4.2).
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private OrderSide side;

    @Column(name = "limit_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal limitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    // 매수만 채워진다. 지정가 x 수량 - 매수에 수수료가 없어 잠금액이 곧 체결 금액이다
    // (ord.md §수수료). 요율이 등록 시점과 체결 시점으로 갈라질 여지가 없으므로 무료 타임
    // 중 등록도 같은 값이며, 체결가가 지정가 이하라 잠금이 모자랄 수 없다.
    @Column(name = "locked_amount", precision = 38, scale = 4)
    private BigDecimal lockedAmount;

    // 매도만 채워진다. 매수는 현금을, 매도는 수량을 잠그므로 한 컬럼에 합치면 단위가 섞인다.
    @Column(name = "locked_quantity")
    private Integer lockedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderStatus status;

    // 예약의 유효기간은 당일 폐장까지다.
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_tick_seq", nullable = false)
    private Long createdTickSeq;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 체결·취소·만료된 시각. status 가 OPEN 이면 null 이다.
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * 등록 직후의 예약 주문. 등록 시점에는 체결을 판정하지 않는다 - 판정은 새 종가 틱이
     * 도착할 때만 일어나며, 즉시 체결을 원하는 요구는 시장가 주문이 전담한다.
     *
     * @param accountId      주문한 계좌
     * @param instrumentId   대상 종목
     * @param side           주문 방향
     * @param limitPrice     지정가
     * @param quantity       주문 수량
     * @param currency       결제 통화
     * @param lockedAmount   매수 시 잠근 현금. 매도면 null
     * @param lockedQuantity 매도 시 잠근 보유 수량. 매수면 null
     * @param expiresAt      당일 폐장 시각
     * @param createdTickSeq 등록 시점의 틱
     * @param createdAt      등록 실제 시각
     */
    public Order(Long accountId, Long instrumentId, OrderSide side, BigDecimal limitPrice,
                 Integer quantity, Currency currency, BigDecimal lockedAmount, Integer lockedQuantity,
                 Instant expiresAt, Long createdTickSeq, Instant createdAt) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.side = side;
        this.limitPrice = limitPrice;
        this.quantity = quantity;
        this.currency = currency;
        this.lockedAmount = lockedAmount;
        this.lockedQuantity = lockedQuantity;
        this.status = OrderStatus.OPEN;
        this.expiresAt = expiresAt;
        this.createdTickSeq = createdTickSeq;
        this.createdAt = createdAt;
    }

    /**
     * 체결로 닫는다. 잠금 컬럼은 비우지 않는다 — {@code ck_orders_open_locks} 가 살아 있는
     * 잠금만 규정하고 닫힌 행은 열어 두므로, 얼마를 잠갔었는지가 남아 있는 편이 복기에 쓸 데가 있다.
     *
     * @param at 체결 실제 시각. 같은 체결의 {@code executions.executed_at} 과 같은 값이다
     * @throws IllegalStateException 미체결이 아닌 예약을 닫으려 하면
     */
    public void fill(Instant at) {
        close(OrderStatus.FILLED, at);
    }

    /**
     * 사용자 취소로 닫는다 (기획서 6.3.2). 잠긴 현금·수량이 즉시 반환된다.
     *
     * <p>폐장 만료와 <b>상태를 나눠 두는 이유</b>는 둘이 다른 사건이기 때문이다 — 사용자가
     * 마음을 바꾼 것과 시간이 다 된 것은 복기에서 구분되어야 한다 (6.2.4).
     *
     * @param at 취소 실제 시각
     * @throws IllegalStateException 미체결이 아닌 예약을 닫으려 하면
     */
    public void cancel(Instant at) {
        close(OrderStatus.CANCELLED, at);
    }

    /**
     * 폐장 자동 취소로 닫는다 (기획서 6.2.4). 예약의 유효기간은 당일뿐이다.
     *
     * @param at 폐장 실제 시각
     * @throws IllegalStateException 미체결이 아닌 예약을 닫으려 하면
     */
    public void expire(Instant at) {
        close(OrderStatus.EXPIRED, at);
    }

    /**
     * 수량과 지정가를 바꾼다 (기획서 6.3.3). <b>취소 후 재등록과 같은 처리</b>이므로 잠금도 새
     * 조건으로 다시 계산된 값이 들어온다.
     *
     * <p><b>{@code orderId} 는 유지된다.</b> 행을 지우고 새로 만들지 않는 것은 사용자에게
     * 같은 예약이기 때문이다 — 화면의 목록에서 자리가 바뀌지 않아야 하고, 알림이 참조하는
     * 식별자도 살아 있어야 한다 ({@code noti.md} 의 {@code ORDER_EXPIRED} 는
     * {@code orders.id} 를 가리킨다).
     *
     * <p><b>등록 시점 틱은 그대로 둔다.</b> 수정은 새 예약이 아니므로 "언제 걸었나"가 바뀌면
     * 안 되고, 그 값이 바뀌면 당일 판정 기준({@code OrderFillService}) 까지 흔들린다.
     *
     * @param quantity       새 수량
     * @param limitPrice     새 지정가
     * @param lockedAmount   다시 잠근 현금. 매도면 {@code null}
     * @param lockedQuantity 다시 잠근 보유 수량. 매수면 {@code null}
     * @throws IllegalStateException 미체결이 아닌 예약을 고치려 하면
     */
    public void amend(int quantity, BigDecimal limitPrice, BigDecimal lockedAmount,
                      Integer lockedQuantity) {
        if (this.status != OrderStatus.OPEN) {
            throw new IllegalStateException(
                    "미체결이 아닌 예약을 고칠 수 없습니다: order=%d, status=%s".formatted(id, status));
        }
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.lockedAmount = lockedAmount;
        this.lockedQuantity = lockedQuantity;
    }

    /**
     * 상태 전이를 한 곳으로 모은다. {@code OPEN} 이 아닌 행이 다시 닫히면 잠금이 두 번 풀려
     * 현금이 늘어나므로, 그것을 조용히 넘기지 않고 터뜨린다 — 판정 리스너가 같은 틱을 두 번
     * 처리하는 버그가 여기서 드러나야 한다.
     */
    private void close(OrderStatus closed, Instant at) {
        if (this.status != OrderStatus.OPEN) {
            throw new IllegalStateException(
                    "미체결이 아닌 예약을 닫을 수 없습니다: order=%d, status=%s".formatted(id, status));
        }
        this.status = closed;
        this.closedAt = at;
    }
}
