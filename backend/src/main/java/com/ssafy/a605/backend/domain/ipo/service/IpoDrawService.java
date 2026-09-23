package com.ssafy.a605.backend.domain.ipo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.service.AccountLedgerService;
import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;
import com.ssafy.a605.backend.domain.market.entity.Currency;

import lombok.RequiredArgsConstructor;

/**
 * 마감 추첨 (기획서 8.4). 마감 틱이 지난 공모를 무작위로 뽑고, 낙첨 증거금과 당첨자의 배정
 * 잔액을 즉시 돌려준다.
 *
 * <p><b>공모 하나가 한 트랜잭션이다.</b> 체결·폐장 만료가 계좌마다 트랜잭션을 나누는 것과
 * 다른데, 추첨은 <b>나눌 수 없는 하나의 사건</b>이기 때문이다 — 당첨자 절반만 확정된 채 커밋되면
 * 남은 청약자들은 이미 뽑힌 사람 수를 모른 채 다시 뽑혀야 하고, 공모의 "N명 당첨" 공지가
 * 무엇을 세는지가 사라진다. 한 계좌에서 실패하면 그 공모 전체가 되돌아가고, 아직 추첨되지
 * 않았으므로 다음 틱의 스윕이 처음부터 다시 한다.
 *
 * <p><b>계좌를 식별자 순서로 잠근다.</b> 한 트랜잭션이 여러 계좌를 잠그는 유일한 자리라,
 * 순서를 정해 두지 않으면 다른 경로와 엇갈려 교착에 빠질 수 있다.
 *
 * <p><b>당첨자의 배정액은 잠긴 채로 둔다.</b> 상장 시점에 포지션으로 바뀌기 때문이다
 * (기획서 8.5.2) — 추첨 자리에서 현금을 빼면 상장까지의 12분 동안 계좌에서 돈이 사라졌다가
 * 자산으로 돌아온다. 잠금은 총평가액을 건드리지 않으므로 순위도 흔들리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IpoDrawService {

    private final IpoOfferingRepository offeringRepository;
    private final IpoSubscriptionRepository subscriptionRepository;
    private final AccountLedgerService ledger;
    private final IpoOfferingDescriber describer;

    /** 추첨. 모든 청약자의 당첨 확률이 같아야 하므로 섞어서 앞에서 끊는다 (기획서 8.4.1). */
    private final Random random = new SecureRandom();

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 그 시즌 공모의 <b>추첨 결과만</b> 지워 다시 청약·추첨 가능한 상태로 되돌린다
     * (개발·테스트 전용 시즌 초기화).
     *
     * <p>공시·마감·상장 틱은 시즌 편성이라 손대지 않는다. 결과만 남으면 마감 틱을 다시 지나도
     * {@link #draw} 의 "이미 추첨했으면 건너뛴다" 판정에 걸려 재추첨이 일어나지 않는다.
     *
     * @param seasonId 초기화할 시즌
     */
    @Transactional
    public void rearmSeason(long seasonId) {
        offeringRepository.findBySeasonId(seasonId).forEach(IpoOffering::rearm);
    }

    /**
     * 이번 틱에 마감된 공모 (기획서 8.4.1 — 마감 즉시 추첨한다).
     *
     * <p>이 목록은 <b>후보</b>다. 잠금 없이 읽었으므로 {@link #draw} 가 공모를 잠근 뒤 추첨
     * 여부를 다시 확인한다.
     *
     * @param tickSeq 방금 공개된 틱
     * @return 후보 공모 식별자
     */
    @Transactional(readOnly = true)
    public List<Long> offeringsToDraw(long tickSeq) {
        return offeringRepository.findBySeasonIdAndCloseTickSeq(seasonId, tickSeq)
                .stream().map(IpoOffering::getId).toList();
    }

    /**
     * 한 공모를 추첨하고 증거금을 정리한다.
     *
     * <p><b>통보할 내용을 트랜잭션 안에서 만든다.</b> 당락은 청약 행에 저장되지만 그것을 다시
     * 읽어 통보를 만들면 그 사이 상장 전환이 같은 행을 건드릴 수 있고, 무엇보다 커밋 전에
     * 만들어 두어야 "보낸 내용과 저장된 내용이 같다"가 성립한다.
     *
     * @param offeringId 대상 공모
     * @param at         추첨 실제 시각
     * @return 확정된 당첨자 수와 개인별 통보. 이미 뽑힌 공모였으면 {@code null}
     */
    @Transactional
    public Drawn draw(Long offeringId, Instant at) {
        IpoOffering offering = offeringRepository.findWithLockById(offeringId).orElse(null);
        if (offering == null || offering.getDrawnAt() != null) {
            return null;
        }
        List<IpoSubscription> subscriptions =
                subscriptionRepository.findByOfferingIdOrderBySubscribedAtAscAccountIdAsc(offeringId);
        Set<Long> winners = pick(subscriptions);

        List<Awarded> awarded = new ArrayList<>(subscriptions.size());
        for (IpoSubscription subscription : byAccount(subscriptions)) {
            Account account = settle(subscription, offering, winners.contains(subscription.getAccountId()));
            awarded.add(new Awarded(account.getUserId(),
                    IpoSubscriptionResponse.of(subscription, offering.getCurrency(), at).result()));
        }
        offering.draw(winners.size(), at);
        return new Drawn(offeringId, winners.size(), awarded);
    }

    /**
     * 당첨자를 뽑는다. 당첨 예정 인원은 청약자 수의 30%(반올림, 최소 1)이고, 청약자가 그 이하면
     * 전원 당첨된다 (기획서 8.4.2).
     */
    private Set<Long> pick(List<IpoSubscription> subscriptions) {
        int winnerCount = IpoOfferingDescriber.expectedWinnerCount(subscriptions.size(),
                describer.winnerRatio());
        List<IpoSubscription> pool = new ArrayList<>(subscriptions);
        if (winnerCount < pool.size()) {
            Collections.shuffle(pool, random);
            pool = pool.subList(0, winnerCount);
        }
        return pool.stream().map(IpoSubscription::getAccountId).collect(Collectors.toSet());
    }

    /**
     * 한 건을 정산한다. 낙첨은 증거금 전액이 즉시 돌아오고(기획서 8.4.4), 당첨은 배정하고 남은
     * 잔액만 돌아온다 (8.5.1).
     */
    private Account settle(IpoSubscription subscription, IpoOffering offering, boolean won) {
        Account account = ledger.lockedAccountById(subscription.getAccountId());
        Currency currency = offering.getCurrency();
        BigDecimal deposit = subscription.getDepositAmount();

        if (!won) {
            ledger.unlockCash(account, currency, deposit);
            subscription.settle(false, 0, BigDecimal.ZERO, deposit);
            return account;
        }
        // 수량만 내림한다 (기획서 8.5.1 — 증거금 ÷ 공모가, 정수 내림).
        int quantity = deposit.divide(offering.getOfferPrice(), 0, RoundingMode.FLOOR).intValueExact();
        // 배정액은 통화 자릿수로 맞추기만 한다. 수량이 이미 내려간 뒤라 잠긴 증거금을 넘지
        // 않으며, 반올림 방향은 다른 금액 계산과 같다 (OrderQuote).
        BigDecimal allocated = offering.getOfferPrice().multiply(BigDecimal.valueOf(quantity))
                .setScale(currency.scale(), RoundingMode.HALF_UP);
        BigDecimal refunded = deposit.subtract(allocated);
        if (refunded.signum() > 0) {
            ledger.unlockCash(account, currency, refunded);
        }
        subscription.settle(true, quantity, allocated, refunded);
        return account;
    }

    /** 교착을 피하려고 계좌 식별자 순서로 잠근다. 청약 목록의 순서는 신청 순이라 계좌 순이 아니다. */
    private static List<IpoSubscription> byAccount(List<IpoSubscription> subscriptions) {
        List<IpoSubscription> ordered = new ArrayList<>(subscriptions);
        ordered.sort(Comparator.comparing(IpoSubscription::getAccountId));
        return ordered;
    }

    /**
     * 한 공모의 추첨 결과.
     *
     * @param offeringId  추첨한 공모
     * @param winnerCount 확정된 당첨자 수. 전체 공지의 "○○○명 당첨"이다
     * @param awarded     청약자 전원의 개인 통보. 낙첨도 들어 있다 — 떨어졌다는 사실도 알려야
     *                    증거금이 왜 돌아왔는지 화면이 설명할 수 있다
     */
    public record Drawn(Long offeringId, int winnerCount, List<Awarded> awarded) {
    }

    /**
     * 한 사람에게 갈 결과.
     *
     * @param userId 청약자
     * @param result 당락과 배정·반환
     */
    public record Awarded(Long userId, IpoSubscriptionResponse.Result result) {
    }
}
