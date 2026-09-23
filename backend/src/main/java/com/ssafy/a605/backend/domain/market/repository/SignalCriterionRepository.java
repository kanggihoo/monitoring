package com.ssafy.a605.backend.domain.market.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.market.entity.SignalCriterion;

public interface SignalCriterionRepository extends JpaRepository<SignalCriterion, Long> {

    /**
     * 판정 기준 9행 전부. 도움말 조회와 종목 카드의 신호등 판정이 같은 목록을 읽는다 —
     * 판정과 공개가 서로 다른 행을 보면 화면의 설명이 실제 판정을 설명하지 못한다.
     *
     * @return 도움말 표시 순서의 기준 전부
     */
    List<SignalCriterion> findAllByOrderBySortOrderAsc();
}
