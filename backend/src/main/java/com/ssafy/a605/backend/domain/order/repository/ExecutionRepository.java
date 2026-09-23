package com.ssafy.a605.backend.domain.order.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.Execution;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    /** (계좌, 건수). 건수가 0 인 계좌는 행이 없다. */
    /**
     * 부재 구간에 <b>예약이 체결된</b> 건수 (기획서 7.5.2, {@code acc.md} §부재 요약).
     *
     * <p><b>시장가는 세지 않는다.</b> 시장가는 사용자가 그 자리에서 누른 것이라 자리를 비운
     * 사이에 일어날 수 없고, 놓쳐서 놀랄 일도 아니다 — {@code order_id} 가 있는 체결만 예약에서
     * 온 것이다.
     *
     * @param accountId 대상 계좌
     * @param from      구간 시작. <b>이 틱은 포함하지 않는다</b> — 마지막으로 확인한 틱이다
     * @param to        구간 끝. 포함한다
     * @return 그 구간의 예약 체결 건수
     */
    int countByAccountIdAndOrderIdIsNotNullAndTickSeqGreaterThanAndTickSeqLessThanEqual(
            Long accountId, Long from, Long to);

    record AccountCount(Long accountId, long count) {
    }

    /**
     * 종목별 거래대금 ({@code S15P21A605-253}). 시세 응답의 {@code tradedAmountKrw} 가 이 값이고,
     * 프론트가 그것으로 종목 목록을 거래량 순 정렬한다.
     *
     * <p><b>여기서 말하는 거래량은 참가자들이 그 종목에 오간 돈이다.</b> 실제 시장 거래량은
     * 적재되지 않는다 — 단일 가격 모델이라 {@code daily_closes} 가 시가·고가·저가·거래량을
     * 담지 않는다 (기획서 4.1.3).
     *
     * <p><b>매수·매도를 모두 더한다.</b> {@link #aggregateSeason} 의 총 거래대금과 같은 정의다 —
     * 한 이름의 값이 자리마다 다른 뜻을 갖지 않게 한다. 사고 판 돈이 두 번 셈에 들어가므로
     * 회전이 잦은 종목이 크게 나오며, 그것이 "돈이 오간 양" 이라는 이 지표의 뜻이다.
     *
     * <p><b>원화 환산을 SQL 안에서 한다.</b> 체결마다 그 틱의 환율이 다르므로 (일차 하나가 게임
     * 거래일 500여 일이다) 합계를 먼저 내고 나중에 환산할 수 없다. 그래서 이 조회만은
     * {@code game_ticks} 를 조인한다 — 아래 틱 구간 조건과 같은 판단이 아니라 <b>환율 때문</b>이다.
     *
     * <p><b>일차를 틱 구간으로 받는다.</b> 체결은 {@code season_day} 를 갖고 있지 않아 일차로
     * 거르려면 어차피 틱을 봐야 하고, 호출 측이 범위를 먼저 구해 넘기면 그 조인이 범위 조건이
     * 된다 ({@link #search} 와 같은 판단).
     *
     * @param fromTickSeq 그 일차의 첫 틱(포함)
     * @param toTickSeq   공개 기준 틱(포함). <b>아직 공개하지 않은 틱의 체결은 세지 않는다</b> —
     *                    가격과 같은 절단 기준이라야 한 응답의 두 숫자가 같은 시점을 말한다
     * @param usd         달러 통화. 환산 곱수를 가르는 값이다
     * @return 체결이 한 건 이상인 종목만
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$InstrumentAmount(
                e.instrumentId,
                SUM(e.amount * CASE WHEN e.currency = :usd THEN t.usdKrw ELSE 1 END))
            FROM Execution e, GameTick t
            WHERE e.tickSeq = t.tickSeq
              AND e.tickSeq >= :fromTickSeq
              AND e.tickSeq <= :toTickSeq
            GROUP BY e.instrumentId
            """)
    List<InstrumentAmount> sumAmountByInstrumentBetweenTicks(@Param("fromTickSeq") long fromTickSeq,
                                                             @Param("toTickSeq") long toTickSeq,
                                                             @Param("usd") Currency usd);

    /** (종목, 거래대금 원화 환산). 체결이 없는 종목은 행이 없다. */
    record InstrumentAmount(Long instrumentId, BigDecimal amountKrw) {
    }

    /**
     * 그 계좌의 체결 내역. 종목과 틱 구간으로 좁힌다.
     *
     * <p><b>일차 필터를 조인이 아니라 틱 구간으로 받는 이유</b>는 인덱스다. 체결은
     * {@code season_day} 를 갖고 있지 않아 일차로 거르려면 {@code game_ticks} 를 조인해야
     * 하는데, 호출 측이 그 일차의 틱 범위를 먼저 구해 넘기면
     * {@code ix_executions_account_tick} 의 두 열을 그대로 타는 범위 조건이 된다.
     *
     * @param accountId   대상 계좌
     * @param instrumentId 종목 필터. {@code null} 이면 전체
     * @param fromTickSeq 틱 구간 시작(포함). {@code null} 이면 하한 없음
     * @param toTickSeq   틱 구간 끝(포함). {@code null} 이면 상한 없음
     * @param pageable    페이지와 정렬. 최신순은 호출 측이 고정한다
     * @return 체결 한 페이지
     */
    @Query("""
            SELECT e FROM Execution e
            WHERE e.accountId = :accountId
              AND (:instrumentId IS NULL OR e.instrumentId = :instrumentId)
              AND (:fromTickSeq IS NULL OR e.tickSeq >= :fromTickSeq)
              AND (:toTickSeq IS NULL OR e.tickSeq <= :toTickSeq)
            """)
    Page<Execution> search(@Param("accountId") Long accountId,
                           @Param("instrumentId") Long instrumentId,
                           @Param("fromTickSeq") Long fromTickSeq,
                           @Param("toTickSeq") Long toTickSeq,
                           Pageable pageable);

    /**
     * 계좌별 시즌 전체 체결 건수. 리더보드 시즌 트랙의 {@code tradeCount} 다.
     *
     * @param accountIds 대상 계좌
     * @return 체결이 한 건 이상인 계좌만
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$AccountCount(
                e.accountId, COUNT(e))
            FROM Execution e
            WHERE e.accountId IN :accountIds
            GROUP BY e.accountId
            """)
    List<AccountCount> countByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    /**
     * 계좌별 그 일차의 체결 건수. 리더보드 일간 트랙의 {@code tradeCount} 다. 체결은 게임 시각을
     * 틱으로만 갖고 있어 일차는 {@code game_ticks} 를 거쳐 얻는다.
     *
     * @param accountIds 대상 계좌
     * @param seasonDay  일차
     * @return 그 일차에 체결이 한 건 이상인 계좌만
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$AccountCount(
                e.accountId, COUNT(e))
            FROM Execution e, GameTick t
            WHERE e.tickSeq = t.tickSeq
              AND t.seasonDay = :seasonDay
              AND e.accountId IN :accountIds
            GROUP BY e.accountId
            """)
    List<AccountCount> countByAccountIdsAndSeasonDay(@Param("accountIds") Collection<Long> accountIds,
                                                     @Param("seasonDay") Short seasonDay);

    /**
     * 폐장 복기 집계가 읽는 그 일차의 체결. <b>계좌 수와 무관하게 이 한 번으로 끝난다</b> —
     * 폐장에서 공개까지 20 분에 100 명분을 돌려야 하는 경로라 계좌마다 물을 수 없다.
     *
     * <p><b>틱을 조인하는 것이 여기서는 이득이다.</b> {@link #search} 는 일차를 틱 구간으로
     * 바꿔 조인을 피하지만, 집계는 체결마다 <b>그 틱의 환율</b>이 필요하다 (일차 하나가 게임
     * 거래일 500여 일을 재생하므로 하루의 환율로 일차 전체를 환산할 수 없다). 어차피 볼
     * 테이블이라 같은 조인에서 일차까지 거른다 — {@link #countByAccountIdsAndSeasonDay} 와 같다.
     *
     * @param accountIds 대상 계좌. 그 일차 순위에 든 계좌 전부다
     * @param seasonDay  집계할 일차
     * @return 그 일차의 체결. 거래가 없는 계좌는 행이 없다
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$DailyTrade(
                e.id, e.accountId, e.instrumentId, e.side, e.quantity, e.executedPrice,
                e.currency, e.amount, e.fee, e.realizedPnl, e.gameDate, t.usdKrw, t.seasonDay)
            FROM Execution e, GameTick t
            WHERE e.tickSeq = t.tickSeq
              AND t.seasonDay = :seasonDay
              AND e.accountId IN :accountIds
            """)
    List<DailyTrade> findDailyTrades(@Param("accountIds") Collection<Long> accountIds,
                                     @Param("seasonDay") Short seasonDay);

    /**
     * 시즌 전체 체결 한 줄씩. 일차로 거르지 않는 것 말고는 {@link #findDailyTrades} 와 같다.
     *
     * <p><b>접지 않고 행으로 받는다.</b> 시즌 유형 판정의 재료가 건수 합이 아니라 체결마다의
     * 판정이기 때문이다 — 상대 가격 위치 R 은 그 체결일 전후 20 거래일 종가를 봐야 하고,
     * 보유 기간은 매수와 매도를 짝지어야 나온다 (AI 전처리 요청서 v5 §7.1).
     *
     * @param accountIds 대상 계좌. 3일차 확정에 든 계좌 전부다
     * @return 그 계좌들의 시즌 체결. 거래가 없는 계좌는 행이 없다
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$DailyTrade(
                e.id, e.accountId, e.instrumentId, e.side, e.quantity, e.executedPrice,
                e.currency, e.amount, e.fee, e.realizedPnl, e.gameDate, t.usdKrw, t.seasonDay)
            FROM Execution e, GameTick t
            WHERE e.tickSeq = t.tickSeq
              AND e.accountId IN :accountIds
            ORDER BY e.id
            """)
    List<DailyTrade> findSeasonTrades(@Param("accountIds") Collection<Long> accountIds);

    /**
     * 그 일차까지의 체결 한 줄씩 (AI 업무요청서 2026-09-18 요청 ②).
     *
     * <p>일일 리포트의 누적 집계가 쓴다. {@link #findSeasonTrades} 와 재료가 같고 구간만
     * 그 일차까지로 좁는다 — 3일차에 부르면 두 질의의 결과가 같다.
     *
     * <p><b>일차로 거르고 날짜로 거르지 않는다.</b> 게임 거래일은 일차 경계에서 이어지므로
     * 날짜 범위로 자르면 편성이 바뀔 때 경계가 어긋난다.
     *
     * @param accountIds 대상 계좌
     * @param seasonDay  이 일차까지 (포함)
     * @return 그 구간의 체결. 거래가 없는 계좌는 행이 없다
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$DailyTrade(
                e.id, e.accountId, e.instrumentId, e.side, e.quantity, e.executedPrice,
                e.currency, e.amount, e.fee, e.realizedPnl, e.gameDate, t.usdKrw, t.seasonDay)
            FROM Execution e, GameTick t
            WHERE e.tickSeq = t.tickSeq
              AND t.seasonDay <= :seasonDay
              AND e.accountId IN :accountIds
            ORDER BY e.id
            """)
    List<DailyTrade> findTradesUpToSeasonDay(@Param("accountIds") Collection<Long> accountIds,
                                             @Param("seasonDay") Short seasonDay);

    /**
     * 시즌 전체 체결을 계좌별로 접는다 ({@code rpt.md} §GET /reports/season).
     *
     * <p><b>승률·회전율·수수료 셋이 한 조회에서 나온다.</b> 셋 다 같은 행들을 세거나
     * 더하는 값이라 따로 돌 이유가 없다. 시즌 리포트는 3일차 폐장에 한 번만 만들어지므로
     * 이 조회도 시즌당 한 번이다.
     *
     * <p><b>원화 환산을 SQL 안에서 한다.</b> 체결마다 그 틱의 환율이 다르므로 (일차 하나가
     * 게임 거래일 500여 일이다) 합계를 먼저 내고 나중에 환산할 수 없다.
     *
     * @param accountIds 대상 계좌
     * @param sell       매도 방향. 승률의 분모를 세는 데 쓴다
     * @param usd        달러 통화. 환산 곱수를 가르는 값이다
     * @return 체결이 있는 계좌의 집계. 거래가 없는 계좌는 행이 없다
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.order.repository.ExecutionRepository$SeasonAggregate(
                e.accountId,
                SUM(CASE WHEN e.side = :sell THEN 1 ELSE 0 END),
                SUM(CASE WHEN e.realizedPnl > 0 THEN 1 ELSE 0 END),
                SUM(e.fee * CASE WHEN e.currency = :usd THEN t.usdKrw ELSE 1 END),
                SUM(e.amount * CASE WHEN e.currency = :usd THEN t.usdKrw ELSE 1 END))
            FROM Execution e, GameTick t
            WHERE e.tickSeq = t.tickSeq
              AND e.accountId IN :accountIds
            GROUP BY e.accountId
            """)
    List<SeasonAggregate> aggregateSeason(@Param("accountIds") Collection<Long> accountIds,
                                          @Param("sell") OrderSide sell,
                                          @Param("usd") Currency usd);

    /**
     * 한 계좌의 시즌 집계.
     *
     * @param accountId       그 계좌
     * @param sellCount       매도 체결 건수. 승률의 분모다
     * @param winCount        실현 손익이 양수인 체결 건수. 매수는 손익이 {@code null} 이라 세지 않는다
     * @param totalFeeKrw     거래 수수료 합계 (원화 환산)
     * @param tradedAmountKrw 총 거래대금 (원화 환산). 회전율의 분자다
     */
    record SeasonAggregate(Long accountId, long sellCount, long winCount,
                           BigDecimal totalFeeKrw, BigDecimal tradedAmountKrw) {
    }

    /**
     * 복기 집계가 보는 체결 한 건. 엔티티 대신 이 모양으로 읽는 이유는 <b>환율이 함께 필요</b>
     * 하기 때문이고, 집계 규칙을 조회 없이 계산할 수 있게 되는 것이 따라오는 이득이다.
     *
     * @param executionId   체결 식별자. 상·하위와 태그가 가리키는 값이다
     * @param accountId     체결한 계좌
     * @param instrumentId  종목
     * @param side          매수·매도
     * @param quantity      체결 수량. 복기 차트의 마커가 쓴다
     * @param executedPrice 체결 단가. 그 거래일의 종가다
     * @param currency      결제 통화
     * @param amount        체결 금액 (결제 통화). 실현 손익의 취득 원가를 되짚는 데 쓴다
     * @param fee           거래 수수료 (결제 통화)
     * @param realizedPnl   실현 손익 (결제 통화). <b>매수는 {@code null}</b>
     * @param gameDate      체결한 게임 거래일
     * @param usdKrw        그 틱의 환율
     */
    record DailyTrade(Long executionId, Long accountId, Long instrumentId, OrderSide side,
                      Integer quantity, BigDecimal executedPrice, Currency currency,
                      BigDecimal amount, BigDecimal fee, BigDecimal realizedPnl,
                      LocalDate gameDate, BigDecimal usdKrw, Short seasonDay) {

        /**
         * 이 체결이 속한 일차. <b>선입선출 짝짓기를 일차로 끊는 데 쓴다</b> — 일차 경계에서
         * 보유가 초기화되므로 1 일차 매수와 3 일차 매도는 짝지을 수 없는 관계다. 게임 거래일은
         * 일차 경계에서 이어져 날짜만으로는 경계를 알 수 없다.
         */

        /** 이 체결을 원화로 만드는 곱수. 원화 체결은 1 이다. */
        public BigDecimal krwRate() {
            return currency == Currency.USD ? usdKrw : BigDecimal.ONE;
        }

        /**
         * 이 매도가 덜어낸 취득 원가 (결제 통화). 매수면 {@code null}.
         *
         * <p>저장하지 않고 되짚는다. 체결이 {@code 대금 - 수수료 - 실현 손익 = 취득 원가} 로
         * 정산되므로({@code AccountLedgerService.settleSell}) 그 셋만으로 복원된다. 평단가를
         * 저장하지 않는다는 규칙({@code _common.md} §6.3)과 어긋나지 않는다 — 이 값은 그
         * 체결 순간에 확정돼 체결 행에 이미 담겨 있다.
         */
        public BigDecimal removedCost() {
            return realizedPnl == null ? null : amount.subtract(fee).subtract(realizedPnl);
        }
    }

    /**
     * 부재 구간의 체결 전부. 그 구간 시작 시점의 현금·수량을 되짚는 데 쓴다
     * ({@code acc.md} §부재 요약).
     *
     * @param accountId 대상 계좌
     * @param from      구간 시작. 포함하지 않는다
     * @param to        구간 끝. 포함한다
     * @return 그 구간의 체결. 시장가와 예약을 가리지 않는다 — 둘 다 현금과 수량을 바꾼다
     */
    List<Execution> findByAccountIdAndTickSeqGreaterThanAndTickSeqLessThanEqual(
            Long accountId, Long from, Long to);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
