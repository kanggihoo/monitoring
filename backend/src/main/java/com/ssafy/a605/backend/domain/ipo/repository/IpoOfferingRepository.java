package com.ssafy.a605.backend.domain.ipo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;

import jakarta.persistence.LockModeType;

public interface IpoOfferingRepository extends JpaRepository<IpoOffering, Long> {

    /**
     * 그 시즌의 공모 편성 전부. 시즌당 8건이라 페이지도 필터도 두지 않는다 — 유니버스 목록은
     * 전건을 종목별로 찾아 쓰고, 개장 브리핑은 당일 틱 구간으로 걸러 쓴다.
     *
     * <p><b>비어 있는 것이 정상 상태다.</b> 이 테이블은 {@code game_ticks} 를 참조하므로
     * 마이그레이션이 아니라 적재 스크립트가 채우고, 적재 전에는 0행이다. 그때 유니버스 목록은
     * 상장 전 공모 종목을 전부 숨기고(미래 차단 방향으로 더 엄격하다) 브리핑의 청약 일정은
     * 빈 배열이 된다.
     *
     * @param seasonId 대상 시즌
     * @return 그 시즌의 공모 편성. 적재 전에는 빈 목록
     */
    List<IpoOffering> findBySeasonId(Long seasonId);

    /**
     * 공시된 공모. 공시 전 공모는 <b>목록에서 빠지는 것이 아니라 존재하지 않아야 한다</b> —
     * 종목명이 곧 미래 정보라 개수만 새어 나가도 편성이 드러난다 (기획서 3.5.2).
     *
     * @param seasonId 대상 시즌
     * @param tickSeq  지금까지 공개된 틱
     * @return 공시 순서대로. 화면의 순서가 곧 일어난 순서다
     */
    List<IpoOffering> findBySeasonIdAndDisclosureTickSeqLessThanEqualOrderByDisclosureTickSeqAsc(
            long seasonId, long tickSeq);

    /**
     * 그 틱에 마감되는 공모 (기획서 8.4.1 — 마감 즉시 추첨한다).
     *
     * @param seasonId 대상 시즌
     * @param tickSeq  방금 공개된 틱
     * @return 이번 틱에 마감된 공모
     */
    List<IpoOffering> findBySeasonIdAndCloseTickSeq(long seasonId, long tickSeq);

    /**
     * 그 틱에 공시되는 공모. 공시는 한 번뿐이라 <b>지났는지가 아니라 같은지</b>로 찾는다 —
     * 지난 것까지 잡으면 이미 공시된 공모의 알림이 틱마다 다시 나간다.
     *
     * @param seasonId 대상 시즌
     * @param tickSeq  방금 공개된 틱
     * @return 이번 틱에 공시된 공모
     */
    List<IpoOffering> findBySeasonIdAndDisclosureTickSeq(long seasonId, long tickSeq);

    /**
     * 그 틱에 상장하는 공모 (기획서 8.5.2 — 상장 시점에 포지션으로 전환된다).
     *
     * <p><b>추첨을 마친 것만 대상이다.</b> 배정이 확정되기 전에는 옮길 물량 자체가 없다.
     *
     * @param seasonId 대상 시즌
     * @param tickSeq  방금 공개된 틱
     * @return 이번 틱에 상장한 공모
     */
    List<IpoOffering> findBySeasonIdAndListingTickSeqAndDrawnAtIsNotNull(long seasonId, long tickSeq);

    /**
     * 추첨하려고 공모 행을 잠근다. 같은 공모를 두 번 추첨하지 않기 위한 직렬화 지점이다 —
     * 스윕이 중복 실행되면 잠금 반환이 두 번 일어난다.
     *
     * @param id 대상 공모
     * @return 잠긴 공모
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IpoOffering> findWithLockById(Long id);

    /**
     * 그 틱 구간에 일어난 공모 사건 수. 공시·마감·상장 셋을 <b>각각</b> 센다 — 한 공모가 구간
     * 안에서 두 국면을 지났다면 사용자는 두 가지를 놓친 것이다.
     *
     * <p>부재 요약의 {@code eventCount} 가 쓴다 (기획서 7.5.2 — "주요 이벤트 수(IPO·무료 타임)").
     *
     * @param seasonId 대상 시즌
     * @param from     구간 시작 틱. 포함한다
     * @param to       구간 끝 틱. 포함한다
     * @return 그 구간에 든 공시·마감·상장 틱의 개수. 편성이 없으면 0
     */
    @Query("""
            SELECT coalesce(sum(CASE WHEN o.disclosureTickSeq BETWEEN :from AND :to THEN 1 ELSE 0 END), 0)
                 + coalesce(sum(CASE WHEN o.closeTickSeq BETWEEN :from AND :to THEN 1 ELSE 0 END), 0)
                 + coalesce(sum(CASE WHEN o.listingTickSeq BETWEEN :from AND :to THEN 1 ELSE 0 END), 0)
            FROM IpoOffering o
            WHERE o.seasonId = :seasonId
            """)
    long countEventsBetween(@Param("seasonId") long seasonId, @Param("from") long from,
                            @Param("to") long to);
}
