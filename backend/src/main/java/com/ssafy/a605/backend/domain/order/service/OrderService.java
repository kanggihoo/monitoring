package com.ssafy.a605.backend.domain.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.dto.ShortfallExchange;
import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.service.AccountLedgerService;
import com.ssafy.a605.backend.domain.account.service.AccountService;
import com.ssafy.a605.backend.domain.account.service.ExchangeService;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfInstrument;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrdersResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderAmendRequest;
import com.ssafy.a605.backend.domain.order.dto.OrderCreatedResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderPreviewResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderRequest;
import com.ssafy.a605.backend.domain.order.entity.Execution;
import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderStatus;
import com.ssafy.a605.backend.domain.order.entity.OrderType;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.OrderRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.PhaseGate;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 (기획서 6장, {@code ord.md}). 지금은 미리보기와 생성(시장가·예약)이고 예약 조회·수정
 * ·취소가 뒤따른다. 등록된 예약의 체결은 {@link OrderFillService} 가 틱마다 판정한다.
 *
 * <p><b>조회와 체결의 트랜잭션 요구가 다르다.</b> 미리보기는 여러 조회를 한 응답으로 합치므로
 * 클래스 기본값인 {@code REPEATABLE_READ} 로 읽고, 돈을 옮기는 {@link #place} 는 같은 행에 대한
 * 동시 갱신이 직렬화 실패로 거부되면 안 되므로 <b>행 잠금 + 기본 격리</b>로 따로 선언한다.
 * {@code ExchangeService} 가 같은 판단을 하고 있다.
 *
 * <p><b>기준 틱을 스스로 정하지 않는다.</b> "지금 어느 틱까지 공개됐는가"는 재생 상태가 갖는
 * 값이므로 {@link PhaseGate} 가 게이팅에 쓴 그 시계를 그대로 이어 쓴다 — 두 번 물으면 그 사이에
 * 틱이 넘어가, 게이트가 통과시킨 시각과 금액을 계산한 시각이 달라진다.
 *
 * <p><b>{@code REPEATABLE_READ} 로 읽는다.</b> 한 응답이 시계·종목·종가·현금을 여러 번 조회해
 * 하나의 부족분으로 합치므로 그 조회들이 같은 스냅샷을 봐야 한다. PostgreSQL 기본값
 * {@code READ COMMITTED} 에서는 조회 중간에 커밋된 환전이 "환전 전 현금 + 환전 후 환율"로 섞여
 * 들어온다. 같은 이유를 {@code AccountService} 가 더 길게 적어 두었다.
 */
@Slf4j
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OrderService {

    /**
     * 지정가의 정수부 상한. 체결 금액이 {@code NUMERIC(20, 4)} 라 정수부가 16 자리다. 이보다 큰
     * 값은 어차피 저장되지 않으므로 계산에 들어가기 전에 거른다 — 지수 표기(예: {@code 1E+9000000})는
     * 입력이 열 글자인데 전개하면 900만 자리가 되므로, 전개 없이 세어({@code precision - scale})
     * 먼저 막는다. 같은 판단을 {@code ExchangeService} 가 환전 금액에 대해 하고 있다.
     */
    private static final int MAX_INTEGER_DIGITS = 16;

    /**
     * 미체결 예약의 표시 순서. 등록 순서가 사용자가 설명받을 수 있는 유일한 순서다 — 그리고
     * 체결 판정도 같은 순서로 돈다 ({@code OrderFillService}).
     */
    private static final Sort BY_REGISTRATION = Sort.by(Sort.Direction.ASC, "id");

    private final MarketPriceService marketPrices;
    private final AccountService accounts;
    private final AccountLedgerService ledger;
    private final ExchangeService exchanges;
    private final ExecutionRepository executionRepository;
    private final OrderRepository orderRepository;
    private final GameTickCatalog gameTicks;
    private final PhaseGate phases;
    private final ReplayService replay;
    private final OpenOrderDescriber openOrders;
    private final com.ssafy.a605.backend.global.websocket.CommittedUserPublisher updates;

    /** 실제 시각. 게임 시계와 헷갈리지 않게 이름을 나눠 뒀다 — 체결은 둘 다 기록한다. */
    private final Clock wallClock;

    private final long seasonId;

    public OrderService(MarketPriceService marketPrices, AccountService accounts,
                        AccountLedgerService ledger, ExchangeService exchanges,
                        ExecutionRepository executionRepository, OrderRepository orderRepository,
                        GameTickCatalog gameTicks, PhaseGate phases,
                        ReplayService replay, OpenOrderDescriber openOrders, Clock wallClock,
                        @Value("${app.replay.season-id:1}") long seasonId,
                        com.ssafy.a605.backend.global.websocket.CommittedUserPublisher updates) {
        this.marketPrices = marketPrices;
        this.accounts = accounts;
        this.ledger = ledger;
        this.exchanges = exchanges;
        this.executionRepository = executionRepository;
        this.orderRepository = orderRepository;
        this.gameTicks = gameTicks;
        this.phases = phases;
        this.replay = replay;
        this.openOrders = openOrders;
        this.wallClock = wallClock;
        this.seasonId = seasonId;
        this.updates = updates;
    }

    /**
     * 주문을 낸다. 시장가는 <b>즉시 전량 체결</b>되고 (기획서 6.1.2) 미체결 상태를 거치지
     * 않으므로 {@code orders} 에 행을 만들지 않는다 — {@code executions} 에만 남는다.
     *
     * <p><b>미리보기와 같은 계산을 쓴다</b> ({@link OrderQuote}). 두 벌로 두면 "예상 2,626.29"를
     * 보고 누른 주문이 다른 금액으로 체결된다.
     *
     * <p><b>적용 요율은 이 응답이 진실이다.</b> 무료 타임 경계에서 요청이 넘어가면 미리보기가
     * 보여준 요율과 달라질 수 있는데, 그것은 위반이 아니라 요율이 바뀐 경우이므로 409 를 주지
     * 않고 체결시킨 뒤 실제 적용 요율을 담아 돌려준다 ({@code _common.md} §8 예외).
     *
     * @param userId  세션의 User 식별자
     * @param request 주문 유형·방향·종목·수량과 예약의 지정가
     * @return 체결 한 건 (시장가) 또는 등록된 예약
     * @throws BusinessException 거래 구간이 아닐 때 (409), 아직 공개된 시세가 없을 때 (409),
     *                           수량이나 지정가가 잘못됐을 때 (400), 유니버스에 없는 종목일 때 (404),
     *                           아직 상장 전이거나 휴장 종목의 시장가일 때 (409),
     *                           그 시즌 계좌가 없을 때 (404),
     *                           현금이나 보유 수량이 부족할 때 (409)
     */
    @Transactional
    public OrderCreatedResponse place(Long userId, OrderRequest request) {
        GameClockSnapshot clock = phases.requireTrading();
        requireQuantity(request.quantity());
        requireLimitPrice(request.orderType(), request.limitPrice());

        GameTick tick = publishedTick(clock);
        AsOfInstrument instrument = instrument(request.instrumentId(), tick.getGameDate());
        BigDecimal referencePrice = referencePrice(request.orderType(), instrument, tick.getGameDate(),
                request.limitPrice());
        if (request.orderType() == OrderType.LIMIT) {
            var order = register(userId, request, instrument, referencePrice, tick, clock);
            updates.send(userId, "order.changed", order);
            return OrderCreatedResponse.limit(order);
        }

        OrderQuote quote = OrderQuote.of(request.side(), instrument.currency(), referencePrice,
                request.quantity(), clock.feeFree());
        // 읽고 계산한 뒤 쓰는 경로다. 잠그지 않으면 같은 사용자의 동시 요청 둘이 같은 잔고를
        // 읽어 각자의 결과를 덮어쓴다. 오늘의 일차 초기화를 받았는지도 같은 자리에서 본다.
        Account account = ledger.lockedTradingAccount(userId, clock.seasonDay());
        BigDecimal realizedPnl = settle(account, request, instrument.currency(), quote);

        Execution execution = executionRepository.save(new Execution(
                account.getId(), request.instrumentId(), null, OrderType.MARKET, request.side(),
                request.quantity(), quote.referencePrice(), quote.currency(), quote.amount(),
                quote.feeRate(), quote.fee(), realizedPnl, tick.getTickSeq(), tick.getGameDate(),
                Instant.now(wallClock)));
        var response = ExecutionResponse.of(execution, instrument.priceScale());
        updates.send(userId, "order.filled", response);
        return OrderCreatedResponse.market(response);
    }

    /**
     * 예약을 등록하고 잠근다 (기획서 6.2). <b>체결은 판정하지 않는다</b> — 등록 시점 판정이
     * 없는 것이 확정된 도메인 규칙이고 (6.4.1), 현재 종가보다 유리한 지정가라도 다음 틱을
     * 기다린다 ({@code ord.md} §등록 즉시 판정하지 않는다).
     *
     * <p><b>잠금이 검증을 대신한다.</b> 매수는 현금을 잠그고 매도는 수량이 있는지 확인하므로
     * (기획서 6.2.2 · 6.2.3), 등록을 통과한 예약은 체결이 실패하지 않는다 — 그것이 미체결
     * 상태를 단순하게 유지하는 6.2 의 전제이고, 부분 실패를 없애는 근거다.
     *
     * <p>잠그는 순서가 <b>계좌 잠금 → 검증 → 저장</b>인 이유는 검증과 갱신이 같은 행 잠금 안에
     * 있어야 하기 때문이다. 먼저 저장하고 나중에 잠그면 잔고가 모자란 예약이 순간적으로
     * {@code OPEN} 으로 존재한다.
     */
    private OpenOrderResponse register(Long userId, OrderRequest request, AsOfInstrument instrument,
                                       BigDecimal limitPrice, GameTick tick, GameClockSnapshot clock) {
        Instant expiresAt = closesAt(clock);
        Account account = ledger.lockedTradingAccount(userId, clock.seasonDay());

        BigDecimal lockedAmount = null;
        Integer lockedQuantity = null;
        if (request.side() == OrderSide.BUY) {
            // 잠금액을 OrderQuote 에서 얻는 이유는 미리보기가 보여준 총액과 같은 계산이어야
            // 하기 때문이다. 매수에 수수료가 없어 amount() 가 곧 totalAmount 이고, 그것이
            // 잠금액이자 체결 금액이다 — 화면의 총액과 실제 문턱이 갈라질 여지가 없다.
            lockedAmount = OrderQuote.of(OrderSide.BUY, instrument.currency(), limitPrice,
                    request.quantity(), clock.feeFree()).amount();
            requireLockable(lockedAmount);
            ledger.lockCash(account, instrument.currency(), lockedAmount);
        } else {
            lockedQuantity = request.quantity();
            ledger.requireAvailableQuantity(account.getId(), request.instrumentId(), lockedQuantity);
        }

        Order order = orderRepository.save(new Order(account.getId(), request.instrumentId(),
                request.side(), limitPrice, request.quantity(), instrument.currency(),
                lockedAmount, lockedQuantity, expiresAt, tick.getTickSeq(), Instant.now(wallClock)));
        return OpenOrderResponse.of(order, instrument.priceScale(), tick.getGameDate());
    }

    /**
     * 미체결 예약 목록 (기획서 6.3.1). 수정·취소의 대상이며 폐장에 소멸한다.
     *
     * <p><b>phase 게이트를 걸지 않는다.</b> 기획서 6.2.1 이 제한하는 것은 등록·수정·취소이고,
     * 열람 전용 시간에도 목록 조회는 허용한다 (0장 시간표). 그래서 시계를 게이트가 아니라
     * 직접 읽는다.
     *
     * <p>폐장 뒤에는 언제나 빈 목록이다 — 자동 취소가 그 시점에 전부 닫기 때문이며 (6.2.4)
     * 이 조회가 걸러내는 것이 아니다.
     *
     * @param userId 세션의 User 식별자
     * @return 공개 기준 틱과 미체결 예약
     * @throws BusinessException 그 시즌 계좌가 없을 때 (404)
     */
    public OpenOrdersResponse openOrders(Long userId) {
        List<Order> open = orderRepository.findByAccountIdAndStatus(accounts.accountId(userId),
                OrderStatus.OPEN, BY_REGISTRATION);
        return new OpenOrdersResponse(replay.clock().tickSeq(), openOrders.describe(open));
    }

    /**
     * 미체결 예약을 수정한다 (기획서 6.3.3).
     *
     * <p><b>취소 후 재등록과 동일하게 처리한다.</b> 잠금을 전액 반환하고 새 조건으로 다시
     * 잠근다 — 잠금액을 차이만큼 조정하지 않는 이유는 그 계산이 등록 경로와 다른 두 번째
     * 규칙이 되기 때문이다. 풀고 다시 잠그면 {@link #register} 와 같은 코드가 값을 정한다.
     *
     * <p><b>부분 성공이 없다.</b> 새 조건으로 잠글 잔고가 모자라면 409 를 던지고, 그 예외가
     * 트랜잭션을 되돌려 원래 예약이 그대로 남는다 — 잠금을 이미 풀어 둔 상태도 함께
     * 되돌아간다. 성공하면 전부, 실패하면 아무것도다.
     *
     * @param userId  세션의 User 식별자
     * @param orderId 고칠 예약
     * @param request 새 수량·지정가. 비운 쪽은 지금 값을 쓴다
     * @return 고쳐진 예약
     * @throws BusinessException 거래 구간이 아닐 때 (409), 둘 다 비었거나 값이 잘못됐을 때 (400),
     *                           내 미체결 예약이 아닐 때 (404), 이미 닫힌 예약일 때 (409),
     *                           새 조건의 잠금이 잔고를 넘을 때 (409)
     */
    @Transactional
    public OpenOrderResponse amend(Long userId, Long orderId, OrderAmendRequest request) {
        GameClockSnapshot clock = phases.requireTrading();
        if (request.quantity() == null && request.limitPrice() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "수량과 지정가 중 최소 하나는 있어야 합니다.");
        }

        GameTick tick = publishedTick(clock);
        Account account = ledger.lockedTradingAccount(userId, clock.seasonDay());
        Order order = openOrder(account, orderId);

        int quantity = request.quantity() == null ? order.getQuantity() : request.quantity();
        BigDecimal limitPrice = request.limitPrice() == null
                ? order.getLimitPrice() : request.limitPrice();
        requireQuantity(quantity);
        requireLimitPrice(OrderType.LIMIT, limitPrice);
        AsOfInstrument instrument = instrument(order.getInstrumentId(), tick.getGameDate());
        requirePriceScale(limitPrice, instrument.priceScale());

        BigDecimal lockedAmount = null;
        Integer lockedQuantity = null;
        if (order.getSide() == OrderSide.BUY) {
            // 먼저 푼다. 그래야 같은 예약이 점유하던 몫이 새 잠금의 재원이 되고, 수량을 그대로
            // 두는 수정이 "두 배를 요구한다"가 되지 않는다.
            ledger.unlockCash(account, order.getCurrency(), order.getLockedAmount());
            lockedAmount = OrderQuote.of(OrderSide.BUY, order.getCurrency(), limitPrice, quantity,
                    clock.feeFree()).amount();
            requireLockable(lockedAmount);
            ledger.lockCash(account, order.getCurrency(), lockedAmount);
        } else {
            lockedQuantity = quantity;
            // 매도 잠금은 orders 가 들고 있어 미리 풀 수 없다. 대신 이 예약을 셈에서 뺀다.
            ledger.requireAvailableQuantity(account.getId(), order.getInstrumentId(), quantity,
                    order.getId());
        }

        order.amend(quantity, limitPrice, lockedAmount, lockedQuantity);
        var response = openOrders.describe(order, instrument.priceScale());
        updates.send(userId, "order.changed", response);
        return response;
    }

    /**
     * 미체결 예약을 취소한다 (기획서 6.3.2). 잠긴 현금·수량이 즉시 반환된다.
     *
     * @param userId  세션의 User 식별자
     * @param orderId 취소할 예약
     * @throws BusinessException 거래 구간이 아닐 때 (409), 내 미체결 예약이 아닐 때 (404),
     *                           이미 닫힌 예약일 때 (409)
     */
    @Transactional
    public void cancel(Long userId, Long orderId) {
        phases.requireTrading();
        Account account = ledger.lockedAccount(userId);
        Order order = openOrder(account, orderId);

        if (order.getSide() == OrderSide.BUY) {
            ledger.unlockCash(account, order.getCurrency(), order.getLockedAmount());
        }
        // 매도는 되돌릴 것이 없다. 잠금이 orders.locked_quantity 에 있고 OPEN 인 행만 세므로,
        // 상태가 바뀌는 것으로 풀린다.
        order.cancel(Instant.now(wallClock));
        updates.send(userId, "order.cancelled", java.util.Map.of("orderId", orderId));
    }

    /**
     * 내 미체결 예약을 잠긴 계좌 아래에서 읽는다.
     *
     * <p><b>남의 예약은 404 다.</b> 403 을 주면 그 {@code orderId} 의 예약이 존재한다는 사실이
     * 드러난다 — 문의 도메인이 같은 판단을 한 선례가 있다 ({@code ord.md} §{@code DELETE}).
     * 그래서 없는 예약과 남의 예약을 <b>같은 응답</b>으로 답한다.
     *
     * <p>반면 <b>내 예약인데 이미 닫혔다면 409 다.</b> 그것은 숨길 것이 없고, 사용자에게는
     * "목록이 낡았다"는 다른 사건이다 — 404 로 뭉뚱그리면 방금 체결된 예약을 취소하려던
     * 사용자가 자기 주문이 사라졌다고 읽는다.
     */
    private Order openOrder(Account account, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .filter(found -> found.getAccountId().equals(account.getId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 처리된 예약입니다.");
        }
        return order;
    }

    /**
     * 잠글 금액이 결제 통화의 최소 단위 이상이어야 한다.
     *
     * <p><b>0 원을 잠근 예약이 만들어지는 경로가 있다.</b> 가격 자릿수가 결제 통화보다 잘은
     * 종목(MATIC 은 네 자리, 달러는 두 자리)에 아주 낮은 지정가를 소량으로 걸면
     * {@code 지정가 × 수량} 이 통화 자릿수로 반올림되며 0 이 된다. 그 예약은 잠금이 없으니
     * 공짜 매수가 되고, 체결되면 {@code 수량 > 0} 인데 취득 총액이 0 인 포지션이 되어
     * {@code ck_positions_cost_matches_quantity} 를 어겨 <b>체결이 매 틱 실패</b>한다.
     *
     * <p>미리보기에서 막지 않는 것은 그쪽이 상태를 바꾸지 않기 때문이다 — 금액이 0 으로
     * 보이는 것은 사용자가 고칠 수 있는 정보이고, 거절해야 하는 것은 등록이다.
     */
    private static void requireLockable(BigDecimal lockedAmount) {
        if (lockedAmount.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "주문 금액이 너무 작아 예약할 수 없습니다.");
        }
    }

    /**
     * 당일 폐장 실제 시각. 예약의 유효기간은 당일뿐이다 (기획서 6.2.4).
     *
     * <p><b>거래 구간의 다음 phase 경계가 폐장이다.</b> Phase 를 바꾸는 경계는 브리핑·개장·폐장
     * ·종료 넷뿐이므로 ({@code ReplayState.PHASE_BOUNDARIES}) {@code TRADING} 다음은 폐장이고,
     * 게이트가 이미 {@code TRADING} 을 보장했다. 시계를 다시 묻지 않는 것은 그 사이에 관리자가
     * 배속을 바꾸면 등록한 예약의 만료가 게이트를 통과한 시각과 다른 기준에서 나오기 때문이다.
     *
     * @throws BusinessException 재생이 정지 중이라 도달 시각이 없을 때 (409)
     */
    private static Instant closesAt(GameClockSnapshot clock) {
        if (clock.phaseEndsAt() == null) {
            // 배속 0 은 테스트 서버에서만 가능하다. 그래도 막는 것은 유효기간 없는 예약을
            // 만들지 않기 위해서다 — 폐장이 오지 않으면 그 예약은 영구히 잠금을 쥔다.
            throw new BusinessException(HttpStatus.CONFLICT,
                    "재생이 정지 중이라 예약의 유효기간을 정할 수 없습니다.");
        }
        return clock.phaseEndsAt();
    }

    /**
     * 현금과 포지션을 옮긴다. 계좌 도메인이 잔고·보유 수량 부족을 여기서 판정한다 —
     * 레버리지와 공매도를 막는 검증이 데이터의 주인 쪽에 있어야 한다 (기획서 6.5.1 · 6.5.3).
     *
     * @return 매도의 실현손익. 매수면 {@code null} 이다 ({@code ord.md})
     */
    private BigDecimal settle(Account account, OrderRequest request, Currency currency,
                              OrderQuote quote) {
        if (request.side() == OrderSide.BUY) {
            ledger.settleBuy(account, request.instrumentId(), request.quantity(), currency,
                    quote.amount());
            return null;
        }
        return ledger.settleSell(account, request.instrumentId(), request.quantity(), currency,
                quote.amount(), quote.fee());
    }

    /**
     * 주문 미리보기. 예상 체결 금액·수수료·부족분을 돌려주고 어떤 예약도 만들지 않으며 체결
     * 판정에도 관여하지 않는다 ({@code ord.md} §등록 즉시 판정하지 않는다).
     *
     * @param userId       세션의 User 식별자
     * @param orderType    시장가인지 예약인지
     * @param side         주문 방향
     * @param instrumentId 대상 종목
     * @param quantity     주문 수량
     * @param limitPrice   지정가. 예약일 때만 쓰이며 시장가면 무시한다
     * @return 기준가·금액·수수료·부족분
     * @throws BusinessException 거래 구간이 아닐 때 (409), 아직 공개된 시세가 없을 때 (409),
     *                           수량이나 지정가가 잘못됐을 때 (400), 유니버스에 없는 종목일 때 (404),
     *                           아직 상장 전이거나 휴장 종목의 시장가일 때 (409),
     *                           그 시즌 계좌가 없을 때 (404),
     *                           상장 종목의 종가가 적재되지 않았을 때 (500)
     */
    public OrderPreviewResponse preview(Long userId, OrderType orderType, OrderSide side,
                                        Long instrumentId, int quantity, BigDecimal limitPrice) {
        GameClockSnapshot clock = phases.requireTrading();
        requireQuantity(quantity);
        requireLimitPrice(orderType, limitPrice);

        GameTick tick = publishedTick(clock);
        AsOfInstrument instrument = instrument(instrumentId, tick.getGameDate());
        BigDecimal referencePrice = referencePrice(orderType, instrument, tick.getGameDate(), limitPrice);

        OrderQuote quote = OrderQuote.of(side, instrument.currency(), referencePrice, quantity,
                clock.feeFree());
        BigDecimal availableCash = availableCash(userId, instrument.currency());
        BigDecimal shortfall = shortfall(side, quote, availableCash);

        return OrderPreviewResponse.of(quote, instrument.priceScale(), availableCash, shortfall,
                shortfallExchange(instrument.currency(), shortfall, tick.getUsdKrw()),
                clock.feeFree());
    }

    /**
     * 기준가 — 시장가는 그 종목의 공개 기준 종가, 예약은 사용자가 낸 지정가다
     * ({@code ord.md} §{@code GET /api/v1/orders/preview}).
     *
     * <p>예약은 종가를 보지 않는다. 그래서 <b>휴장 종목에도 예약은 걸 수 있고</b> 시장가만
     * 막힌다 (기획서 4.1.4) — 그날 새 틱이 없어 체결 판정 자체가 일어나지 않는 것과, 지금
     * 당장 살 값이 없는 것은 다른 이야기다.
     */
    private BigDecimal referencePrice(OrderType orderType, AsOfInstrument instrument,
                                      LocalDate gameDate, BigDecimal limitPrice) {
        if (orderType == OrderType.LIMIT) {
            requirePriceScale(limitPrice, instrument.priceScale());
            return limitPrice;
        }
        if (instrument.close() == null) {
            log.error("No close price loaded for instrument {} as of {}",
                    instrument.instrumentId(), gameDate);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "종목의 시세가 없어 예상 금액을 계산할 수 없습니다.");
        }
        if (instrument.isMarketClosedOn(gameDate)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "휴장 중인 종목은 시장가로 주문할 수 없습니다.");
        }
        return instrument.close();
    }

    /**
     * 매수가 결제 통화로 얼마나 모자란가. <b>매도는 항상 0 이다</b> — 파는 쪽은 현금이 아니라
     * 보유 수량을 쓰고, 수수료는 받을 대금에서 빠지므로 현금이 모자랄 수가 없다.
     */
    private static BigDecimal shortfall(OrderSide side, OrderQuote quote, BigDecimal availableCash) {
        if (side != OrderSide.BUY) {
            return BigDecimal.ZERO;
        }
        return quote.totalAmount().subtract(availableCash).max(BigDecimal.ZERO);
    }

    /**
     * 부족분을 채우는 환전 요청. 방향은 결제 통화가 정한다 — 부족한 쪽을 <b>받는</b>
     * 방향이다. 금액은 계좌 도메인이 실제 환전 계산으로 낸다.
     */
    private ShortfallExchange shortfallExchange(Currency currency, BigDecimal shortfall,
                                                BigDecimal rate) {
        if (shortfall.signum() <= 0) {
            return null;
        }
        ExchangeDirection direction = currency == Currency.USD
                ? ExchangeDirection.KRW_TO_USD
                : ExchangeDirection.USD_TO_KRW;
        return ShortfallExchange.of(direction, exchanges.shortfallSendAmount(direction, shortfall, rate));
    }

    /**
     * 잠기지 않은 결제 통화 현금. <b>내림</b>이다 — 이 값은 "여기까지 쓸 수 있다"는 상한이고,
     * 잔고는 표시 자릿수보다 잘게 남을 수 있다(체결가가 소수 여덟 자리까지 간다). 반올림하면
     * 부족분이 0 으로 보이는데 주문은 거절되는 칸이 생긴다.
     */
    private BigDecimal availableCash(Long userId, Currency currency) {
        return accounts.availableCash(userId, currency)
                .setScale(OrderQuote.scale(currency), RoundingMode.FLOOR);
    }

    /**
     * 유니버스의 종목이고 그 거래일에 상장돼 있어야 한다 (기획서 5.2.2). IPO 종목은 상장일
     * 전까지 조회는 되지만 거래되지 않는다 ({@code ipo.md}).
     */
    private AsOfInstrument instrument(Long instrumentId, LocalDate gameDate) {
        AsOfInstrument instrument = marketPrices.instrumentAsOf(instrumentId, gameDate)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다."));
        if (!instrument.isListedOn(gameDate)) {
            throw new BusinessException(HttpStatus.CONFLICT, "아직 상장되지 않은 종목입니다.");
        }
        return instrument;
    }

    /**
     * 게이트가 판정에 쓴 시계가 가리키는 틱.
     *
     * <p>거래 구간인데도 공개된 틱이 없는 순간이 있다 — 개장(11:05) 직후 첫 틱이 나가기 전
     * 30여 초다. 그때는 기준가도 환율도 없으므로 거절한다. 조용히 직전 거래일 값을 쓰면
     * 브로드캐스트된 시세와 다른 금액을 안내하게 되고, 그 차이는 사용자가 볼 수 없다.
     */
    private GameTick publishedTick(GameClockSnapshot clock) {
        if (clock.tickSeq() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "시세가 아직 공개되지 않았습니다.");
        }
        GameTick tick = gameTicks.find(clock.tickSeq()).orElseThrow(() -> {
            log.error("Published tick {} is absent from game_ticks (season {})", clock.tickSeq(), seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "공개 기준 틱을 찾을 수 없습니다.");
        });
        if (tick.getSeasonDay() != clock.seasonDay()) {
            // 개장했지만 오늘 첫 틱이 아직인 순간이다. 공개 틱 순번은 일차가 넘어가도
            // 리셋되지 않아 어제 마지막 틱을 가리키므로, 그대로 쓰면 어제 종가로 오늘
            // 거래가 된다. 메시지를 위와 같게 두는 것은 사용자에게 같은 상황이기
            // 때문이다 — "오늘 시세가 아직 없다".
            throw new BusinessException(HttpStatus.CONFLICT, "시세가 아직 공개되지 않았습니다.");
        }
        return tick;
    }

    /** 1주 단위 정수 거래다. 0 이하는 주문이 아니다 (기획서 6.5.2). */
    private static void requireQuantity(int quantity) {
        if (quantity < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "주문 수량은 1주 이상이어야 합니다.");
        }
    }

    /**
     * 지정가는 예약일 때만 필수다. 필수 여부가 {@code orderType} 에 달려 있어 Bean Validation
     * 으로 표현할 수 없으므로 여기서 본다 — {@code ExchangeService} 가 금액 자릿수에 대해 하는
     * 판단과 같은 이유다.
     */
    private static void requireLimitPrice(OrderType orderType, BigDecimal limitPrice) {
        if (orderType != OrderType.LIMIT) {
            return;
        }
        if (limitPrice == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "예약 주문에는 지정가가 필요합니다.");
        }
        // 크기를 먼저 본다. 뒤의 계산이 값을 전개하므로 그 전에 걸러야 한다.
        if (limitPrice.precision() - limitPrice.scale() > MAX_INTEGER_DIGITS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "지정가가 너무 큽니다.");
        }
        if (limitPrice.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "지정가는 0보다 커야 합니다.");
        }
    }

    /**
     * 지정가의 자릿수는 그 종목의 것이어야 한다 ({@code _common.md} §6.2). 종목마다 다르므로
     * 종목을 읽은 뒤에 본다.
     *
     * <p>더 잘게 받아 반올림해 버리지 않는 이유는 <b>가격이 주문의 조건</b>이기 때문이다.
     * 조용히 깎으면 응답의 기준가와 예상 금액이 서로를 설명하지 못하고, 사용자가 낸 값과 체결
     * 판정에 쓰일 값이 달라진다. 환전이 원화 1원·달러 1센트보다 잘은 금액을 거절하는 것과 같은
     * 판단이다.
     */
    private static void requirePriceScale(BigDecimal limitPrice, short priceScale) {
        if (limitPrice.stripTrailingZeros().scale() <= priceScale) {
            return;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, priceScale == 0
                ? "이 종목의 지정가는 정수로만 입력할 수 있습니다."
                : "이 종목의 지정가는 소수점 %d자리까지 입력할 수 있습니다.".formatted(priceScale));
    }
}
