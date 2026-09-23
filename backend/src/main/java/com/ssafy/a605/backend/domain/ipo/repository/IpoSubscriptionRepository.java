package com.ssafy.a605.backend.domain.ipo.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.market.entity.Currency;

public interface IpoSubscriptionRepository
        extends JpaRepository<IpoSubscription, IpoSubscription.Pk> {

    Optional<IpoSubscription> findByOfferingIdAndAccountId(Long offeringId, Long accountId);

    /**
     * 한 공모의 청약 전부. 추첨이 이 목록을 섞는다.
     *
     * @param offeringId 대상 공모
     * @return 신청 순서대로. 추첨은 무작위지만, 순서가 정해져 있어야 같은 입력에서 같은 결과를
     *         재현해 볼 수 있다
     */
    List<IpoSubscription> findByOfferingIdOrderBySubscribedAtAscAccountIdAsc(Long offeringId);

    /**
     * 내가 낸 청약들. 목록 응답이 공모마다 {@code mySubscription} 을 담으므로 한 번에 읽는다.
     *
     * @param accountId   내 계좌
     * @param offeringIds 화면에 실릴 공모들
     * @return 그중 내가 청약한 것
     */
    List<IpoSubscription> findByAccountIdAndOfferingIdIn(Long accountId, Collection<Long> offeringIds);

    /**
     * 공모별 청약자 수 (기획서 8.3.1). 경쟁률과 당첨 확률이 이 값 하나에서 나온다.
     *
     * @param offeringIds 세어야 할 공모들
     * @return {@code [공모 식별자, 청약자 수]}. <b>청약자가 없는 공모는 행이 없다</b> —
     *         호출부가 0 으로 채운다
     */
    @Query("""
            select s.offeringId, count(s)
            from IpoSubscription s
            where s.offeringId in :offeringIds
            group by s.offeringId
            """)
    List<Object[]> countByOfferingIds(Collection<Long> offeringIds);

    /**
     * 그 계좌가 부재 구간에 <b>포지션으로 받은</b> 배정. 구간 시작 시점의 현금·수량을 되짚는 데
     * 쓴다 ({@code acc.md} §부재 요약).
     *
     * <p>전환은 공모의 상장 틱에 일어나므로 그 틱으로 거른다. 배정 수량이 0 인 당첨은 옮긴 것이
     * 없어 제외한다.
     *
     * @param accountId 대상 계좌
     * @param from      구간 시작. 포함하지 않는다
     * @param to        구간 끝. 포함한다
     * @return 그 구간에 포지션이 된 배정
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository$Converted(
                o.instrumentId, o.currency, s.allocatedQuantity, s.allocatedAmount)
            FROM IpoSubscription s, IpoOffering o
            WHERE s.offeringId = o.id
              AND s.accountId = :accountId
              AND s.won = true
              AND s.allocatedQuantity > 0
              AND o.listingTickSeq > :from AND o.listingTickSeq <= :to
            """)
    List<Converted> findConvertedBetween(@Param("accountId") Long accountId,
                                         @Param("from") long from, @Param("to") long to);

    /**
     * 상장 시점에 포지션이 된 배정 한 건.
     *
     * @param instrumentId 상장한 종목
     * @param currency     결제 통화. 현금이 어느 지갑에서 빠졌는지를 정한다
     * @param quantity     배정 수량
     * @param amount       배정 금액. 그때 현금에서 빠진 값이자 취득 총액이다
     */
    record Converted(Long instrumentId, Currency currency, Integer quantity, BigDecimal amount) {
    }

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
