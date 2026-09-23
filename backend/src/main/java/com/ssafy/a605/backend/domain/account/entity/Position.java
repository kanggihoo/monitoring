package com.ssafy.a605.backend.domain.account.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;

import com.ssafy.a605.backend.global.exception.BusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보유 포지션. 공매도가 없으므로 quantity 는 항상 0 이상이다.
 *
 * <p>평균 단가를 저장하지 않는다. 100 달러를 3주로 나누면 33.3333... 이고, 이 나눗셈 결과를
 * 저장한 채 매매를 반복하면 오차가 누적되어 전량 매도 후에도 잔고가 미세하게 남는다.
 * 그래서 취득 총액과 수량만 저장하고 평단은 응답을 만들 때 나눈다.
 * 수량이 0 이 되면 취득 총액도 0 으로 강제해야 한다.
 */
@Entity
@Table(name = "positions")
@IdClass(Position.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    /** 취득 총액의 자릿수. 저장 컬럼이 {@code NUMERIC(38, 4)} 다. */
    private static final int COST_SCALE = 4;

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    // 1주 단위 정수. 소수 보유가 없다.
    @Column(nullable = false)
    private Integer quantity;

    // 취득 총액(현지 통화). 추가 매수는 여기에 더하기만 하므로 나눗셈이 없고,
    // 이동평균법이 자연히 성립한다.
    @Column(name = "total_cost", nullable = false, precision = 38, scale = 4)
    private BigDecimal totalCost;

    /**
     * @param accountId    보유 계좌
     * @param instrumentId 보유 종목
     * @param quantity     보유 수량
     * @param totalCost    취득 총액 (현지 통화)
     */
    public Position(Long accountId, Long instrumentId, Integer quantity, BigDecimal totalCost) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.totalCost = totalCost;
    }

    /**
     * 추가 매수를 반영한다. 수량과 취득 총액을 더하기만 하므로 <b>나눗셈이 없고 이동평균법이
     * 자연히 성립한다</b> (기획서 7.3.3).
     *
     * <p><b>보유 수량이 {@code int} 라 상한이 있다.</b> 코인은 단가가 $0.06 대까지 내려가고
     * 1주 단위 정수 거래라, 자산이 커지면 한 종목에 21억 주를 넘게 담을 수 있다. 그대로 더하면
     * 조용히 감싸 돌아 음수가 되고, DB 의 {@code ck_positions_cost_matches_quantity} 가 그때야
     * 거부한다 — 체결이 500 으로 죽고 원인은 제약 위반으로만 남는다. 여기서 먼저 막아
     * 무엇이 한계인지 말한다. 매수 경로 둘(시장가·예약)이 모두 이 메서드를 지난다.
     *
     * @param quantity 체결 수량
     * @param cost     체결 금액 (현지 통화). 매수에는 수수료가 없어 나간 현금과 같다
     * @throws BusinessException 한 종목 보유 수량이 {@code int} 상한을 넘을 때 (409)
     */
    public void add(int quantity, BigDecimal cost) {
        try {
            this.quantity = Math.addExact(this.quantity, quantity);
        } catch (ArithmeticException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "한 종목에 보유할 수 있는 수량을 넘었습니다. 수량을 나누어 주문하세요.");
        }
        this.totalCost = totalCost.add(cost);
    }

    /**
     * 매도한 수량을 덜어내고 그만큼의 취득 총액을 돌려준다. <b>이 게임의 나눗셈은 여기 하나만
     * 산다</b> — 평단을 유지하려면 취득 총액을 비례 차감해야 하고 (기획서 7.3.3), 그 비율이
     * 유일한 나눗셈이다.
     *
     * <p><b>전량 매도는 나누지 않고 남은 것을 그대로 털어낸다.</b> 비율로 계산하면 반올림
     * 잔차가 남아 "전량 매도 후에도 취득 총액이 미세하게 남는" 불일치가 되고,
     * {@code ck_positions_cost_matches_quantity} 가 그것을 거부한다.
     *
     * @param quantity 매도 수량. 보유 수량 이하여야 한다 (호출 측이 검증한다)
     * @return 덜어낸 취득 총액. 실현손익의 원가가 이 값이다
     */
    public BigDecimal remove(int quantity) {
        if (quantity == this.quantity) {
            BigDecimal removed = totalCost;
            this.quantity = 0;
            this.totalCost = BigDecimal.ZERO;
            return removed;
        }
        BigDecimal removed = totalCost.multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(this.quantity), COST_SCALE, RoundingMode.HALF_UP);
        this.quantity -= quantity;
        this.totalCost = totalCost.subtract(removed);
        return removed;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long accountId;
        private Long instrumentId;
    }
}
