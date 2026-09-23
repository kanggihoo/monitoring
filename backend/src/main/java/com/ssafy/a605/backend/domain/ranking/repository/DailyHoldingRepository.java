package com.ssafy.a605.backend.domain.ranking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.ranking.entity.DailyHolding;

/**
 * 일차 마감 보유 스냅샷 조회 (V42).
 *
 * <p>시즌으로 좁히는 조회가 없다. 이 테이블을 읽는 두 경로가 모두 계좌를 이미 손에 들고
 * 있고({@code AccountPurgeService} 의 삭제, 복기 화면의 계좌별 조회), 계좌 자체가 한 시즌에
 * 속하기 때문이다 — {@link DailySettlementRepository} 가 일차로 묻는 조회에만 시즌을 받는
 * 것과 같은 이유다.
 */
public interface DailyHoldingRepository extends JpaRepository<DailyHolding, DailyHolding.Pk> {

    /**
     * 그 계좌의 그 일차 마감 보유.
     *
     * @param seasonDay 일차
     * @param accountId 대상 계좌
     * @return 보유 행. 그날 아무것도 들고 있지 않았으면 비어 있다
     */
    List<DailyHolding> findBySeasonDayAndAccountId(Short seasonDay, Long accountId);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴와 시즌 초기화가 쓴다 ({@code AccountPurgeService}).
     *
     * <p><b>{@code daily_settlements} 보다 먼저 지워야 한다.</b> 이 테이블이 확정 행을
     * 복합 외래키로 참조한다.
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
