package com.ssafy.a605.backend.domain.order.repository;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 여러 계좌의 잠긴 수량을 한 번에 집계하기 위한 미체결 예약 조회다. */
    List<Order> findByAccountIdInAndStatus(List<Long> accountIds, OrderStatus status);

    /**
     * 그 계좌의 방향·상태가 맞는 예약 주문. 1인 1계좌에 미체결 예약은 몇 건이므로
     * ({@code ord.md} - 미체결 예약 조회에 페이지를 두지 않았다) 합계를 DB 에 맡기지 않고
     * 행을 읽어 호출 측에서 더한다.
     *
     * @param accountId 대상 계좌
     * @param side      주문 방향
     * @param status    주문 상태
     * @return 조건에 맞는 주문
     */
    List<Order> findByAccountIdAndSideAndStatus(Long accountId, OrderSide side, OrderStatus status);

    /**
     * 미체결 예약 전부. 틱마다의 체결 판정이 판정 대상을 모으는 데 쓴다.
     *
     * <p><b>종목으로 좁히지 않는다.</b> 그 틱에 새 종가가 있는 종목을 먼저 알아야 좁힐 수
     * 있는데, 그 목록을 얻으려면 종가 28행을 먼저 읽어야 한다 — 미체결 예약이 몇 건인 반면
     * 종가는 종목 수만큼이므로, 예약에서 출발해 필요한 종목의 종가만 읽는 편이 적게 읽는다.
     *
     * <p>페이지를 두지 않는 이유는 {@code ord.md} 가 미체결 예약 목록에 페이지를 두지 않은
     * 것과 같다 — 1인 1계좌에 몇 건이고, 한 틱의 판정은 나눠 처리할 수 없다.
     *
     * <p>정렬을 두지 않는다. 이 결과에서 쓰는 것은 <b>어느 계좌에 체결이 있는가</b>뿐이고,
     * 계좌 안에서의 판정 순서는 잠금 뒤 재조회가 정한다.
     *
     * @param status 주문 상태
     * @return 그 상태의 예약 전부
     */
    List<Order> findByStatus(OrderStatus status);

    long countByStatus(OrderStatus status);

    /**
     * 그 계좌의 미체결 예약. 계좌를 잠근 뒤 판정 대상을 <b>다시 읽는</b> 데 쓴다.
     *
     * <p>잠금 전에 읽은 행을 그대로 쓰지 않는 이유는 그 사이에 사용자가 취소했을 수 있기
     * 때문이다. 잠금 뒤 재조회가 곧 그 창을 닫는다 — 취소된 예약을 체결시키면 이미 반환한
     * 잠금을 한 번 더 풀게 된다.
     *
     * @param accountId 대상 계좌
     * @param status    주문 상태
     * @param sort      판정 순서. 같은 종목에 예약이 여럿이면 취득 총액의 비례 차감 결과가
     *                  순서에 달려 있어 결정론적이어야 한다
     * @return 조건에 맞는 주문
     */
    List<Order> findByAccountIdAndStatus(Long accountId, OrderStatus status, Sort sort);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
