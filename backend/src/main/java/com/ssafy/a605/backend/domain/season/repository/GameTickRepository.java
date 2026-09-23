package com.ssafy.a605.backend.domain.season.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.season.entity.GameTick;

/**
 * 틱 순번으로 그 틱의 게임 거래일과 환율을 읽는다.
 *
 * <p><b>"지금 몇 번째 틱인가"를 여기서 묻지 않는다.</b> 그 답은 재생 상태
 * ({@code replay_state.last_published_tick_seq}) 가 갖고 있고 {@code ReplayService.clock()} 이
 * 내준다. 이 테이블의 {@code cum_offset_sec} 는 배속·앵커와 무관한 불변 편성 오프셋이라
 * 실제 시각과 비교할 수 있는 값이 아니다 (V13).
 */
public interface GameTickRepository extends JpaRepository<GameTick, Long> {

    /**
     * 한 일차가 차지하는 틱 구간. 체결·환전이 일차로 걸러질 때 조인 대신 이 범위를 쓴다 —
     * 그 테이블들은 {@code season_day} 를 갖고 있지 않고 {@code tick_seq} 로만 게임 시각을
     * 표현하므로, 범위 조건이 인덱스를 그대로 탄다.
     *
     * <p><b>범위 조건이 성립하는 근거는 틱 순번이 공개 순서라는 것이다.</b> 일차는 시간순으로
     * 이어지고 서로 끼어들지 않으므로 한 일차의 틱은 순번상 연속한다 — 그래서 첫 틱과 마지막
     * 틱만으로 그 일차 전체를 가른다. 순번이 공개 순서가 아니게 되는 날 이 가정이 깨진다.
     *
     * <p>편성은 시즌 시작 전에 전 구간이 적재되어 있으므로 아직 공개되지 않은 틱도 범위에
     * 들어온다. 그래도 미래 데이터가 새지 않는 것은 <b>체결이 공개된 틱에만 생기기</b>
     * 때문이다 — 범위가 넓은 것과 그 범위에 행이 있는 것은 다른 이야기다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 대상 일차
     * @return 그 일차의 첫 틱과 마지막 틱. 편성에 없는 일차면 두 값이 {@code null}
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.season.repository.GameTickRepository$TickRange(
                MIN(t.tickSeq), MAX(t.tickSeq))
            FROM GameTick t
            WHERE t.seasonId = :seasonId AND t.seasonDay = :seasonDay
            """)
    TickRange findTickRange(@Param("seasonId") Long seasonId, @Param("seasonDay") Short seasonDay);

    /**
     * 시즌의 첫 틱. 종가 시계열의 시작 날짜 기본값인 <b>재생 구간 시작</b>이 이 틱의 거래일이다
     * ({@code mkt.md}).
     *
     * <p>편성에서 읽는 이유는 재생 구간이 시즌마다 다르고 상수가 아니기 때문이다. 상장일이
     * 있는 IPO 종목은 그 상장일이 기본값이므로 여기까지 오지 않는다.
     *
     * @param seasonId 대상 시즌
     * @return 그 시즌에서 가장 이른 거래일의 틱. 편성이 없으면 빈 값
     */
    Optional<GameTick> findFirstBySeasonIdOrderByGameDateAsc(Long seasonId);

    /**
     * 그 일차가 재생한 게임 거래일의 처음과 끝.
     *
     * <p>두 값이 함께 필요한 곳이 둘이다. 복기 집계는 끝 날짜로 패턴 태그의 기준 종가를
     * 집고({@code DailySettlementAggregator}), 복기 조회는 두 날짜로 차트 구간을 자른다
     * ({@code DailyReportService}).
     *
     * <p>끊어야 하는 이유는 종가가 시즌 시작 전에 전 구간 적재되어 있기 때문이다
     * ({@code _common.md} §9). 범위를 주지 않으면 아직 공개되지 않은 다음 일차의 종가가 섞인다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 대상 일차
     * @return 그 일차의 첫·마지막 거래일. 편성에 없는 일차면 두 값이 {@code null}
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.season.repository.GameTickRepository$GameDateRange(
                MIN(t.gameDate), MAX(t.gameDate))
            FROM GameTick t
            WHERE t.seasonId = :seasonId AND t.seasonDay = :seasonDay
            """)
    GameDateRange findGameDateRange(@Param("seasonId") Long seasonId,
                                    @Param("seasonDay") Short seasonDay);

    /**
     * 그 일차의 틱 전부. 게임 거래일과 틱 번호가 짝지어져 있다.
     *
     * <p><b>보유 기간을 재는 자다.</b> 틱 하나가 게임 거래일 하나이므로 두 체결의 틱 번호
     * 차이가 곧 보유 거래일 수다 — 달력 날짜를 빼면 주말과 휴장이 섞인다. 체결 행은
     * {@code tick_seq} 를 갖지만 집계가 읽는 {@code ExecutionRepository.DailyTrade} 는 거래일만
     * 실어 오므로(그 모양은 87번이 확정했다) 날짜에서 틱으로 되돌릴 자가 필요하다.
     *
     * <p>일차 하나가 게임 거래일 500여 일이라 그만큼의 행이 오지만, AI 전처리는 일차당 한 번만
     * 이것을 읽고 계좌 100 명이 같은 자를 나눠 쓴다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 대상 일차
     * @return 그 일차의 틱. 틱 순번 오름차순이며 편성에 없는 일차면 빈 목록
     */
    List<GameTick> findBySeasonIdAndSeasonDayOrderByTickSeqAsc(Long seasonId, Short seasonDay);

