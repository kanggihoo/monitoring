package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.dto.AccountSummaryResponse;
import com.ssafy.a605.backend.domain.account.dto.AccountValuation;
import com.ssafy.a605.backend.domain.account.dto.HoldingValuation;
import com.ssafy.a605.backend.domain.account.dto.PositionsResponse;
import com.ssafy.a605.backend.domain.order.service.OrderLockService;
import com.ssafy.a605.backend.global.response.Amounts;
import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.season.entity.GameTick;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 한 틱의 전 계좌 평가 (기획서 7.1 · 11.2). 개인 메시지 {@code account.valuation} 과 랭킹이
 * 이 결과를 쓴다.
 *
 * <p><b>조회 경로를 계좌 수만큼 부르지 않는다.</b> {@link AccountService#getSummary} 는 요청
 * 하나를 답하는 경로라 계좌마다 시계·틱·포지션·종가를 따로 읽고, 무엇보다 <b>시계를 스스로 다시
 * 읽는다</b>. 그것을 순회에 쓰면 도중에 틱이 넘어갔을 때 앞 계좌와 뒤 계좌가 서로 다른 틱으로
 * 계산되어, 같은 틱의 조각들이 같은 {@code tickSeq} 를 실어야 한다는 계약이 깨진다
 * ({@code _common.md} §10).
 *
 * <p>그래서 <b>틱을 인자로 받는다.</b> 조회 횟수도 계좌 수와 무관하게 고정된다 — 계좌 한 번,
 * 포지션 한 번, 종가 한 번이다.
 *
 * <p>계산 규칙은 {@link AccountValuations} 하나를 조회 경로와 공유한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AccountValuationService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final MarketPriceService marketPrices;
    private final OrderLockService orderLocks;

    /**
     * 그 틱 기준으로 시즌의 모든 계좌를 평가한다.
     *
     * <p><b>한 계좌의 실패가 전체를 멈추지 않는다.</b> 시세가 없는 종목을 보유한 계좌는 건너뛰고
     * 기록한다 — 조회 경로가 그 상황을 500 으로 드러내는 것과 정책이 다른 이유는, 여기서 던지면
     * 아무 잘못 없는 나머지 사용자의 화면까지 갱신이 멈추기 때문이다.
     *
     * @param tick 공개된 틱. 게임 거래일·환율·순번의 출처다
     * @return 평가된 계좌. 평가하지 못한 계좌는 빠진다
     */
    public List<AccountValuation> valueAll(GameTick tick) {
        List<Account> accounts = accountRepository.findBySeasonId(tick.getSeasonId());
        if (accounts.isEmpty()) {
            return List.of();
        }

        Map<Long, List<Position>> positionsByAccount = positionsOf(accounts);
        Map<Long, Map<Long, Integer>> locks = orderLocks.lockedSellQuantitiesForAccounts(
                accounts.stream().map(Account::getId).toList());
        Map<Long, AsOfPrice> prices = marketPrices.pricesAsOf(
                positionsByAccount.values().stream()
                        .flatMap(List::stream)
                        .map(Position::getInstrumentId)
                        .distinct()
                        .toList(),
                tick.getGameDate());

        return accounts.stream()
                .map(account -> valueOrSkip(account,
                        positionsByAccount.getOrDefault(account.getId(), List.of()), prices, tick,
                        locks.getOrDefault(account.getId(), Map.of())))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 그 틱 기준으로 계좌별 보유를 평가한다. 일차 폐장의 마감 보유 스냅샷이 쓴다 (V42).
     *
     * <p><b>계좌를 키로 돌려주는 이유는 "보유 0" 과 "평가 실패" 를 갈라야 하기 때문이다.</b>
     * 아무것도 들고 있지 않은 계좌는 정상적으로 빈 목록이고, 그 계좌의 스냅샷은 0 행이
     * 맞다. 반면 보유 종목의 종가가 없어 평가하지 못한 계좌는 <b>키 자체가 빠진다</b> —
     * 부르는 쪽이 그 계좌를 "스냅샷 완료" 로 표시하면 기록 없이 자산만 초기화된다.
     *
     * <p>{@link #valueAll} 과 같은 공식({@link AccountValuations})을 쓴다. 두 값이 갈리면
     * 정산 평가액과 스냅샷 합계가 맞지 않고, 그 대조가 스냅샷의 유일한 검증 수단이다.
     *
     * @param tick       평가 기준 틱. 종가의 거래일과 환율의 출처다
     * @param accountIds 평가할 계좌
     * @return 계좌별 보유 평가. 보유가 없으면 빈 목록이고, 종가가 없어 평가하지 못한
     *         계좌는 키가 없다
     */
    public Map<Long, List<HoldingValuation>> holdingsAsOf(GameTick tick, Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(accountIds);
        Map<Long, List<Position>> positionsByAccount = positionRepository
                .findByAccountIdInAndQuantityGreaterThan(ids, 0).stream()
                .collect(Collectors.groupingBy(Position::getAccountId));
        Map<Long, AsOfPrice> prices = marketPrices.pricesAsOf(
                positionsByAccount.values().stream()
                        .flatMap(List::stream)
                        .map(Position::getInstrumentId)
                        .distinct()
                        .toList(),
                tick.getGameDate());

        BigDecimal rate = tick.getUsdKrw();
        Map<Long, List<HoldingValuation>> valued = new HashMap<>();
        for (Long accountId : ids) {
            List<Position> positions = positionsByAccount.getOrDefault(accountId, List.of());
            List<Long> missing = AccountValuations.missingPrices(positions, prices);
            if (!missing.isEmpty()) {
                log.error("Cannot snapshot holdings of account {} at tick {}: no close price for {} as of {}",
                        accountId, tick.getTickSeq(), missing, tick.getGameDate());
                continue;
            }
            valued.put(accountId, positions.stream()
                    .map(position -> new HoldingValuation(accountId, position.getInstrumentId(),
                            position.getQuantity(), position.getTotalCost(),
                            AccountValuations.valuationKrw(position,
                                    prices.get(position.getInstrumentId()), rate)))
                    .toList());
        }
        return valued;
    }

    /**
     * 이 틱의 평가액을 계좌의 최고점·낙폭에 반영한다 ({@code rpt.md} 열린 질문 1).
     *
     * <p><b>평가와 나눠 부른다.</b> 평가는 읽기 트랜잭션이고 이것은 쓰기라, 한 메서드에
     * 두면 매 틱 실시간 발행이 쓰기 트랜잭션 안에서 일어난다. 호출부가 발행을 마친 뒤
     * 부르므로 여기서 실패해도 그 틱의 화면은 이미 갱신돼 있다.
     *
     * <p><b>바뀐 계좌만 UPDATE 된다.</b> 최고점을 새로 찍거나 낙폭이 깊어진 계좌만
     * 더티가 되고, 대부분의 틱에서 그런 계좌는 소수다.
     *
     * @param valued 그 틱의 평가 결과. {@link #valueAll} 이 낸 것 그대로다
     * @return 최고점이나 낙폭이 바뀐 계좌 수
     */
    @Transactional
    public int recordDrawdowns(List<AccountValuation> valued) {
        if (valued.isEmpty()) {
            return 0;
        }
        Map<Long, BigDecimal> byAccount = valued.stream().collect(Collectors.toMap(
                AccountValuation::accountId, AccountValuation::totalValuationKrw));
        int changed = 0;
        for (Account account : accountRepository.findAllById(byAccount.keySet())) {
            if (account.observeValuation(byAccount.get(account.getId()))) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 계좌 하나를 평가하되, 실패하면 그 계좌만 건너뛴다. 시세 없음만 걸러서는 부족하다 —
     * 평가에는 나눗셈이 둘 있고(수익률의 두 분모) 그 분모가 0 이 아님을 보장하는 제약이 없다.
     * 여기서 새어 나가면 아무 잘못 없는 나머지 사용자의 화면까지 그 틱에 갱신되지 않는다.
     */
    private AccountValuation valueOrSkip(Account account, List<Position> positions,
                                         Map<Long, AsOfPrice> prices, GameTick tick,
                                         Map<Long, Integer> locks) {
        try {
            return value(account, positions, prices, tick, locks);
        } catch (RuntimeException e) {
            log.error("Skipping account {} at tick {}: valuation failed",
                    account.getId(), tick.getTickSeq(), e);
            return null;
        }
    }

    /** 계좌 하나를 평가한다. 시세가 없어 평가할 수 없으면 기록하고 {@code null} 이다. */
    private AccountValuation value(Account account, List<Position> positions,
                                   Map<Long, AsOfPrice> prices, GameTick tick,
                                   Map<Long, Integer> locks) {
        List<Long> missing = AccountValuations.missingPrices(positions, prices);
        if (!missing.isEmpty()) {
            log.error("Skipping account {} at tick {}: no close price for instruments {} as of {}",
                    account.getId(), tick.getTickSeq(), missing, tick.getGameDate());
            return null;
        }

        BigDecimal rate = tick.getUsdKrw();
        BigDecimal holdingsValuationKrw = AccountValuations.holdingsValuationKrw(positions, prices, rate);
        BigDecimal totalValuationKrw =
                AccountValuations.totalValuationKrw(account, rate, holdingsValuationKrw);
        BigDecimal cumulativeReturn =
                AccountValuations.returnRate(totalValuationKrw, account.getStartingBalanceKrw());
        BigDecimal dailyReturn =
                AccountValuations.returnRate(totalValuationKrw, account.getDailyBaseValuationKrw());

        return new AccountValuation(account.getId(), account.getUserId(),
                holdingsValuationKrw, totalValuationKrw, cumulativeReturn, dailyReturn,
                AccountSummaryResponse.of(account, tick, holdingsValuationKrw, totalValuationKrw,
                        cumulativeReturn, dailyReturn),
                new PositionsResponse(tick.getGameDate(), tick.getTickSeq(),
                        Amounts.of(rate, Amounts.RATE_SCALE), positions.stream()
                        .sorted(java.util.Comparator.comparing(Position::getInstrumentId))
                        .map(position -> PositionsResponse.Item.of(position,
                                prices.get(position.getInstrumentId()),
                                locks.getOrDefault(position.getInstrumentId(), 0), rate,
                                AccountValuations.valuationKrw(position,
                                        prices.get(position.getInstrumentId()), rate), totalValuationKrw))
                        .toList()));
    }

    /** 전 계좌의 보유 포지션을 한 번에 읽어 계좌별로 묶는다. */
    private Map<Long, List<Position>> positionsOf(List<Account> accounts) {
        return positionRepository
                .findByAccountIdInAndQuantityGreaterThan(accounts.stream().map(Account::getId).toList(), 0)
                .stream()
                .collect(Collectors.groupingBy(Position::getAccountId));
    }
}
