package com.ssafy.a605.backend.domain.report.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.report.entity.SeasonReport;

/**
 * 시즌 리포트 1행 = 계좌. 3일차 폐장에 한 번 쓰이고 그 뒤로는 읽기만 한다.
 */
public interface SeasonReportRepository extends JpaRepository<SeasonReport, Long> {

    /**
     * 이 계좌들 중 이미 확정된 행이 있는가. 확정의 멱등을 이것으로 판단한다 —
     * {@code count() > 0} 으로 보면 다음 시즌의 첫 확정이 이전 시즌 행 때문에 건너뛰어진다
     * (이 테이블은 계좌만 키로 쓰고 시즌 컬럼이 없다).
     *
     * @param accountIds 확정하려는 계좌
     * @return 하나라도 이미 있으면 참
     */
    boolean existsByAccountIdIn(Collection<Long> accountIds);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
