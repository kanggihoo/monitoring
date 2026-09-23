package com.ssafy.a605.backend.domain.account.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.account.entity.Account;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * 그 사용자의 그 시즌 계좌. 1인 1계좌를 {@code (user_id, season_id)} UNIQUE 가 보장하므로
     * 결과는 0 또는 1 건이다.
     *
     * @param userId   계좌 주인
     * @param seasonId 소속 시즌
     * @return 계좌 (없으면 비어 있음)
     */
    Optional<Account> findByUserIdAndSeasonId(Long userId, Long seasonId);

    /**
     * 같은 계좌를 행 잠금으로 읽는다. 현금을 옮기는 경로는 읽고 계산한 뒤 쓰므로, 잠금이 없으면
     * 같은 사용자의 동시 요청 둘이 같은 잔고를 읽어 각자 계산한 결과를 덮어쓴다 —
     * {@code READ COMMITTED} 는 이 갱신 분실을 막지 않는다.
     *
     * <p>격리 수준을 올리는 대신 행을 잠그는 이유: {@code REPEATABLE_READ} 에서 같은 행에 대한
     * 동시 갱신은 직렬화 실패(40001)로 <b>거부</b>되어 사용자에게 오류가 나간다. 잠그면 뒤에 온
     * 요청이 기다린 뒤 갱신된 잔고를 다시 읽어 정상 처리된다.
     *
     * @param userId   계좌 주인
     * @param seasonId 소속 시즌
     * @return 잠긴 계좌 (없으면 비어 있음)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockByUserIdAndSeasonId(Long userId, Long seasonId);

    /**
     * 식별자로 같은 행을 잠근다. 사용자 요청이 아닌 경로가 쓴다 — 틱마다의 체결 판정은
     * 미체결 예약에서 출발하므로 손에 있는 것이 {@code account_id} 이고, 세션이 없어
     * {@code user_id} 로 되짚을 이유도 없다.
     *
     * <p>잠그는 이유는 {@link #findWithLockByUserIdAndSeasonId} 와 같다. 오히려 여기가 더
     * 필요하다 — 체결 판정은 사용자의 주문·환전과 <b>동시에</b> 돌아가므로 같은 계좌를 두
     * 경로가 나란히 갱신한다.
     *
     * @param id 계좌 식별자
     * @return 잠긴 계좌 (없으면 비어 있음)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockById(Long id);

    /**
     * 그 시즌의 모든 계좌. 매 틱 전 계좌를 평가하는 스윕이 쓴다 — 계좌 수와 무관하게 조회를
     * 한 번으로 고정하기 위해서다.
     *
     * <p>페이지를 두지 않는다. 시즌 참가자 전원을 한 틱 안에 평가해야 하므로 나눠 읽을 이유가
     * 없고, 나눠 읽으면 페이지 사이에 잔고가 바뀌어 같은 틱의 평가가 갈린다.
     *
     * @param seasonId 대상 시즌
     * @return 그 시즌의 계좌 전부
     */
    List<Account> findBySeasonId(Long seasonId);

    /**
     * 그 시즌의 계좌 수. 일차 초기화의 개장 전 점검이 분모로 쓴다.
     *
     * @param seasonId 대상 시즌
     * @return 계좌 수
     */
    long countBySeasonId(Long seasonId);

    /**
     * 그 일차의 초기화를 아직 받지 못한 계좌 <b>식별자만</b> (일차 초기화).
     *
     * <p><b>엔티티가 아니라 식별자를 돌려주는 것이 핵심이다.</b> 엔티티로 먼저 읽으면 그 행이
     * 영속성 컨텍스트에 올라가고, 뒤이어 {@link #findWithLockById} 로 잠가도 <b>이미 관리
     * 중인 인스턴스의 상태는 갱신되지 않는다</b>. 초기화가 둘 겹치면 뒤엣것이 앞엣것의 커밋을
     * 기다린 뒤에도 낡은 완료 표식을 보고 다시 초기화하며, 그 사이 체결된 거래까지 지운다.
     *
     * <p>여기서 좁히는 것은 <b>후보</b>일 뿐이다. 확정은 잠근 뒤 다시 본다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 진행 중인 일차
     * @return 계좌 식별자 오름차순. 잠금 순서를 고정한다
     */
    @Query("""
            SELECT a.id FROM Account a
            WHERE a.seasonId = :seasonId AND a.dailyBaseSeasonDay < :seasonDay
            ORDER BY a.id
            """)
    List<Long> findIdsNeedingDailyReset(@Param("seasonId") Long seasonId,
                                        @Param("seasonDay") Short seasonDay);

    /**
     * 그 일차의 초기화를 아직 받지 못한 계좌 수 (일차 초기화).
     *
     * <p>{@code daily_base_season_day} 가 완료 표식이다 ({@code Account#needsDailyReset}).
     * 개장 전에 이 값이 0 이어야 한다 — 0 이 아니면 그 계좌들은 거래가 막힌 상태다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 진행 중인 일차
     * @return 초기화가 남은 계좌 수
     */
    long countBySeasonIdAndDailyBaseSeasonDayLessThan(Long seasonId, Short seasonDay);

    /**
     * 그 시즌에 남아 있는 계좌의 식별자. 리더보드가 탈퇴자를 빼는 데 쓴다 — 순위 스냅샷은
     * 메모리에 있어 탈퇴를 모르고, 탈퇴는 계좌 행을 지우므로 <b>계좌의 존재가 곧 참가</b>다.
     *
     * <p>엔티티가 아니라 식별자만 읽는다. 매 조회가 시즌 전원을 대상으로 도는 자리라 행을
     * 통째로 실어 올 이유가 없다.
     *
     * @param seasonId 대상 시즌
     * @return 남아 있는 계좌의 식별자
     */
    @Query("SELECT a.id FROM Account a WHERE a.seasonId = :seasonId")
    List<Long> findIdsBySeasonId(@Param("seasonId") Long seasonId);

    /**
     * 그 사용자의 계좌 전부. 탈퇴가 쓴다 — 시즌으로 좁히지 않는 유일한 조회다. 탈퇴는 이 시즌만
     * 떠나는 것이 아니므로, 시즌을 고정하면 다음 시즌 계좌가 남아 삭제가 외래키 위반으로 깨진다.
     *
     * @param userId 계좌 주인
     * @return 그 사용자의 계좌 전부
     */
    List<Account> findByUserId(Long userId);

    /**
     * 그 계좌들의 주인. 전원 공지가 계좌마다 개인 큐로 보낼 대상을 찾는 데 쓴다
     * ({@code NotificationService.createForAll}).
     *
     * <p><b>엔티티가 아니라 두 값만 읽는다.</b> {@code findAllById} 로 받으면 필요한 것이
     * 사용자 식별자 하나인데 금액 여섯 개가 든 행을 사람 수만큼 실어 올리고, 쓰기 트랜잭션
     * 안이라 커밋에서 전부 더티 체킹까지 한다 — {@link #findIdsBySeasonId} 와 같은 판단이다.
     * 트랜잭션이 짧아지는 만큼 INSERT 가 계좌 행에 건 잠금도 일찍 풀린다.
     *
     * @param ids 대상 계좌
     * @return {@code [accountId, userId]} 쌍
     */
    @Query("SELECT a.id, a.userId FROM Account a WHERE a.id IN :ids")
    List<Object[]> findUserIdsByIds(@Param("ids") Collection<Long> ids);
}
