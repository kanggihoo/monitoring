package com.ssafy.a605.backend.domain.ipo.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.service.AccountLedgerService;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;

import lombok.RequiredArgsConstructor;

/**
 * 상장 전환 (기획서 8.5.2). 상장 틱에 당첨 물량을 포지션으로 바꾼다.
 *
 * <p><b>여기서 처음으로 현금이 실제로 빠진다.</b> 청약은 잠그기만 했고 추첨은 잔액만 풀었다 —
 * 배정액은 상장까지 잠긴 채였고, 이 자리에서 잠금 해제와 지출이 함께 일어나 포지션이 된다.
 * 예약 매수의 체결과 같은 모양이라 {@code AccountLedgerService#settleLockedBuy} 를 그대로 쓴다.
 *
 * <p><b>평단을 따로 만들지 않는다.</b> 취득 총액에 배정액을 넣으면 평단이 자동으로 공모가가
 * 된다 — {@code acc.md} 가 평단을 저장하지 않고 {@code totalCost ÷ quantity} 로 계산하기
 * 때문이다. 그래서 상장일 첫 종가와 공모가의 차이가 그대로 첫 평가손익이 된다 (8.5.3).
 *
 * <p><b>공모 하나가 한 트랜잭션이다.</b> {@link IpoDrawService} 와 같은 이유이며, 한 계좌가
 * 깨지면 그 공모 전체가 되돌아간다. 계좌를 식별자 순서로 잠그는 것도 같다 — 두 스윕이 같은
 * 순서를 쓰지 않으면 교착한다.
 *
 * <p><b>전환은 상장 틱에 한 번만 시도한다.</b> 기획서 8.5.2 가 정한 시점이 그 틱이고, 명세는
 * 그 시점을 놓쳤을 때를 말하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IpoListingService {

    private final IpoOfferingRepository offeringRepository;
    private final IpoSubscriptionRepository subscriptionRepository;
    private final AccountLedgerService ledger;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 이번 틱에 상장하는 공모 (기획서 8.5.2).
     *
     * <p>이 목록은 <b>후보</b>다. 잠금 없이 읽었으므로 {@link #list} 가 공모를 잠근 뒤 배정
     * 여부를 다시 확인한다.
     *
     * @param tickSeq 방금 공개된 틱
     * @return 후보 공모 식별자
     */
    @Transactional(readOnly = true)
    public List<Long> offeringsToList(long tickSeq) {
        return offeringRepository.findBySeasonIdAndListingTickSeqAndDrawnAtIsNotNull(seasonId, tickSeq)
                .stream().map(IpoOffering::getId).toList();
    }

    /**
     * 한 공모의 당첨 물량을 포지션으로 옮긴다.
     *
     * @param offeringId 대상 공모
     * @return 상장한 공모와 종목. 추첨 전이면 {@code null}
     */
    @Transactional
    public Listed list(Long offeringId) {
        IpoOffering offering = offeringRepository.findWithLockById(offeringId).orElse(null);
        if (offering == null || offering.getDrawnAt() == null) {
            return null;
        }
        for (IpoSubscription subscription : winners(offeringId)) {
            Account account = ledger.lockedAccountById(subscription.getAccountId());
            // 잠긴 배정액이 그대로 취득 총액이 된다. 두 값이 같아야 잠금이 남거나 모자라지 않는다.
            ledger.settleLockedBuy(account, offering.getInstrumentId(),
                    subscription.getAllocatedQuantity(), offering.getCurrency(),
                    subscription.getAllocatedAmount(), subscription.getAllocatedAmount());
        }
        return new Listed(offeringId, offering.getInstrumentId());
    }

    /**
     * 옮길 것이 있는 청약. <b>당첨이어도 배정 수량이 0 이면 건너뛴다</b> — 증거금이 공모가에
     * 못 미쳐 한 주도 못 받은 경우이며, 그 증거금은 추첨 때 전액 반환됐으므로 잠긴 것도 없다.
     */
    private List<IpoSubscription> winners(Long offeringId) {
        List<IpoSubscription> winners = new ArrayList<>(
                subscriptionRepository.findByOfferingIdOrderBySubscribedAtAscAccountIdAsc(offeringId)
                        .stream()
                        .filter(subscription -> Boolean.TRUE.equals(subscription.getWon()))
                        .filter(subscription -> subscription.getAllocatedQuantity() > 0)
                        .toList());
        winners.sort(Comparator.comparing(IpoSubscription::getAccountId));
        return winners;
    }

    /**
     * 상장한 공모.
     *
     * @param offeringId   상장한 공모
     * @param instrumentId 이제 거래할 수 있게 된 종목
     */
    public record Listed(Long offeringId, Long instrumentId) {
    }
}