    /**
     * 한 일차가 재생한 거래일 구간.
     *
     * @param fromGameDate 첫 거래일. 편성에 없는 일차면 {@code null}
     * @param toGameDate   마지막 거래일. 같은 경우 {@code null}
     */
    record GameDateRange(LocalDate fromGameDate, LocalDate toGameDate) {
    }

    /**
     * 한 일차의 틱 구간.
     *
     * @param fromTickSeq 첫 틱. 편성에 없는 일차면 {@code null} (집계가 빈 행에도 한 줄을 낸다)
     * @param toTickSeq   마지막 틱. 같은 경우 {@code null}
     */
    record TickRange(Long fromTickSeq, Long toTickSeq) {
    }

    /**
     * 틱 구간을 일차별로 접은 오프셋 범위. 부재 구간에 <b>무료 수수료 타임 시작</b>이 들어
     * 있는지 판정하는 데 쓴다 ({@code acc.md} §부재 요약).
     *
     * <p>틱을 전부 읽지 않는 이유는 구간이 하루를 넘으면 천 행이 넘기 때문이다. 필요한 것은
     * 일차마다 "구간이 어디서 시작해 어디서 끝났나" 둘뿐이다.
     *
     * @param seasonId 대상 시즌
     * @param from     구간 시작 틱. 포함한다
     * @param to       구간 끝 틱. 포함한다
     * @return 일차별 최소·최대 가상 오프셋
     */
    @Query("""
            SELECT new com.ssafy.a605.backend.domain.season.repository.GameTickRepository$DayOffsets(
                t.seasonDay, min(t.cumOffsetSec), max(t.cumOffsetSec))
            FROM GameTick t
            WHERE t.seasonId = :seasonId AND t.tickSeq BETWEEN :from AND :to
            GROUP BY t.seasonDay
            """)
    List<DayOffsets> findDayOffsets(@Param("seasonId") long seasonId, @Param("from") long from,
                                    @Param("to") long to);

    /**
     * 한 일차에서 구간이 걸친 가상 위치의 처음과 끝.
     *
     * @param seasonDay 일차
     * @param minOffset 구간에 든 첫 틱의 위치
     * @param maxOffset 구간에 든 마지막 틱의 위치
     */
    record DayOffsets(Short seasonDay, Integer minOffset, Integer maxOffset) {

        /**
         * 그 경계를 이 구간이 넘었는가. 시작 위치가 경계 앞이고 끝 위치가 경계 뒤여야 한다 —
         * 구간이 이미 경계 뒤에서 시작했다면 그 사건은 부재 전에 일어난 것이다.
         *
         * @param offset 경계의 가상 위치
         * @return 넘었으면 참
         */
        public boolean crossed(int offset) {
            return minOffset < offset && maxOffset >= offset;
        }
    }

    /**
     * 그 시즌의 틱 전부, 거래일 순서.
     *
     * <p>'만약에' 시뮬레이터가 <b>일차 경계와 환율을 한 번에</b> 얻는 데 쓴다. 일차별 첫·마지막
     * 거래일은 {@code seasonDay} 로 갈라 구하고, USD 종목의 양 끝 환산은 같은 행의
     * {@code usdKrw} 로 한다 — 일차마다 따로 부르면 왕복이 일차 수만큼 늘고, 환율을 또 한 번
     * 물어야 한다.
     *
     * <p>시즌 하나가 약 1,700 행이다. 한 번 열고 오래 보는 화면이 종목을 눌러 가며 부르는
     * 경로라, 행 수보다 왕복 횟수를 줄이는 편이 낫다고 보았다.
     *
     * @param seasonId 대상 시즌
     * @return 거래일 오름차순 틱 전부
     */
    List<GameTick> findBySeasonIdOrderByGameDateAsc(Long seasonId);
}
