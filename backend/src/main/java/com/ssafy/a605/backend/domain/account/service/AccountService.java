package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.dto.AccountSummaryResponse;
import com.ssafy.a605.backend.domain.account.dto.PositionsResponse;
import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.order.service.OrderLockService;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * 계좌 조회. 지금은 요약과 포지션이고 환전·부재 요약이 뒤따른다 ({@code acc.md}).
 *
 * <p>평가액과 수익률은 저장하지 않고 응답을 만들 때 계산한다 ({@code _common.md} §6.4).
 *
 * <p><b>평가 기준 틱을 스스로 정하지 않는다.</b> "지금 어느 틱까지 공개됐는가"는 재생 상태가
 * 갖는 값이고 {@link ReplayService#clock()} 이 그 단일 진실이다. 계좌가 따로 판정하면
 * 브로드캐스트된 {@code market.prices} 와 계좌 평가액이 서로 다른 틱을 보게 되어,
 * {@code _common.md} §10 이 막으려는 "주가는 올랐는데 내 평가액은 그대로"인 화면이 나온다.
 *
 * <p>시즌은 재생과 같은 설정값에서 읽는다 — 재생이 진행하는 시즌과 평가 대상 시즌이 어긋나면
 * 환율·종가와 계좌가 다른 시즌 것이 된다. "현재 시즌" 판정을 별도로 두지 않는 것은
 * {@code sea.md} 가 시즌 식별자를 계약에서 의도적으로 뺀 것과 같은 이유다.
 *
 * <p><b>{@code REPEATABLE_READ} 로 읽는다.</b> 한 응답이 시계·현금·포지션·종가를 여러 번
 * 조회해 하나의 평가액으로 합치므로, 그 조회들이 같은 스냅샷을 봐야 한다. PostgreSQL 기본값
 * {@code READ COMMITTED} 에서는 조회마다 스냅샷이 새로 잡혀, 조회 중간에 커밋된 체결이
 * "체결 전 현금 + 체결 후 포지션"으로 섞여 들어온다.
 *
 * <p>격리 수준을 여기 명시해야 하는 이유는 {@link ReplayService} 가 시계를 읽을 때 쓰는
 * {@code REPEATABLE_READ} 가 <b>참여하는 트랜잭션에서는 조용히 버려지기</b> 때문이다 —
 * Spring 은 {@code validateExistingTransaction} 이 기본 {@code false} 라 격리 수준 불일치를
 * 예외 없이 무시한다. 시계를 트랜잭션 밖에서 부르는 {@code GameClockController} 와 달리
 * 계좌는 이 트랜잭션 안에서 부르므로, 여기서 같은 수준을 선언하지 않으면 시계가 요청한
 * 일관성이 사라진다.
 */
@Slf4j
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AccountService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final GameTickCatalog gameTicks;
    private final MarketPriceService marketPrices;
    private final OrderLockService orderLocks;
    private final ReplayService replay;
    private final long seasonId;

    public AccountService(AccountRepository accountRepository, PositionRepository positionRepository,
                          GameTickCatalog gameTicks, MarketPriceService marketPrices,
                          OrderLockService orderLocks, ReplayService replay,
                          @Value("${app.replay.season-id:1}") long seasonId) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.gameTicks = gameTicks;
        this.marketPrices = marketPrices;
        this.orderLocks = orderLocks;
        this.replay = replay;
        this.seasonId = seasonId;
    }

    /**
     * 내 계좌 요약을 읽는다. 세션의 User 로 대상을 좁히므로 남의 계좌는 조회되지 않는다 —
     * 기획서 11.6.2 가 타인의 포지션 상세 비공개를 요구한다.
     *
     * @param userId 세션의 User 식별자
     * @return 공개 기준 틱으로 평가한 계좌 요약. 최초 공개 전에는 틱 관련 세 필드가 {@code null}
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다),
     *                           그 시즌에 참가하지 않아 계좌가 없을 때 (404),
     *                           평가에 필요한 종가가 적재되지 않았을 때 (500)
     */
    public AccountSummaryResponse getSummary(Long userId) {
        GameClockSnapshot clock = replay.clock();
        Account account = account(userId);
        List<Position> positions = positions(account);

        if (clock.tickSeq() == null) {
            requireNothingToValue(account, positions);
            return AccountSummaryResponse.beforeFirstPublish(account);
        }

        GameTick tick = tick(clock.tickSeq());
        BigDecimal rate = tick.getUsdKrw();
        Map<Long, AsOfPrice> prices = pricesFor(positions, tick.getGameDate());
        requirePrices(positions, prices, tick.getGameDate());

        BigDecimal holdingsValuationKrw =
                AccountValuations.holdingsValuationKrw(positions, prices, rate);
        BigDecimal totalValuationKrw =
                AccountValuations.totalValuationKrw(account, rate, holdingsValuationKrw);

        warnIfDailyBaseStale(account, clock);

        // 수익률은 반올림 전 평가액으로 계산한다. 표현용으로 깎은 값에서 나누면 화면의 금액과
        // 비율이 서로를 설명하지 못한다.
        return AccountSummaryResponse.of(account, tick, holdingsValuationKrw, totalValuationKrw,
                AccountValuations.returnRate(totalValuationKrw, account.getStartingBalanceKrw()),
                AccountValuations.returnRate(totalValuationKrw, account.getDailyBaseValuationKrw()));
    }

    /**
     * 내 보유 포지션을 읽는다 (기획서 7.3).
     *
     * <p><b>비중의 분모는 계좌 총평가액이다</b> — 현금을 포함한다. 현금을 빼면 원화 절반을
     * 남겨 둔 사용자와 전액 투자한 사용자의 비중이 같아져, "내 포트폴리오에서 이 종목이
     * 차지하는 몫"이라는 질문에 답하지 못한다.
     *
     * @param userId 세션의 User 식별자
     * @return 공개 기준 틱으로 평가한 포지션 목록. 최초 공개 전에는 목록이 비어 있다
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다),
     *                           그 시즌에 참가하지 않아 계좌가 없을 때 (404),
     *                           평가에 필요한 종가가 적재되지 않았을 때 (500)
     */
    public PositionsResponse getPositions(Long userId) {
        GameClockSnapshot clock = replay.clock();
        Account account = account(userId);
        List<Position> positions = positions(account);

        if (clock.tickSeq() == null) {
            requireNothingToValue(account, positions);
            return PositionsResponse.beforeFirstPublish();
        }

        GameTick tick = tick(clock.tickSeq());
        BigDecimal rate = tick.getUsdKrw();
        Map<Long, AsOfPrice> prices = pricesFor(positions, tick.getGameDate());
        requirePrices(positions, prices, tick.getGameDate());

        // 비중의 분모가 총평가액이라 한 번 다 돌아 합계를 낸 뒤에 줄을 만든다.
        Map<Long, BigDecimal> valuationsKrw = new LinkedHashMap<>();
        BigDecimal holdingsValuationKrw = BigDecimal.ZERO;
        for (Position position : positions) {
            BigDecimal valuationKrw = AccountValuations.valuationKrw(
                    position, prices.get(position.getInstrumentId()), rate);
            valuationsKrw.put(position.getInstrumentId(), valuationKrw);
            holdingsValuationKrw = holdingsValuationKrw.add(valuationKrw);
        }
        BigDecimal totalValuationKrw =
                AccountValuations.totalValuationKrw(account, rate, holdingsValuationKrw);

        Map<Long, Integer> lockedQuantities = orderLocks.lockedSellQuantities(account.getId());
        List<PositionsResponse.Item> items = positions.stream()
                .map(position -> PositionsResponse.Item.of(position,
                        prices.get(position.getInstrumentId()),
                        lockedQuantities.getOrDefault(position.getInstrumentId(), 0),
                        rate,
                        valuationsKrw.get(position.getInstrumentId()),
                        totalValuationKrw))
                .toList();
        return PositionsResponse.of(tick, items);
    }

    /**
     * 세션의 User 가 그 시즌에 가진 계좌의 식별자. 계좌에 매달린 이력(체결·환전)을 읽는 쪽이
     * 대상을 좁히는 데 쓴다.
     *
     * <p>식별자만 돌려주는 이유는 <b>남의 것을 읽을 경로를 만들지 않기 위해서</b>다. 조회가
     * 계좌 식별자를 파라미터로 받으면 그 값을 검증할 책임이 부르는 쪽마다 생기는데, 세션에서
     * 유도하면 그 책임 자체가 없어진다 (기획서 11.6.2).
     *
     * @param userId 세션의 User 식별자
     * @return 그 시즌 계좌의 식별자
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    public Long accountId(Long userId) {
        return account(userId).getId();
    }

    /**
     * 잠기지 않은 결제 통화 현금. 주문 미리보기가 부족분을 재는 기준이다 ({@code ord.md}).
     *
     * <p>통화 하나만 돌려주는 이유는 <b>매수 검증이 결제 통화 기준</b>이기 때문이다 (기획서
     * 6.5.1). 원화가 1억 있어도 달러가 없으면 나스닥 매수는 거절되므로, 두 통화를 합친 값은
     * 어떤 판단의 근거도 되지 못한다.
     *
     * @param userId   세션의 User 식별자
     * @param currency 결제 통화
     * @return 잠기지 않은 그 통화의 현금
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    public BigDecimal availableCash(Long userId, Currency currency) {
        Account account = account(userId);
        return currency == Currency.USD ? account.availableUsd() : account.availableKrw();
    }

    /**
     * 일간 수익률의 기준값이 오늘 것인지 확인한다. {@code daily_base_season_day} 는 이 감지를
     * 위해 존재하는 컬럼이다 — 개장 갱신이 누락되면 기준값이 어제 것으로 남고, 일간 수익률에
     * 며칠치 성과가 실려 나가면서 일간 리더보드와도 어긋난다 (기획서 7.1.4 · 11.2.1).
     *
     * <p><b>던지지 않고 기록만 한다.</b> 개장 시점에 기준값을 갱신하는 코드가 아직 없어서
     * 지금 예외로 만들면 2·3일차의 계좌 화면이 통째로 막힌다. 갱신이 붙은 뒤에는 이 불일치가
     * 곧 버그이므로 그때 판정을 강화한다.
     */
    private void warnIfDailyBaseStale(Account account, GameClockSnapshot clock) {
        if (account.getDailyBaseSeasonDay() != clock.seasonDay()) {
            log.error("Daily base of account {} is from season day {} but the clock is on day {};"
                            + " dailyReturn spans more than today",
                    account.getId(), account.getDailyBaseSeasonDay(), clock.seasonDay());
        }
    }

    /** 세션의 User 가 그 시즌에 가진 계좌. 1인 1계좌를 UNIQUE 가 보장한다. */
    private Account account(Long userId) {
        return accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "시즌 계좌가 없습니다."));
    }

    /**
     * 수량이 남아 있는 포지션. 전량 매도한 행은 수량 0 으로 남을 수 있고 평가액에 아무것도
     * 더하지 않으므로 읽지 않는다.
     */
    private List<Position> positions(Account account) {
        return positionRepository.findByAccountIdAndQuantityGreaterThanOrderByInstrumentIdAsc(account.getId(), 0);
    }

    /**
     * 아직 어떤 틱도 공개되지 않은 구간 — 1일차 브리핑이다. 계좌는 이 구간에도 존재한다:
     * {@code sea.md} 의 참가 절차가 "1일차 개장 전이다 → 계좌 생성, 시드 1억(3.2.2)"이다.
     *
     * <p>거래·환전 게이트가 모두 닫혀 있어 원화 현금 외에는 아무것도 있을 수 없다. 있다면
     * 평가할 종가가 없으므로 총평가액을 만들 수 없고, 달러를 조용히 빼면 평가액과 순위가
     * 틀린 채 나간다.
     */
    private void requireNothingToValue(Account account, List<Position> positions) {
        if (account.getUsdCash().signum() != 0 || account.getLockedUsd().signum() != 0
                || !positions.isEmpty()) {
            log.error("Account {} holds valued assets before the first published tick", account.getId());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "공개된 종가가 없어 평가액을 계산할 수 없습니다.");
        }
    }

    /**
     * 평가에 필요한 종가가 모두 있는지 확인한다. 시세 적재가 끝나지 않은 종목을 보유한 경우를
     * 여기서 드러낸다 — 0 으로 넘기면 평가액과 순위가 조용히 틀린다.
     *
     * <p>매 틱 스윕은 같은 상황에서 그 계좌만 건너뛴다 ({@code AccountValuationService}).
     * 요청 하나를 답하는 이 경로와 정책이 다른 것은 의도한 것이다.
     */
    private void requirePrices(List<Position> positions, Map<Long, AsOfPrice> prices,
                               LocalDate asOfGameDate) {
        List<Long> missing = AccountValuations.missingPrices(positions, prices);
        if (!missing.isEmpty()) {
            log.error("No close price loaded for instruments {} as of {}", missing, asOfGameDate);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보유 종목의 시세가 없어 평가액을 계산할 수 없습니다.");
        }
    }

    /** 보유 종목의 공개 기준 가격. 휴장 이월은 시장 도메인의 규칙이다 ({@code mkt.md}). */
    private Map<Long, AsOfPrice> pricesFor(List<Position> positions, LocalDate asOfGameDate) {
        return marketPrices.pricesAsOf(
                positions.stream().map(Position::getInstrumentId).toList(), asOfGameDate);
    }





    /** 시계가 공개했다고 말한 틱. 편성에 없으면 재생 상태와 편성이 어긋난 것이다. */
    private GameTick tick(Long tickSeq) {
        return gameTicks.find(tickSeq).orElseThrow(() -> {
            log.error("Published tick {} is absent from game_ticks (season {})", tickSeq, seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "공개 기준 틱을 찾을 수 없습니다.");
        });
    }
}
