package com.ssafy.a605.backend.domain.order.service;

import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderStatus;
import com.ssafy.a605.backend.domain.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * 미체결 예약이 점유한 몫. 잠긴 것을 알아야 하는 다른 도메인(계좌·랭킹)이 이 서비스를 거쳐
 * 읽는다 — 도메인 간 협력은 Service 호출로 한다 ({@code architecture/overview.md} §3).
 *
 * <p>계좌가 {@code orders} 를 직접 읽지 않는 이유는 잠금의 의미가 주문 도메인의 것이기
 * 때문이다. 매수는 현금을, 매도는 보유 수량을 잠그고 (그래서 컬럼이 둘로 나뉘어 있다),
 * 어떤 상태가 "아직 점유 중"인지는 체결 판정과 폐장 자동 취소가 정한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderLockService {

    private final OrderRepository orderRepository;

    /**
     * 틱 평가에서 계좌 수만큼 조회하지 않고 잠긴 수량을 한 번에 읽는다.
     * @param accountIds 평가 대상 계좌
     * @return 계좌별 종목별 잠긴 매도 수량
     */
    public Map<Long, Map<Long, Integer>> lockedSellQuantitiesForAccounts(List<Long> accountIds) {
        if (accountIds.isEmpty()) return Map.of();
        return orderRepository.findByAccountIdInAndStatus(accountIds, OrderStatus.OPEN).stream()
                .filter(order -> order.getSide() == OrderSide.SELL)
                .collect(Collectors.groupingBy(Order::getAccountId,
                        Collectors.groupingBy(Order::getInstrumentId,
                                Collectors.summingInt(Order::getLockedQuantity))));
    }

    /**
     * 미체결 예약 매도가 잠근 종목별 수량 (기획서 6.2.3 · 7.3.4).
     *
     * <p>같은 종목에 여러 예약을 걸 수 있고 각각 독립적으로 판정되므로 ({@code ord.md}
     * §체결 판정 규칙) 종목 하나에 여러 행이 잡힐 수 있어 더해서 돌려준다.
     *
     * @param accountId 대상 계좌
     * @return 종목 식별자별 잠긴 수량. 잠긴 것이 없는 종목은 담기지 않는다
     */
    public Map<Long, Integer> lockedSellQuantities(Long accountId) {
        return lockedSellQuantities(accountId, null);
    }

    /**
     * 한 예약의 잠금을 빼고 센 종목별 잠긴 수량. <b>그 예약 자신을 고칠 때</b> 쓴다.
     *
     * <p>수정은 취소 후 재등록과 같은 처리이므로 (기획서 6.3.3) 새 수량을 검증하는 시점에는
     * 옛 잠금이 이미 풀린 것으로 봐야 한다. 자기 잠금을 함께 세면 <b>수량을 그대로 두는
     * 수정조차 거부된다</b> — 4주 잠근 예약을 4주로 고치는 것이 8주를 요구하는 꼴이 된다.
     *
     * <p>행을 먼저 고치고 세는 방법을 쓰지 않는 이유는 검증이 실패했을 때다. 그때는 원래
     * 예약을 그대로 둬야 하는데, 이미 고쳐 놓았다면 되돌리는 일이 생긴다.
     *
     * @param accountId        대상 계좌
     * @param excludingOrderId 셈에서 뺄 예약. {@code null} 이면 전부 센다
     * @return 종목 식별자별 잠긴 수량. 잠긴 것이 없는 종목은 담기지 않는다
     */
    public Map<Long, Integer> lockedSellQuantities(Long accountId, Long excludingOrderId) {
        return orderRepository
                .findByAccountIdAndSideAndStatus(accountId, OrderSide.SELL, OrderStatus.OPEN)
                .stream()
                .filter(order -> !order.getId().equals(excludingOrderId))
                .collect(Collectors.groupingBy(Order::getInstrumentId,
                        Collectors.summingInt(Order::getLockedQuantity)));
    }
}
