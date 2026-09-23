package com.ssafy.a605.backend.domain.account.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.account.entity.Position;

public interface PositionRepository extends JpaRepository<Position, Position.Pk> {

    /**
     * 수량이 남아 있는 보유 포지션. 전량 매도한 행은 수량 0 으로 남을 수 있고 평가액에
     * 아무것도 더하지 않으므로 읽지 않는다 ({@code acc.md} - quantity 가 0 인 포지션은
     * 응답에 포함하지 않는다).
     *
     * <p>종목 식별자 순으로 고정한다. 정렬이 없으면 행의 물리 순서가 돌아오는데, 그 순서는
     * 갱신에 따라 바뀌므로 매 틱 다시 그리는 목록의 줄이 이유 없이 뒤바뀐다.
     *
     * @param accountId 대상 계좌
     * @param quantity  이 수량보다 많은 것만 (0 을 넘긴다)
     * @return 보유 중인 포지션. 종목 식별자 오름차순
     */
    List<Position> findByAccountIdAndQuantityGreaterThanOrderByInstrumentIdAsc(Long accountId,
                                                                              Integer quantity);

    /**
     * 여러 계좌의 보유 포지션을 한 번에 읽는다. 매 틱 전 계좌를 평가하는 스윕이 쓴다 —
     * 계좌마다 따로 물으면 조회 횟수가 참가자 수만큼 늘어난다.
     *
     * @param accountIds 대상 계좌
     * @param quantity   이 수량보다 많은 것만 (0 을 넘긴다)
     * @return 보유 중인 포지션. 계좌별 묶음은 호출 측이 만든다
     */
    List<Position> findByAccountIdInAndQuantityGreaterThan(Collection<Long> accountIds, Integer quantity);

    /**
     * 그 계좌의 포지션 <b>전부</b>. 수량이 0 인 행도 준다 — 부재 구간을 되짚을 때 그 사이에
     * 전량 매도한 종목도 시작 시점에는 갖고 있었기 때문이다.
     *
     * @param accountId 대상 계좌
     * @return 포지션 전부
     */
    List<Position> findByAccountId(Long accountId);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);

    /**
     * 그 계좌들의 포지션을 전부 지운다. 일차 경계의 계좌 초기화가 쓴다 (V42).
     *
     * <p><b>수량 0 행도 함께 지운다.</b> 초기화 뒤에 남으면 "그 종목을 들고 있었다"는
     * 흔적만 남고 그 일차의 실제 보유는 {@code daily_holdings} 에 있다.
     *
     * @param accountIds 대상 계좌
     * @return 지워진 행 수
     */
    long deleteByAccountIdIn(Collection<Long> accountIds);
}
