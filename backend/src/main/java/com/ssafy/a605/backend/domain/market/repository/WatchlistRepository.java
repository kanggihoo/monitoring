package com.ssafy.a605.backend.domain.market.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.market.entity.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, Watchlist.Pk> {

    /**
     * 한 User 가 <b>이 시즌에</b> 담아 둔 관심종목.
     *
     * <p>관심종목은 계좌가 아니라 User 에 붙으므로 행 자체는 시즌을 넘어 남는다 — 취향은
     * 사람의 것이다. 다만 <b>화면에 내보낼 때는</b> 지금 시즌의 종목만 보여야 한다: 지난 시즌의
     * 종목 식별자를 그대로 내보내면 프론트가 목록에 없는 번호를 받아 빈 줄을 그린다.
     *
     * @param userId   세션의 User 식별자
     * @param seasonId 진행 중인 시즌
     * @return 종목 식별자 순의 관심종목. 없으면 빈 목록
     */
    @Query("SELECT w FROM Watchlist w WHERE w.userId = :userId"
            + " AND w.instrumentId IN (SELECT i.id FROM Instrument i WHERE i.seasonId = :seasonId)"
            + " ORDER BY w.instrumentId ASC")
    List<Watchlist> findBySeason(@Param("userId") Long userId, @Param("seasonId") Long seasonId);

    /**
     * 한 User 의 관심종목 전부를 지운다. 탈퇴가 쓴다 ({@code WatchlistService#deleteByUser}).
     *
     * @param userId 대상 User 식별자
     */
    void deleteByUserId(Long userId);
}
