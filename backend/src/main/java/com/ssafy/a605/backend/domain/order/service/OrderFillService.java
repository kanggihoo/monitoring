package com.ssafy.a605.backend.domain.order.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.service.AccountLedgerService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.entity.Execution;
import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderStatus;
import com.ssafy.a605.backend.domain.order.entity.OrderType;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.OrderRepository;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository.TickRange;

/**
 * 틱마다의 체결 판정 (기획서 6.4, {@code ord.md} §체결 판정 규칙). 새 종가 틱이 도착할 때마다
 * 그 종목의 미체결 예약을 판정한다.
 *
 * <table border="1">
 *   <caption>판정 규칙</caption>
 *   <tr><th>방향</th><th>조건</th><th>체결가</th></tr>
 *   <tr><td>매수</td><td>종가 ≤ 지정가</td><td>그 틱의 종가</td></tr>
 *   <tr><td>매도</td><td>종가 ≥ 지정가</td><td>그 틱의 종가</td></tr>
 * </table>
 *
 * <p><b>체결가가 지정가가 아니라 종가인 결과로 차이는 항상 사용자에게 유리하다</b> — 매수는
 * 지정가보다 싸게, 매도는 비싸게 체결된다 (6.4.2).
 *
 * <p><b>계좌마다 트랜잭션을 나눈다.</b> 한 계좌의 판정이 깨져도 나머지가 체결되어야 하기
 * 때문이다 — 스윕 전체를 한 트랜잭션으로 묶으면 잘못된 예약 하나가 <b>매 틱</b> 전원의 체결을
 * 막고, 그것이 시즌 내내 반복된다. {@code AccountValuationListener} 가 같은 이유로 사람 단위로
 * 실패를 끊는다.
 *
 * <p><b>같은 틱에서 조건을 충족한 예약은 전부 전량 체결된다.</b> 가상 유동성이 무제한이라
 * 플레이어 간 물량 경쟁이 없고 부분 체결도 없다. 한 사용자가 같은 종목에 여러 예약을 걸었다면
 * 각각 독립적으로 판정된다.
 */
@Service
public class OrderFillService {

    /**
     * 한 계좌 안에서의 판정 순서. 같은 종목에 예약이 여럿이면 취득 총액의 비례 차감
     * ({@code Position.remove}) 결과가 순서에 달려 있어 결정론적이어야 한다 — 등록 순서로
     * 판정하는 것이 사용자가 설명받을 수 있는 유일한 순서다.
     */
    private static final Sort BY_REGISTRATION = Sort.by(Sort.Direction.ASC, "id");

    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final GameTickRepository gameTickRepository;
    private final MarketPriceService marketPrices;
    private final AccountLedgerService ledger;
    private final Clock wallClock;
    private final long seasonId;

    public OrderFillService(OrderRepository orderRepository, ExecutionRepository executionRepository,
                            GameTickRepository gameTickRepository, MarketPriceService marketPrices,
                            AccountLedgerService ledger, Clock wallClock,
                            @Value("${app.replay.season-id:1}") long seasonId) {
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.gameTickRepository = gameTickRepository;
        this.marketPrices = marketPrices;
        this.ledger = ledger;
        this.wallClock = wallClock;
        this.seasonId = seasonId;
    }

