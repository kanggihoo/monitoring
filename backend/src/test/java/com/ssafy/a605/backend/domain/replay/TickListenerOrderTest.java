package com.ssafy.a605.backend.domain.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;

import com.ssafy.a605.backend.domain.account.listener.AccountValuationListener;
import com.ssafy.a605.backend.domain.order.listener.OrderFillListener;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;

/**
 * 틱 리스너의 실행 순서 (S15P21A605-191).
 *
 * <p>체결 판정이 계좌 평가보다 먼저 돌아야 한다. 뒤집히면 틱 N 의 {@code account.valuation} 과
 * 랭킹이 틱 N 의 체결을 빠뜨린 값으로 나간다 — 화면은 "체결됐는데 잔고가 그대로"가 된다.
 *
 * <p><b>컨텍스트를 띄우지 않는다.</b> Spring 이 {@code @EventListener} 의 순서를 정하는 방식을
 * 그대로 재현한다 — <b>메서드</b>에서만 {@code @Order} 를 찾고, 없으면 최하위다. 실제로 이
 * 리포에서는 {@code @Order} 가 클래스에 붙어 있어 둘 다 최하위가 됐고, 빈 등록 순서로 밀려
 * 계약과 정반대로 돌았다. 그 상태에서 이 테스트는 두 값이 같아 실패한다.
 */
class TickListenerOrderTest {

    @Test
    @DisplayName("틱 리스너_체결 판정이_계좌 평가보다 먼저 돈다")
    void orderFillRunsBeforeAccountValuation() throws NoSuchMethodException {
        assertThat(listenerOrder(OrderFillListener.class))
                .as("체결 판정(%s)이 계좌 평가(%s)보다 먼저 돌아야 한다. "
                                + "@Order 가 클래스에 있으면 둘 다 최하위가 되어 순서가 빈 등록 순서로 밀린다",
                        OrderFillListener.class.getSimpleName(),
                        AccountValuationListener.class.getSimpleName())
                .isLessThan(listenerOrder(AccountValuationListener.class));
    }

    /**
     * Spring 이 {@code @EventListener} 메서드에 매기는 순서. 메서드에서만 {@code @Order} 를 찾는다.
     *
     * @param listener 리스너 클래스
     * @return 그 리스너의 순서. {@code @Order} 가 메서드에 없으면 {@link Ordered#LOWEST_PRECEDENCE}
     * @throws NoSuchMethodException 틱 이벤트를 받는 메서드 이름이 바뀌었을 때
     */
    private static int listenerOrder(Class<?> listener) throws NoSuchMethodException {
        Method method = listener.getMethod("onTickPublished", TickPublishedEvent.class);
        Order order = AnnotatedElementUtils.findMergedAnnotation(method, Order.class);
        return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
    }
}
