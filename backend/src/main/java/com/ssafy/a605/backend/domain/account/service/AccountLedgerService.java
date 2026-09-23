package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.service.OrderLockService;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 잔고와 포지션을 움직이는 <b>유일한 경로</b> (기획서 6.4.3 · 7.3.3). 체결·청약처럼 돈을 옮기는
 * 쪽은 이 서비스를 거친다.
 *
 * <p><b>주문이 {@code accounts}·{@code positions} 를 직접 쓰지 않는 이유.</b> 지켜야 하는 불변식이
 * 이 도메인의 것이기 때문이다 — 이동평균법은 취득 총액을 비례 차감해야 성립하고
 * ({@code acc.md}), 수량이 0 이면 취득 총액도 0 이어야 하며
 * ({@code ck_positions_cost_matches_quantity}), 잠긴 몫은 다른 주문이 중복 사용할 수 없다.
 * 그 규칙이 주문·청약 두 곳으로 흩어지면 한쪽만 고쳐지는 날이 온다. <b>계좌가 주인이고 주문은
 * 의뢰인이다.</b>
 *
 * <p>조회 전용 {@link AccountService} 와 나눠 둔 이유는 트랜잭션이다. 계좌 조회는 여러 조회를 한
 * 응답으로 합치므로 {@code REPEATABLE_READ} 여야 하지만, 돈을 옮기는 경로는 같은 행에 대한 동시
 * 갱신이 직렬화 실패로 거부되면 안 되므로 <b>행 잠금 + 기본 격리</b>가 맞다.
 * {@link ExchangeService} 가 같은 판단을 하고 있다.
 *
 * <p><b>검증도 여기서 한다.</b> "잔고보다 많이 쓸 수 없다"와 "가진 것보다 많이 팔 수 없다"는
 * 데이터의 규칙이라, 부르는 쪽마다 다시 쓰면 언젠가 빠뜨린다 — 그 순간이 레버리지와 공매도다
 * (기획서 6.5.1 · 6.5.3).
 */
