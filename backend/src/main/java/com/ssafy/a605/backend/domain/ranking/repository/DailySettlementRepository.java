package com.ssafy.a605.backend.domain.ranking.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;

/**
 * 폐장 확정 행 조회.
 *
 * <p><b>일차만으로는 시즌을 가리지 못한다.</b> {@code daily_settlements} 의 기본키는
 * {@code (season_day, account_id)} 이고 일차는 시즌마다 1~3 으로 다시 시작한다. 시즌이 둘이 된
 * 뒤로는 {@code season_day = 1} 만 묻는 조회가 시즌 1 의 1 일차 행을 함께 집는다 — 시즌 2 첫
 * 폐장에서 "이미 확정됐다" 로 읽혀 정산이 통째로 건너뛰어졌다 (S15P21A605-286).
 *
 * <p>그래서 <b>일차를 받는 조회는 전부 시즌을 함께 받는다.</b> 계좌가 {@code (user_id, season_id)}
 * UNIQUE 라 시즌마다 다른 행이고, {@code accounts} 를 조인하는 것이 확정 행을 시즌으로 좁히는
 * 유일한 길이다. 계좌 식별자로 묻는 조회({@code findByAccountId…})는 계좌 자체가 이미 한 시즌에
 * 속하므로 시즌 인자가 없다.
 */
public interface DailySettlementRepository extends JpaRepository<DailySettlement, DailySettlement.Pk> {

    /**
     * 그 일차의 폐장 스냅샷이 확정됐는가. 리더보드의 {@code finalized} 가 이 값이다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 일차
     * @return 한 행이라도 있으면 참
     */
    @Query("""
            SELECT CASE WHEN COUNT(d) > 0 THEN TRUE ELSE FALSE END
            FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId AND d.seasonDay = :seasonDay
            """)
    boolean existsBySeasonDay(@Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay);

    /**
     * 그 일차의 확정 행 전부. 다음 날 브리핑이 일간 기준값을 옮길 때 읽는다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 일차
     * @return 확정 행. 확정 전이면 비어 있다
     */
    @Query("""
            SELECT d FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId AND d.seasonDay = :seasonDay
            """)
    List<DailySettlement> findBySeasonDay(@Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay);

    /**
     * 그 일차에 확정된 계좌 수. 시즌 리포트의 {@code totalParticipants} 가 3일차의 이
     * 값이다 — 그 일차 순위에 든 사람 수이지 가입자 수가 아니다 ({@code rpt.md}).
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 대상 일차
     * @return 확정 행 수
     */
    @Query("""
            SELECT COUNT(d) FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId AND d.seasonDay = :seasonDay
            """)
    long countBySeasonDay(@Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay);

    /**
     * 그 일차의 일간 상위 행 ({@code S15P21A605-265}). 시상 조회와 시상 알림이 <b>같은 메서드</b>를
     * 읽는다 — 화면에 뜬 사람과 알림을 받는 사람이 갈리는 일이 구조적으로 막힌다.
     *
     * <p>동점은 같은 순위라 경계 순위가 동점이면 상한을 넘는 행이 온다 (1,2,2,2,5,5 → 6 행).
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 일차
     * @param dailyRank 포함할 최대 일간 순위
     * @return 그 순위까지의 확정 행. 일간 순위 오름차순, 같은 순위 안에서는 계좌 식별자 순
     */
    @Query("""
            SELECT d FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId
              AND d.seasonDay = :seasonDay AND d.dailyRank <= :dailyRank
            ORDER BY d.dailyRank ASC, d.accountId ASC
            """)
    List<DailySettlement> findBySeasonDayAndDailyRankLessThanEqualOrderByDailyRankAscAccountIdAsc(
            @Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay,
            @Param("dailyRank") Integer dailyRank);

    /**
     * 한 계좌의 확정 행 전부, 일차 순서. 시즌에 최대 세 행이다.
     *
     * <p>'만약에' 시뮬레이터가 일차별 비교 대상({@code daily_return})과 시즌 누적
     * ({@code season_return})을 한 번에 얻는 데 쓴다.
     *
     * @param accountId 대상 계좌
     * @return 일차 오름차순 확정 행. 참가하지 않은 일차는 없다
     */
    List<DailySettlement> findByAccountIdOrderBySeasonDayAsc(Long accountId);

