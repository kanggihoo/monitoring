package com.ssafy.a605.backend.domain.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 보유 수량의 상한. 컨테이너 없이 도는 이유는 순수 계산이기 때문이고, 따로 보는 이유는
 * <b>넘기면 조용히 음수가 되기 때문</b>이다 — {@code int} 덧셈은 예외 없이 감싸 돌고,
 * 베타 1일차에 $0.0126 짜리 코인을 100만 주씩 사던 계좌가 있었다.
 */
class PositionTest {

    @Test
    @DisplayName("추가 매수_int 상한을 넘으면_음수가 되지 않고 거부한다")
    void add_overInt_isRejected() {
        Position position = new Position(1L, 1L, Integer.MAX_VALUE - 1, new BigDecimal("100"));

        assertThatThrownBy(() -> position.add(2, new BigDecimal("1")))
                .isInstanceOf(BusinessException.class);
        // 거부는 아무것도 바꾸지 않는다 — 수량만 막고 취득 총액이 늘면 평단이 틀어진다.
        assertThat(position.getQuantity()).isEqualTo(Integer.MAX_VALUE - 1);
        assertThat(position.getTotalCost()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("추가 매수_상한 안이면_수량과 취득 총액이 함께 는다")
    void add_withinInt_accumulates() {
        Position position = new Position(1L, 1L, 1_000_000, new BigDecimal("12600"));

        position.add(1_000_000, new BigDecimal("12600"));

        assertThat(position.getQuantity()).isEqualTo(2_000_000);
        assertThat(position.getTotalCost()).isEqualByComparingTo("25200");
    }
}
