package com.ssafy.a605.backend.domain.ipo.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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
import com.ssafy.a605.backend.domain.ipo.dto.InsufficientDeposit;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingsResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOfferingStatus;
import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.PhaseGate;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 공모주 청약 (기획서 8.1~8.3, {@code ipo.md}). 공시 조회와 신청·취소를 맡고, 마감 추첨은
 * {@link IpoDrawService} 가 틱마다 한다.
 *
 * <p><b>청약에는 미리보기가 없다.</b> 신청 단위가 고정이고 수량·금액 선택이 없어 사용자가
 * 계산해 볼 값이 없다 (기획서 8.2.1). 그래서 잔고 부족은 신청을 눌러야 드러나고, 거부 응답이
 * 부족분과 환전 요청을 함께 싣는다 ({@link InsufficientDeposit}).
 *
 * <p><b>조회와 신청의 트랜잭션 요구가 다르다.</b> 조회는 시계·공모·청약자 수·내 청약을 한
 * 응답으로 합치므로 클래스 기본값인 {@code REPEATABLE_READ} 로 읽고, 현금을 잠그는
 * {@link #subscribe} 와 {@link #cancel} 은 같은 계좌에 대한 동시 갱신이 직렬화 실패로 거부되면
 * 안 되므로 <b>행 잠금 + 기본 격리</b>로 따로 선언한다. {@code OrderService} 가 같은 판단을 한다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class IpoService {

    private final IpoOfferingRepository offeringRepository;
    private final IpoSubscriptionRepository subscriptionRepository;
    private final IpoOfferingDescriber describer;
    private final AccountService accounts;
    private final AccountLedgerService ledger;
    private final ExchangeService exchanges;
    private final GameTickCatalog gameTicks;
    private final PhaseGate phases;
    private final ReplayService replay;

    /** 실제 시각. 게임 시계와 헷갈리지 않게 이름을 나눠 뒀다. */
    private final Clock wallClock;

    private final long seasonId;

    public IpoService(IpoOfferingRepository offeringRepository,
                      IpoSubscriptionRepository subscriptionRepository,
                      IpoOfferingDescriber describer, AccountService accounts,
                      AccountLedgerService ledger, ExchangeService exchanges,
                      GameTickCatalog gameTicks, PhaseGate phases, ReplayService replay,
                      Clock wallClock, @Value("${app.replay.season-id:1}") long seasonId) {
        this.offeringRepository = offeringRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.describer = describer;
        this.accounts = accounts;
        this.ledger = ledger;
        this.exchanges = exchanges;
        this.gameTicks = gameTicks;
        this.phases = phases;
        this.replay = replay;
        this.wallClock = wallClock;
        this.seasonId = seasonId;
    }

    /**
     * 공시된 공모 목록 (기획서 8.1 · 8.3).
     *
     * <p><b>개장 전에는 빈 목록이다.</b> 공개된 틱이 없으면 아무것도 공시되지 않은 것이고,
     * 그때 편성 전체를 보여주면 종목명이 새어 나간다 (기획서 3.5.2).
     *
     * @param userId 보는 사람
     * @return 공시 순서의 공모들
     */
    public IpoOfferingsResponse offerings(Long userId) {
        GameClockSnapshot clock = replay.clock();
        if (clock.tickSeq() == null) {
            return new IpoOfferingsResponse(null, List.of());
        }
        List<IpoOffering> offerings = disclosed(clock);
        return new IpoOfferingsResponse(clock.tickSeq(),
                describer.describe(offerings, accounts.accountId(userId), clock, false));
    }

    /**
     * 공시 상세 (기획서 8.1). 목록 항목의 모든 필드에 공시 본문이 더해진다.
     *
     * @param userId     보는 사람
     * @param offeringId 대상 공모
     * @return 공시 상세
     * @throws BusinessException 공시 전이거나 없는 공모면 (404)
     */
    public IpoOfferingResponse offering(Long userId, Long offeringId) {
        GameClockSnapshot clock = replay.clock();
        IpoOffering offering = disclosedOffering(offeringId, clock);
        return describer.describe(offering, accounts.accountId(userId), clock, true);
    }

    /**
     * 청약을 신청한다 (기획서 8.2). 증거금 100% 가 결제 통화 현금에서 즉시 잠긴다.
     *
     * <p><b>멱등이다.</b> 이미 청약한 공모면 잠그지 않고 기존 청약을 그대로 돌려준다 — 1인
     * 1청약이라 두 번째 요청이 만들 것이 없고, 중복 클릭에 증거금이 두 번 잠기면 안 된다.
     *
     * <p><b>공모와 계좌를 그 순서로 잠근 뒤에야 청약을 읽는다.</b> 공모 잠금은 마감 추첨과의
     * 경합을, 계좌 잠금은 취소와의 경합을 막는다 — 세 경로가 모두 같은 순서로 같은 행들을
     * 잠그므로, 잠금을 쥔 뒤 읽은 결과는 트랜잭션이 끝날 때까지 바뀌지 않는다.
     *
     * @param userId     신청하는 사람
     * @param offeringId 대상 공모
     * @return 만들었는지와 청약. 이미 있었으면 {@code created} 가 거짓이다
     * @throws BusinessException 거래 시간이 아니거나(409), 공시 전·없는 공모거나(404),
     *                          마감된 공모거나(409), 결제 통화 잔고가 모자랄 때(409)
     */
    @Transactional
    public Subscribed subscribe(Long userId, Long offeringId) {
        GameClockSnapshot clock = phases.requireTrading();
        IpoOffering offering = lockedOpenOffering(offeringId, clock);
        Account account = ledger.lockedTradingAccount(userId, clock.seasonDay());

        IpoSubscription existing = subscriptionRepository
                .findByOfferingIdAndAccountId(offeringId, account.getId()).orElse(null);
        if (existing != null) {
            return new Subscribed(false, describe(existing, offering));
        }

        BigDecimal deposit = offering.getDepositAmount();
        requireEnoughCash(account, offering.getCurrency(), deposit, clock);
        ledger.lockCash(account, offering.getCurrency(), deposit);

        IpoSubscription subscription = subscriptionRepository.save(new IpoSubscription(
                offeringId, account.getId(), deposit, Instant.now(wallClock)));
        return new Subscribed(true, describe(subscription, offering));
    }

    /**
     * 청약을 취소한다 (기획서 8.2.4). 증거금이 즉시 반환된다.
     *
     * <p><b>청약하지 않은 공모에도 성공으로 답한다.</b> 목표 상태(청약 없음)가 이미 성립하므로,
     * 중복 클릭이나 재시도가 오류로 보일 이유가 없다.
     *
     * @param userId     취소하는 사람
     * @param offeringId 대상 공모
     * @throws BusinessException 거래 시간이 아니거나(409), 공시 전·없는 공모거나(404),
     *                          마감된 공모일 때(409)
     */
    @Transactional
    public void cancel(Long userId, Long offeringId) {
        GameClockSnapshot clock = phases.requireTrading();
        IpoOffering offering = lockedOpenOffering(offeringId, clock);
        Account account = ledger.lockedAccount(userId);

        subscriptionRepository.findByOfferingIdAndAccountId(offeringId, account.getId())
                .ifPresent(subscription -> {
                    ledger.unlockCash(account, offering.getCurrency(), subscription.getDepositAmount());
                    subscriptionRepository.delete(subscription);
                });
    }

    /**
     * 신청·취소가 가능한 공모. <b>마감 이후에는 둘 다 막힌다</b> (기획서 8.2.5) — 추첨이 이미
     * 돌았거나 도는 중이라, 그 뒤의 신청은 확정된 결과를 흔들고 취소는 배정된 물량을 되돌린다.
     *
     * <p><b>공모 행을 잠그고 판정한다.</b> 잠그지 않으면 마감 틱을 사이에 두고 창이 열린다 —
     * 재생은 <b>커밋한 뒤 이벤트를 발행</b>하므로, 마감 틱이 공개된 순간과 추첨 스윕이 도는
     * 순간 사이에 틈이 있다. 그 틈에 들어온 신청은 이미 끝난 추첨에서 빠져 <b>증거금이 영원히
     * 잠기고</b>, 취소는 이미 정산된 청약을 지워 배정을 되돌린다.
     *
     * <p><b>잠그는 순서가 {@link IpoDrawService} 와 같다</b> — 공모 다음 계좌다. 뒤집으면 추첨
     * 스윕과 신청이 서로의 잠금을 기다리며 교착한다.
     */
    private IpoOffering lockedOpenOffering(Long offeringId, GameClockSnapshot clock) {
        // 공시 여부와 소속 시즌을 먼저 본다. 없는 공모를 잠그려 기다릴 이유가 없다.
        disclosedOffering(offeringId, clock);
        IpoOffering offering = offeringRepository.findWithLockById(offeringId)
                .orElseThrow(IpoService::notFound);
        if (offering.statusAt(clock.tickSeq()) != IpoOfferingStatus.OPEN) {
            throw new BusinessException(HttpStatus.CONFLICT, "청약이 마감된 공모입니다.");
        }
        return offering;
    }

    /**
     * 공시된 공모 하나.
     *
     * <p><b>공시 전 공모는 404 다.</b> 403 으로 답하면 그 식별자의 공모가 존재한다는 사실이
     * 드러나고, 그것이 곧 편성 정보다 — 남의 예약을 404 로 답하는 것과 같은 판단이다.
     */
    private IpoOffering disclosedOffering(Long offeringId, GameClockSnapshot clock) {
        if (clock.tickSeq() == null) {
            throw notFound();
        }
        return offeringRepository.findById(offeringId)
                .filter(found -> found.getSeasonId() == seasonId)
                .filter(found -> found.isDisclosedAt(clock.tickSeq()))
                .orElseThrow(IpoService::notFound);
    }

    private List<IpoOffering> disclosed(GameClockSnapshot clock) {
        return offeringRepository
                .findBySeasonIdAndDisclosureTickSeqLessThanEqualOrderByDisclosureTickSeqAsc(
                        seasonId, clock.tickSeq());
    }

    /**
     * 잠글 현금이 있는지 본다. 없으면 <b>부족분과 그것을 채우는 환전 요청</b>을 거부에 실어
     * 보낸다 (기획서 8.2.3).
     *
     * <p>{@code AccountLedgerService#lockCash} 도 같은 부족을 막지만 그쪽은 금액만 안다.
     * 환전 안내를 만들려면 부족분을 여기서 먼저 계산해야 한다.
     */
    private void requireEnoughCash(Account account, Currency currency, BigDecimal deposit,
                                   GameClockSnapshot clock) {
        BigDecimal available = currency == Currency.USD
                ? account.availableUsd() : account.availableKrw();
        BigDecimal shortfall = deposit.subtract(available);
        if (shortfall.signum() <= 0) {
            return;
        }
        ExchangeDirection direction = currency == Currency.USD
                ? ExchangeDirection.KRW_TO_USD : ExchangeDirection.USD_TO_KRW;
        BigDecimal rate = rate(clock);
        InsufficientDeposit data = InsufficientDeposit.of(currency, shortfall, ShortfallExchange.of(
                direction, exchanges.shortfallSendAmount(direction, shortfall, rate)));
        throw BusinessException.with(HttpStatus.CONFLICT,
                currency == Currency.USD ? "달러 잔고가 부족합니다." : "원화 잔고가 부족합니다.", data);
    }

    /** 그 게임 거래일의 환율. 환전 안내가 실제 환전과 같은 값을 써야 한다 (기획서 7.2 비고). */
    private BigDecimal rate(GameClockSnapshot clock) {
        GameTick tick = gameTicks.find(clock.tickSeq())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "시세가 아직 공개되지 않았습니다."));
        return tick.getUsdKrw();
    }

    private IpoSubscriptionResponse describe(IpoSubscription subscription, IpoOffering offering) {
        return IpoSubscriptionResponse.of(subscription, offering.getCurrency(), offering.getDrawnAt());
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "공모를 찾을 수 없습니다.");
    }

    /**
     * 신청 결과. 이미 청약이 있었으면 아무것도 만들지 않았다는 사실을 상태 코드로 알려야 하므로
     * ({@code 201} 과 {@code 200}) 컨트롤러가 그것을 알아야 한다.
     *
     * @param created      이번 요청이 청약을 만들었는가
     * @param subscription 내 청약
     */
    public record Subscribed(boolean created, IpoSubscriptionResponse subscription) {
    }
}