    /**
     * 이 틱에 체결될 예약을 가진 계좌들. 계좌마다 트랜잭션을 나누려면 대상을 먼저 알아야 한다.
     *
     * <p>여기서 판정한 결과를 그대로 쓰지 않고 {@link #fillFor} 가 계좌를 잠근 뒤 다시
     * 판정하는 이유는, 그 사이에 사용자가 취소했을 수 있기 때문이다. 이 목록은 <b>후보</b>이며
     * 확정이 아니다 — 잠금 없이 읽었으므로 확정일 수가 없다.
     *
     * @param tick 공개된 틱
     * @return 후보 계좌 식별자. 중복은 없다
     */
    /** 판정 대상이 된 미체결 수. 부하 측정 로그용이다. */
    public long openOrders() {
        return orderRepository.countByStatus(OrderStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public List<Long> accountsWithFills(GameTick tick) {
        List<Order> open = orderRepository.findByStatus(OrderStatus.OPEN);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> closes = closesFor(open, tick.getGameDate());
        long today = dayFirstTick(tick);
        return open.stream().filter(order -> fills(order, closes, today)).map(Order::getAccountId)
                .distinct().toList();
    }

    /**
     * 한 계좌의 체결을 확정한다. <b>계좌 행을 먼저 잠그고 그 안에서 다시 판정한다.</b>
     *
     * <p>후보 목록을 그대로 믿지 않는 이유는 그 사이에 사용자가 취소했을 수 있기 때문이다.
     * 잠금을 쥔 뒤 재조회가 그 창을 닫는다 — 취소·수정도 계좌 행을 잠근 뒤에야 예약을 건드린다.
     *
     * <p><b>무료 타임 여부를 인자로 받는다.</b> 무료 요율은 <i>체결</i> 시점 기준이므로
     * (기획서 6.7.3) 등록 시점 요율을 쓸 수 없고, 계좌마다 시계를 다시 물으면 한 틱의 스윕
     * 안에서 앞 계좌는 유료, 뒤 계좌는 무료가 되는 순간이 생긴다 — 한 번 읽어 전원에게 같은
     * 값을 적용한다.
     *
     * @param accountId 대상 계좌
     * @param tick      공개된 틱. 체결의 게임 시각이 된다
     * @param feeFree   그 틱 시점의 무료 타임 여부. 매도에만 영향이 있다
     * @return 확정된 체결. {@code order.filled} 로 보낼 항목이며 후보가 전부 취소됐으면 빈 목록
     */
    @Transactional
    public Filled fillFor(Long accountId, GameTick tick, boolean feeFree) {
        // 계좌를 먼저 잠근다. 취소·수정도 이 행을 잠근 뒤에야 예약을 건드리므로, 잠금을 쥔 뒤
        // 읽은 목록은 판정이 끝날 때까지 바뀌지 않는다 — 순서를 뒤집으면 이미 취소된 예약을
        // 체결시켜 이미 반환한 잠금을 한 번 더 푸는 창이 열린다.
        Account account = ledger.lockedAccountById(accountId);
        List<Order> open = orderRepository.findByAccountIdAndStatus(accountId, OrderStatus.OPEN,
                BY_REGISTRATION);
        Map<Long, BigDecimal> closes = closesFor(open, tick.getGameDate());
        long today = dayFirstTick(tick);
        List<Order> fillable = open.stream().filter(order -> fills(order, closes, today)).toList();
        if (fillable.isEmpty()) {
            return new Filled(null, List.of());
        }

        Map<Long, Short> priceScales = marketPrices.priceScales(
                fillable.stream().map(Order::getInstrumentId).distinct().toList());
        List<ExecutionResponse> executions = new ArrayList<>(fillable.size());
        for (Order order : fillable) {
            executions.add(ExecutionResponse.of(
                    fill(account, order, closes.get(order.getInstrumentId()), tick, feeFree),
                    priceScales.get(order.getInstrumentId())));
        }
        return new Filled(account.getUserId(), executions);
    }

    /**
     * 예약 한 건을 체결시킨다. 예약을 닫고 잠금을 정산하고 체결을 남기는 세 가지가 한 묶음이다.
     *
     * <p><b>정산 순서는 잠금을 먼저 푸는 것이다</b> (기획서 6.4.3). 매수는 잠금을 전액 풀고 실제
     * 체결 금액만 빼므로 차액이 그 차이로 반환되고, 매도는 잠긴 수량을 포지션에서 덜어낸 뒤
     * 수수료 뗀 대금을 넣는다.
     *
     * <p><b>체결 시각과 예약을 닫은 시각이 같다.</b> 같은 사건이므로 시계를 두 번 읽지 않는다 —
     * 두 번 읽으면 복기에서 "체결보다 먼저 닫힌 예약"이 보인다.
     */
    private Execution fill(Account account, Order order, BigDecimal close, GameTick tick,
                           boolean feeFree) {
        OrderQuote quote = OrderQuote.of(order.getSide(), order.getCurrency(), close,
                order.getQuantity(), feeFree);
        Instant at = Instant.now(wallClock);
        order.fill(at);

        BigDecimal realizedPnl = null;
        if (order.getSide() == OrderSide.BUY) {
            ledger.settleLockedBuy(account, order.getInstrumentId(), order.getQuantity(),
                    order.getCurrency(), order.getLockedAmount(), quote.amount());
        } else {
            realizedPnl = ledger.settleLockedSell(account, order.getInstrumentId(),
                    order.getQuantity(), order.getCurrency(), quote.amount(), quote.fee());
        }
        return executionRepository.save(new Execution(
                account.getId(), order.getInstrumentId(), order.getId(), OrderType.LIMIT,
                order.getSide(), order.getQuantity(), close, order.getCurrency(), quote.amount(),
                quote.feeRate(), quote.fee(), realizedPnl, tick.getTickSeq(), tick.getGameDate(),
                at));
    }

    /**
     * 판정에 필요한 종가. 그 거래일에 <b>새 종가가 있는</b> 종목만 담긴다.
     *
     * <p>빠진 종목은 그날 휴장이며 그 예약은 판정되지 않는다 (기획서 6.4.5) — 새 틱이 없기
     * 때문이고, 직전 종가 유지는 체결 사유가 아니다. 이월된 값으로 판정하면 어제 이미 판정한
     * 종가로 오늘 또 체결시킨다.
     */
    private Map<Long, BigDecimal> closesFor(List<Order> orders, LocalDate gameDate) {
        return marketPrices.closesOn(
                orders.stream().map(Order::getInstrumentId).distinct().toList(), gameDate);
    }

    /**
     * 판정 대상 일차의 첫 틱 순번. 그보다 이르게 등록된 예약은 어제의 것이다.
     *
     * <p>틱 순번이 공개 순서라 한 일차의 틱이 연속하는 것에 기댄다
     * ({@code GameTickRepository.findTickRange}). 조회를 스윕당 한 번으로 묶는 것은 예약마다
     * 등록 틱의 일차를 되짚으면 조회가 예약 수만큼 늘어나기 때문이다.
     */
    private long dayFirstTick(GameTick tick) {
        TickRange range = gameTickRepository.findTickRange(seasonId, tick.getSeasonDay());
        if (range == null || range.fromTickSeq() == null) {
            throw new IllegalStateException(
                    "공개된 틱의 일차가 편성에 없습니다: tick=%d, day=%d"
                            .formatted(tick.getTickSeq(), tick.getSeasonDay()));
        }
        return range.fromTickSeq();
    }

    /**
     * 이 예약이 이 틱에 체결되는가. 종가가 없으면(휴장) 판정하지 않는다.
     *
     * <p>부등호에 등호가 붙는 것은 <b>지정가와 같은 종가도 체결</b>이라는 뜻이다. 사용자가 낸
     * 조건이 "이 값이면 산다"이므로 정확히 그 값에서 빼면 조건을 좁히는 것이 된다.
     *
     * <p><b>어제 등록된 예약은 판정하지 않는다.</b> 예약의 유효기간은 당일뿐이다 (기획서
     * 6.2.4). 폐장 자동 취소가 그 규칙의 집행부지만 아직 없고, 있더라도 이 판정이 남아야
     * 한다 — 취소가 어떤 이유로든 밀리면 어제 예약이 오늘 종가로 체결되고, 사용자에게는
     * "취소됐어야 할 주문이 하루 뒤에 체결됐다"로 보인다.
     *
     * <p>실제 시각({@code expires_at})과 비교하지 않는 것은 배속 때문이다. 100배속에서는
     * 마지막 틱과 폐장 사이가 6.6 초뿐이라, 틱 공개와 판정 사이에 폐장을 넘는 순간이 생긴다.
     * <b>게임 시계로 재면 그 경계 레이스가 없다</b> — 틱은 자기 일차를 갖고 있다.
     *
     * @param todayFirstTick 판정 대상 일차의 첫 틱 순번
     */
    private static boolean fills(Order order, Map<Long, BigDecimal> closes, long todayFirstTick) {
        if (order.getCreatedTickSeq() < todayFirstTick) {
            return false;
        }
        BigDecimal close = closes.get(order.getInstrumentId());
        if (close == null) {
            return false;
        }
        int compared = close.compareTo(order.getLimitPrice());
        return order.getSide() == OrderSide.BUY ? compared <= 0 : compared >= 0;
    }

    /**
     * 한 계좌의 확정된 체결.
     *
     * @param userId     체결의 주인. {@code order.filled} 를 받을 사람이며 체결이 없으면
     *                   {@code null} — 보낼 것이 없으므로 호출 측이 쓰지 않는다
     * @param executions 확정된 체결. 등록 순서다
     */
    public record Filled(Long userId, List<ExecutionResponse> executions) {
    }
}
