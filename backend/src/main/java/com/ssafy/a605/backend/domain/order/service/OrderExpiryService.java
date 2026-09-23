package com.ssafy.a605.backend.domain.order.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.service.AccountLedgerService;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderStatus;
import com.ssafy.a605.backend.domain.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * 폐장 자동 취소 (기획서 6.2.4). <b>예약의 유효기간은 당일뿐</b>이므로 폐장에 남아 있는
 * 미체결 예약을 전부 소멸시키고 잠긴 몫을 돌려준다.
 *
 * <p><b>일차로 좁히지 않고 {@code OPEN} 전부를 닫는다.</b> 정상 경로라면 남아 있는 것은 모두
 * 오늘 것이지만, 어떤 이유로 지난 폐장을 놓쳤다면 어제 예약이 아직 열려 있다. 그것까지 함께
 * 닫는 편이 옳다 — 남겨 두면 잠금이 영구히 묶이고, {@code OrderFillService} 의 당일 판정이
 * 체결만 막을 뿐 잠금은 풀지 못한다.
 *
 * <p><b>계좌마다 트랜잭션을 나눈다.</b> 한 계좌가 깨져도 나머지는 잠금을 돌려받아야 한다 —
 * 전원을 한 트랜잭션으로 묶으면 잘못된 예약 하나가 그날 모든 사용자의 현금을 묶어 둔 채
 * 다음 날로 넘긴다. {@link OrderFillService} 가 같은 이유로 같은 모양이다.
 */
@Service
@RequiredArgsConstructor
public class OrderExpiryService {

    /** 닫는 순서. 통보 목록이 미체결 목록과 같은 순서로 보여야 프론트가 짝지어 걷어낸다. */
    private static final Sort BY_REGISTRATION = Sort.by(Sort.Direction.ASC, "id");

    private final OrderRepository orderRepository;
    private final AccountLedgerService ledger;
    private final OpenOrderDescriber openOrders;

    /**
     * 미체결 예약을 가진 계좌들. 계좌마다 트랜잭션을 나누려면 대상을 먼저 알아야 한다.
     *
     * <p>이 목록은 <b>후보</b>다. 잠금 없이 읽었으므로 그 사이에 사용자가 직접 취소했을 수
     * 있고, {@link #expireFor} 가 계좌를 잠근 뒤 다시 읽어 확정한다.
     *
     * @return 후보 계좌 식별자. 중복은 없다
     */
    @Transactional(readOnly = true)
    public List<Long> accountsWithOpenOrders() {
        return orderRepository.findByStatus(OrderStatus.OPEN).stream()
                .map(Order::getAccountId).distinct().toList();
    }

    /**
     * 한 계좌의 미체결 예약을 전부 닫고 잠금을 돌려준다.
     *
     * <p><b>매도는 되돌릴 것이 없다.</b> 잠금이 {@code orders.locked_quantity} 에 있고
     * {@code OrderLockService} 가 {@code OPEN} 인 행만 세므로, 상태가 바뀌는 것으로 풀린다.
     * 매수만 계좌의 잠긴 현금을 되돌린다.
     *
     * <p>계좌를 <b>먼저</b> 잠그고 예약을 다시 읽는 것은 취소·수정과의 경합 때문이다. 그 둘도
     * 같은 행을 잠근 뒤에야 예약을 건드리므로, 잠금을 쥔 뒤 읽은 목록은 바뀌지 않는다 —
     * 순서를 뒤집으면 이미 취소된 예약을 한 번 더 닫아 잠금이 두 번 풀린다.
     *
     * @param accountId 대상 계좌
     * @param at        폐장 실제 시각. 통보의 {@code occurredAt} 이자 예약이 닫힌 시각이다
     * @return 취소된 예약. 후보가 그 사이 전부 취소됐으면 빈 목록
     */
    @Transactional
    public Expired expireFor(Long accountId, Instant at) {
        Account account = ledger.lockedAccountById(accountId);
        List<Order> open = orderRepository.findByAccountIdAndStatus(accountId, OrderStatus.OPEN,
                BY_REGISTRATION);
        if (open.isEmpty()) {
            return new Expired(null, List.of());
        }

        // 응답을 닫기 전에 만든다. 잠금 컬럼은 닫아도 남지만, 상태가 바뀐 뒤에 읽으면
        // "미체결 예약"이라는 이 타입의 뜻과 어긋나는 값을 담게 된다.
        List<OpenOrderResponse> described = openOrders.describe(open);
        for (Order order : open) {
            if (order.getSide() == OrderSide.BUY) {
                ledger.unlockCash(account, order.getCurrency(), order.getLockedAmount());
            }
            order.expire(at);
        }
        return new Expired(account.getUserId(), described);
    }

    /**
     * 한 계좌에서 폐장에 사라진 예약.
     *
     * @param userId 예약의 주인. {@code order.expired} 를 받을 사람이며 사라진 것이 없으면
     *               {@code null}
     * @param orders 취소된 예약과 반환된 잠금. 등록 순서다
     */
    public record Expired(Long userId, List<OpenOrderResponse> orders) {
    }
}
