package com.ssafy.a605.backend.domain.report.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.report.entity.SeasonReportText;

public interface SeasonReportTextRepository extends JpaRepository<SeasonReportText, Long> {

    /**
     * 이 계좌들 중 문구가 있는 계좌가 있는가. 발표 경계의 재시도가 이것으로 "한 번이라도
     * 받았는가" 를 가린다.
     *
     * <p>{@code count()} 로 보지 않는 이유는 이 테이블에 시즌 컬럼이 없기 때문이다 —
     * 다음 시즌의 첫 재시도가 이전 시즌 행 때문에 건너뛰어진다.
     *
     * @param accountIds 그 시즌 확정에 든 계좌
     * @return 하나라도 있으면 참
     */
    boolean existsByAccountIdIn(Collection<Long> accountIds);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
