package com.ssafy.a605.backend.domain.account.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.account.entity.Exchange;

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    /**
     * 그 계좌의 환전 이력. 정렬은 호출 측이 최신순으로 고정한다.
     *
     * @param accountId 대상 계좌
     * @param pageable  페이지와 정렬
     * @return 환전 한 페이지
     */
    Page<Exchange> findByAccountId(Long accountId, Pageable pageable);

    /**
     * 부재 구간의 환전 전부. 구간 시작 시점의 두 지갑을 되짚는 데 쓴다.
     *
     * <p>부재는 "확인하지 않았다"이지 "아무것도 하지 않았다"가 아니다 — 모달을 닫지 않은 채
     * 거래하는 사용자가 있으므로 이 구간에도 환전이 들어올 수 있다.
     *
     * @param accountId 대상 계좌
     * @param from      구간 시작. 포함하지 않는다
     * @param to        구간 끝. 포함한다
     * @return 그 구간의 환전
     */
    List<Exchange> findByAccountIdAndTickSeqGreaterThanAndTickSeqLessThanEqual(
            Long accountId, Long from, Long to);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