    /**
     * 계좌별 일간 순위 포인트 합계 ({@code S15P21A605-250} 확정 규칙).
     *
     * <p><b>점수는 확정된 일차에서만 나온다.</b> 진행 중인 오늘은 아직 행이 없으므로 셈에
     * 들어가지 않는다 — 폐장 정산이 행을 쓰는 순간 그날 점수가 더해진다.
     *
     * <p><b>그날 체결이 0 건이면 0 점이다.</b> 가입만 하고 아무것도 하지 않은 계좌에 점수가
     * 쌓이지 않게 하는 것이 이 규칙의 목적이다 — 베타 1 일차에 그런 계좌가 11 개였고 전부
     * 50 위에 몰려 있었다.
     *
     * <p><b>{@code wins} 도 체결이 있어야 센다.</b> 그날 전원이 손실을 보면 아무 거래도 하지
     * 않은 계좌가 0.00 % 로 일간 1 등이 된다 — 점수는 0 점인데 1 등 횟수만 쌓이는 모순이
     * 생기고, 그 횟수가 동점을 가르는 두 번째 기준이라 실제 거래자를 제친다.
     *
     * <p><b>{@code dailyReturnSum} 은 일간 수익률의 단순 합이지 누적 수익률이 아니다.</b>
     * 100 % 뒤 −50 % 면 이 값은 50 % 지만 실제 누적은 0 % 다. 동점을 가르는 마지막 기준이며,
     * 복리로 계산되는 {@code season_return} 과 값이 다르다.
     *
     * <p><b>시즌으로 좁힌다.</b> 계좌별 집계라 다른 시즌 행이 섞여도 계좌가 갈리지만, 순위
     * <b>번호</b>는 목록 전체를 세워 매긴다 ({@code PointStandingsCalculator#rank}) — 시즌 1
     * 계좌를 함께 세우면 시즌 2 1 등이 그 수만큼 밀린 번호를 받는다.
     *
     * <p><b>일차로도 좁힌다.</b> 리더보드는 확정된 전부를 보므로 마지막 일차를 넣지만, 일일
     * 리포트의 누적 블록은 <b>그 일차까지</b>여야 한다 — 지난 일차의 결과지를 나중에 다시
     * 만들면 그 사이에 확정된 일차의 점수가 섞여 들어가고, 같은 요청의 체결·수수료·회전율은
     * 그 일차까지로 잘려 있어 한 덩이 안에서 기준이 갈린다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 이 일차까지 (포함)
     * @return 계좌별 집계. 확정된 일차가 하나도 없으면 빈 목록
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository$PointRow(
                d.accountId,
                SUM(CASE WHEN d.executionCount = 0 THEN 0
                         WHEN d.dailyRank = 1 THEN 100
                         WHEN d.dailyRank = 2 THEN 80
                         WHEN d.dailyRank = 3 THEN 65
                         WHEN d.dailyRank <= 10 THEN 50 - (d.dailyRank - 4) * 5
                         ELSE 10 END),
                SUM(CASE WHEN d.dailyRank = 1 AND d.executionCount > 0 THEN 1 ELSE 0 END),
                SUM(d.dailyReturn))
            FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId AND d.seasonDay <= :seasonDay
            GROUP BY d.accountId
            """)
    List<PointRow> aggregatePoints(@Param("seasonId") Long seasonId,
                                   @Param("seasonDay") Short seasonDay);

    /**
     * 한 계좌의 포인트 집계.
     *
     * @param accountId       대상 계좌
     * @param points          일간 순위 포인트 합
     * @param wins            일간 1 등 횟수. 첫 번째 동점 기준이다
     * @param dailyReturnSum  일간 수익률의 <b>단순 합</b>(%). 두 번째 동점 기준이다
     */
    record PointRow(Long accountId, long points, long wins, BigDecimal dailyReturnSum) {
    }

    /**
     * 그 일차의 확정 행 중 마감 보유 스냅샷이 없는 계좌 수 (V42).
     *
     * <p><b>다음 일차 초기화의 통과 조건이다.</b> 0 이 아니면 그 계좌들의 보유 기록이
     * 보존되지 않았다는 뜻이고, 그대로 초기화하면 무엇을 들고 끝냈는지가 영구히 사라진다.
     * 보유가 없던 계좌도 표식은 찍히므로 "행이 있는가" 로는 판정할 수 없다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 일차
     * @return 스냅샷 표식이 없는 확정 행 수
     */
    @Query("""
            SELECT COUNT(d) FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId AND d.seasonDay = :seasonDay
              AND d.holdingsSnapshottedAt IS NULL
            """)
    long countMissingHoldingsSnapshot(@Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay);

    /**
     * 그 일차에 확정된 계좌 식별자 (V42).
     *
     * <p>초기화가 "기록이 남은 계좌" 를 가릴 때 쓴다. 확정 행이 없는데 보유가 있는 계좌를
     * 초기화하면 그 보유는 어디에도 남지 않는다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 일차
     * @return 확정된 계좌 식별자
     */
    @Query("""
            SELECT d.accountId FROM DailySettlement d, Account a
            WHERE a.id = d.accountId AND a.seasonId = :seasonId AND d.seasonDay = :seasonDay
            """)
    List<Long> findAccountIdsBySeasonDay(@Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