@Service
@Transactional
public class AccountLedgerService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final OrderLockService orderLocks;
    private final long seasonId;

    public AccountLedgerService(AccountRepository accountRepository, PositionRepository positionRepository,
                                OrderLockService orderLocks,
                                @Value("${app.replay.season-id:1}") long seasonId) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.orderLocks = orderLocks;
        this.seasonId = seasonId;
    }

    /**
     * 갱신할 계좌를 행 잠금으로 읽는다.
     *
     * <p>읽고 계산한 뒤 쓰는 경로이므로 잠그지 않으면 같은 사용자의 동시 요청 둘이 같은 잔고를
     * 읽어 각자의 결과를 덮어쓴다 — 동시 세션이 허용되므로 실제로 일어날 수 있다.
     *
     * @param userId 세션의 User 식별자
     * @return 잠긴 계좌
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    public Account lockedAccount(Long userId) {
        return accountRepository.findWithLockByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "시즌 계좌가 없습니다."));
    }

    /**
     * 거래에 쓸 계좌를 잠그고, <b>오늘 초기화를 받은 계좌인지 확인한다</b> (일차 초기화).
     *
     * <p><b>왜 필요한가.</b> 일차 경계에서 잔고를 시드로 되돌리기로 한 뒤부터, 초기화가
     * 실패한 계좌는 <b>어제 자산 그대로 오늘 거래한다</b>. 정산 실패를 로그로만 남기고
     * 재생을 계속하는 정책({@code DailySettlementService.on})은 기준값 갱신이던 시절에
     * 감수할 수 있었지만, 초기화가 되면서 감수할 수 없는 것이 됐다.
     *
     * <p><b>노출을 새로 만드는 경로만 부른다.</b> 취소·만료·부재 확인은 초기화가 밀린
     * 상태에서도 돌아야 하는 정리 작업이라 {@link #lockedAccount} 를 그대로 쓴다. 막아야
     * 하는 것은 주문·환전·청약이다.
     *
     * <p>판정 자체는 {@link Account#requireDailyReady} 가 갖는다 — 환전은 계좌를 여기서
     * 잠그지 않으므로 규칙을 이 메서드에 두면 한 벌이 되지 못한다.
     *
     * @param userId    세션의 User 식별자
     * @param seasonDay 지금 진행 중인 일차. 게이트가 통과시킨 시계에서 온다
     * @return 잠긴 계좌
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404),
     *                           오늘의 초기화를 아직 받지 못했을 때 (409)
     */
    public Account lockedTradingAccount(Long userId, short seasonDay) {
        Account account = lockedAccount(userId);
        account.requireDailyReady(seasonDay);
        return account;
    }

    /**
     * 식별자로 갱신할 계좌를 행 잠금으로 읽는다. 사용자 요청이 아닌 경로가 쓴다 — 틱마다의
     * 체결 판정은 미체결 예약에서 출발해 계좌 식별자를 이미 손에 들고 있다.
     *
     * @param accountId 계좌 식별자
     * @return 잠긴 계좌
     * @throws IllegalStateException 그 계좌가 없을 때. 예약이 참조하는 계좌라 없을 수 없고,
     *                               없다면 사용자 입력이 아니라 데이터가 깨진 것이다
     */
    public Account lockedAccountById(Long accountId) {
        return accountRepository.findWithLockById(accountId).orElseThrow(
                () -> new IllegalStateException("예약이 참조하는 계좌가 없습니다: " + accountId));
    }

    /**
     * 매수 체결을 반영한다. 결제 통화 현금이 나가고 포지션이 늘어난다.
     *
     * <p><b>나가는 현금이 곧 취득 총액이다</b> — 매수에는 수수료가 없다 ({@code ord.md} §수수료).
     * 수수료가 있었다면 현금에서는 수수료까지 빠지지만 취득 총액에는 들어가지 않아 두 값이
     * 갈렸을 것이다.
     *
     * @param account      갱신할 계좌. {@link #lockedAccount} 로 잠근 것이어야 한다
     * @param instrumentId 체결된 종목
     * @param quantity     체결 수량
     * @param currency     결제 통화
     * @param amount       체결 금액 (현지 통화)
     * @throws BusinessException 잠기지 않은 결제 통화 현금이 부족할 때 (409)
     */
    public void settleBuy(Account account, Long instrumentId, int quantity, Currency currency,
                          BigDecimal amount) {
        if (availableCash(account, currency).compareTo(amount) < 0) {
            throw notEnoughCash(currency);
        }

        account.spend(currency, amount);
        Position position = positionRepository
                .findById(new Position.Pk(account.getId(), instrumentId))
                .orElse(null);
        if (position == null) {
            // 처음 사는 종목은 최종 값으로 한 번에 넣는다. 빈 행을 만들고 더하면 식별자가
            // 배정 복합 키라 save() 가 merge() 로 가면서 존재하지 않는 행을 한 번 더 조회한다.
            positionRepository.save(new Position(account.getId(), instrumentId, quantity, amount));
            return;
        }
        position.add(quantity, amount);
    }

    /**
     * 매도 체결을 반영한다. 포지션이 줄고 수수료를 뗀 대금이 들어온다.
     *
     * <p><b>실현손익을 여기서 확정해 돌려준다.</b> 원가는 비례 차감된 취득 총액이고 그 나눗셈은
     * {@link Position#remove} 하나에만 있다. 이 값을 저장해야 하는 이유는 매도 요율이 시점에
     * 의존해(무료 타임 0%) 나중에 재계산하면 값이 달라지기 때문이다 ({@code _common.md} §6.3).
     *
     * @param account      갱신할 계좌. {@link #lockedAccount} 로 잠근 것이어야 한다
     * @param instrumentId 체결된 종목
     * @param quantity     체결 수량
     * @param currency     결제 통화
     * @param amount       체결 금액 (수수료 미포함)
     * @param fee          매도 수수료
     * @return 실현손익 = {@code (체결 금액 - 수수료) - 비례 차감된 취득 총액}
     * @throws BusinessException 잠기지 않은 보유 수량이 부족할 때 (409)
     */
    public BigDecimal settleSell(Account account, Long instrumentId, int quantity, Currency currency,
                                 BigDecimal amount, BigDecimal fee) {
        Position position = positionRepository
                .findById(new Position.Pk(account.getId(), instrumentId))
                .orElseThrow(AccountLedgerService::notEnoughQuantity);
        if (availableQuantity(position) < quantity) {
            throw notEnoughQuantity();
        }

        BigDecimal removedCost = position.remove(quantity);
        BigDecimal proceeds = amount.subtract(fee);
        account.receive(currency, proceeds);
        return proceeds.subtract(removedCost);
    }

    /**
     * 예약 매수의 현금을 잠근다 (기획서 6.2.2). 총액은 그대로 두고 쓸 수 없게 표시한다 —
     * 실제로 빠지는 시점은 체결이다.
     *
     * <p><b>잠금액이 곧 체결 금액이다</b> — 매수에 수수료가 없어 {@code 지정가 × 수량} 이
     * 그대로 잠기고, 체결가는 지정가 이하이므로 잠금이 모자랄 수 없다 ({@code ord.md} §수수료).
     *
     * @param account  갱신할 계좌. {@link #lockedAccount} 로 잠근 것이어야 한다
     * @param currency 결제 통화
     * @param amount   잠글 금액
     * @throws BusinessException 잠기지 않은 결제 통화 현금이 부족할 때 (409)
     */
    public void lockCash(Account account, Currency currency, BigDecimal amount) {
        if (availableCash(account, currency).compareTo(amount) < 0) {
            throw notEnoughCash(currency);
        }
        account.lock(currency, amount);
    }

    /**
     * 예약 매수의 현금 잠금을 푼다. 취소·수정·폐장 만료가 쓴다 (기획서 6.2.4 · 6.3.2 · 6.3.3).
     *
     * <p><b>현금이 늘지 않는다.</b> 잠금은 총액을 건드리지 않았으므로 푸는 것도 마찬가지다 —
     * 쓸 수 있게 표시가 돌아올 뿐이다. 체결만이 {@link #settleLockedBuy} 로 잠금 해제와 지출을
     * 함께 한다.
     *
     * <p>매도에는 짝이 없다. 매도 잠금은 {@code orders.locked_quantity} 가 들고 있고
     * {@code OrderLockService} 가 {@code OPEN} 인 행만 세므로, 예약이 닫히는 것으로 잠금이
     * 풀린다 — 계좌에서 되돌릴 것이 없다.
     *
     * @param account  갱신할 계좌. {@link #lockedAccount} 로 잠근 것이어야 한다
     * @param currency 결제 통화
     * @param amount   풀 금액. 등록 시 잠근 값이다
     * @throws IllegalStateException 잠긴 몫보다 큰 금액을 풀려 할 때
     */
    public void unlockCash(Account account, Currency currency, BigDecimal amount) {
        account.unlock(currency, amount);
    }

    /**
     * 예약 매도가 잠글 수량이 있는지 본다. 공매도를 막는 검증이다 (기획서 6.5.1 · 6.5.3).
     *
     * <p><b>계좌에는 아무것도 기록하지 않는다.</b> 매도 잠금은 {@code orders.locked_quantity}
     * 가 들고 있고 (금액과 단위가 달라 한 컬럼에 합치지 않는다) 여기서는 그 값이 성립하는지만
     * 판정한다 — 그래도 이 도메인의 일인 것은 "가진 것보다 많이 팔 수 없다"가 포지션의
     * 규칙이기 때문이다.
     *
     * @param accountId    대상 계좌
     * @param instrumentId 팔려는 종목
     * @param quantity     잠글 수량
     * @throws BusinessException 잠기지 않은 보유 수량이 부족할 때 (409)
     */
    public void requireAvailableQuantity(Long accountId, Long instrumentId, int quantity) {
        requireAvailableQuantity(accountId, instrumentId, quantity, null);
    }

    /**
     * 한 예약의 잠금을 빼고 판정한다. <b>그 예약 자신을 고칠 때</b> 쓴다 (기획서 6.3.3).
     *
     * <p>수정은 취소 후 재등록과 같은 처리라 새 수량을 볼 때 옛 잠금은 이미 풀린 것으로
     * 봐야 한다. 자기 잠금을 함께 세면 수량을 그대로 두는 수정조차 거부된다.
     *
     * @param accountId        대상 계좌
     * @param instrumentId     팔려는 종목
     * @param quantity         잠글 수량
     * @param excludingOrderId 셈에서 뺄 예약. {@code null} 이면 전부 센다
     * @throws BusinessException 잠기지 않은 보유 수량이 부족할 때 (409)
     */
    public void requireAvailableQuantity(Long accountId, Long instrumentId, int quantity,
                                         Long excludingOrderId) {
        Position position = positionRepository.findById(new Position.Pk(accountId, instrumentId))
                .orElseThrow(AccountLedgerService::notEnoughQuantity);
        int locked = orderLocks.lockedSellQuantities(accountId, excludingOrderId)
                .getOrDefault(instrumentId, 0);
        if (position.getQuantity() - locked < quantity) {
            throw notEnoughQuantity();
        }
    }

    /**
     * 예약 매수의 체결을 반영한다. 잠금을 <b>전액</b> 풀고 실제 체결 금액만 현금에서 뺀다.
     *
     * <p><b>차액이 이 두 단계로 반환된다</b> (기획서 6.4.3). 체결가는 그 틱의 종가이고 매수는
     * {@code 종가 ≤ 지정가} 일 때만 체결되므로 체결 금액은 잠금액 이하다 — 그 차이가 곧
     * "지정가보다 싸게 산 만큼"이고, 잠금을 부분 차감하는 대신 풀고 다시 빼면 반환이 따로
     * 계산되지 않는다.
     *
     * <p>{@link #settleBuy} 와 달리 잔고를 확인하지 않는다. 잠금이 이미 그 금액을 확보해 둔
     * 상태이므로 확인할 것이 없고, 확인하면 오히려 잘못 막는다 — 잠긴 몫은
     * {@code availableCash} 에서 빠져 있어 자기 잠금 때문에 자기 체결이 거부된다. 예약은
     * 잠금이 있어서 체결이 실패하지 않는다는 것이 기획서 6.2 의 전제다.
     *
     * @param account      갱신할 계좌. {@link #lockedAccount} 로 잠근 것이어야 한다
     * @param instrumentId 체결된 종목
     * @param quantity     체결 수량
     * @param currency     결제 통화
     * @param lockedAmount 등록 시 잠근 금액 ({@code 지정가 × 수량})
     * @param amount       실제 체결 금액 ({@code 종가 × 수량}). 잠금액 이하다
     */
    public void settleLockedBuy(Account account, Long instrumentId, int quantity, Currency currency,
                                BigDecimal lockedAmount, BigDecimal amount) {
        account.unlock(currency, lockedAmount);
        account.spend(currency, amount);
        Position position = positionRepository
                .findById(new Position.Pk(account.getId(), instrumentId))
                .orElse(null);
        if (position == null) {
            positionRepository.save(new Position(account.getId(), instrumentId, quantity, amount));
            return;
        }
        position.add(quantity, amount);
    }

    /**
     * 예약 매도의 체결을 반영한다. 잠긴 수량을 포지션에서 덜어내고 수수료 뗀 대금을 넣는다.
     *
     * <p><b>잠기지 않은 수량을 보지 않는다</b> — 이 주문의 잠금이 아직 {@code OPEN} 이라
     * {@link #availableQuantity} 로 재면 자기 잠금 때문에 자기 체결이 거부된다. 보유 수량만
     * 확인하며, 그것이 모자란 것은 잠금 불변식이 깨진 상태다 (등록 때 확보했고 시장가 매도는
     * 잠긴 몫을 건드리지 못한다).
     *
     * @param account      갱신할 계좌. {@link #lockedAccount} 로 잠근 것이어야 한다
     * @param instrumentId 체결된 종목
     * @param quantity     체결 수량 (= 잠긴 수량)
     * @param currency     결제 통화
     * @param amount       체결 금액 (수수료 미포함)
     * @param fee          매도 수수료. 무료 타임 체결이면 0 이다
     * @return 실현손익 = {@code (체결 금액 - 수수료) - 비례 차감된 취득 총액}
     * @throws BusinessException 보유 수량이 잠긴 수량보다 적을 때 (409)
     */
    public BigDecimal settleLockedSell(Account account, Long instrumentId, int quantity,
                                       Currency currency, BigDecimal amount, BigDecimal fee) {
        Position position = positionRepository
                .findById(new Position.Pk(account.getId(), instrumentId))
                .orElseThrow(AccountLedgerService::notEnoughQuantity);
        if (position.getQuantity() < quantity) {
            throw notEnoughQuantity();
        }

        BigDecimal removedCost = position.remove(quantity);
        BigDecimal proceeds = amount.subtract(fee);
        account.receive(currency, proceeds);
        return proceeds.subtract(removedCost);
    }

    /**
     * 잠기지 않은 보유 수량. 미체결 예약 매도가 잠근 몫은 시장가 매도나 다른 매도 예약에 중복
     * 사용할 수 없다 (기획서 6.2.3 · 6.5.3).
     */
    private int availableQuantity(Position position) {
        Map<Long, Integer> locked = orderLocks.lockedSellQuantities(position.getAccountId());
        return position.getQuantity() - locked.getOrDefault(position.getInstrumentId(), 0);
    }

    /** 잠기지 않은 결제 통화 현금. 예약 매수와 청약 증거금이 점유한 몫은 쓸 수 없다. */
    private static BigDecimal availableCash(Account account, Currency currency) {
        return currency == Currency.USD ? account.availableUsd() : account.availableKrw();
    }

    /**
     * 결제 통화가 모자랄 때. 달러 쪽에만 환전을 안내하는 이유는 그것이 해결 방법이기
     * 때문이다 — 원화가 모자란 경우도 대칭이지만 시드가 원화라 실제로는 달러를 되돌리는
     * 상황이고, 안내할 다음 행동이 명확하지 않다.
     */
    private static BusinessException notEnoughCash(Currency currency) {
        return new BusinessException(HttpStatus.CONFLICT, currency == Currency.USD
                ? "달러 현금이 부족합니다. 환전 후 다시 시도해 주세요."
                : "원화 현금이 부족합니다.");
    }

    /**
     * 보유하지 않은 종목과 수량이 모자란 종목을 같은 메시지로 답한다 — 둘 다 사용자에게는
     * "팔 것이 없다"이고, 구분해도 할 수 있는 일이 달라지지 않는다.
     */
    private static BusinessException notEnoughQuantity() {
        return new BusinessException(HttpStatus.CONFLICT, "매도할 수 있는 보유 수량이 부족합니다.");
    }
}
