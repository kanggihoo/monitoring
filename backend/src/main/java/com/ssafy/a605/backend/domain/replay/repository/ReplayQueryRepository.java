package com.ssafy.a605.backend.domain.replay.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.a605.backend.domain.replay.entity.ReplayState;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

/**
 * 재생 진행이 읽는 불변 편성 데이터와 재생 상태 행.
 *
 * <p><b>가격은 여기서 읽지 않는다.</b> 휴장 이월 규칙은 시장 도메인의 것이고
 * {@code MarketPriceService.snapshotAsOf} 가 REST 조회와 이 브로드캐스트에 같은 값을 낸다.
 * 규칙이 두 도메인에 흩어져 있으면 한쪽만 고치는 사고가 난다.
 */
@Repository
@RequiredArgsConstructor
public class ReplayQueryRepository {

    /** 브리핑은 개장 300초 전에 시작한다. */
    private static final int BRIEFING_LEAD_SECONDS = -ReplayState.BRIEFING;

    private final JdbcTemplate jdbc;
    private final EntityManager em;

    /**
     * @param seasonId 대상 시즌
     * @param lock     쓰기 전이면 {@code true} — 폴러와 HTTP 요청이 같은 행 잠금을 두고 직렬화된다
     * @return 재생 상태. 아직 초기화되지 않았으면 {@code null}
     */
    public ReplayState findState(long seasonId, boolean lock) {
        return lock ? em.find(ReplayState.class, seasonId, LockModeType.PESSIMISTIC_WRITE)
                : em.find(ReplayState.class, seasonId);
    }


    /**
     * 운영 일정의 다음 브리핑 시각. 재생 배속과 무관한 실제 개장 일정이다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 다음 일차
     * @return 그 일차의 브리핑 시작 실제 시각. 편성이 없으면 {@code null}
     */
    public Instant findBriefingStart(long seasonId, int seasonDay) {
        List<Instant> starts = jdbc.query(
                "SELECT opens_at - make_interval(secs => ?) FROM season_days WHERE season_id=? AND season_day=?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), BRIEFING_LEAD_SECONDS, seasonId, seasonDay);
        return starts.isEmpty() ? null : starts.getFirst();
    }

    /**
     * 편성된 일차 번호. 이동 지점 목록이 일차 경계를 만드는 기준이다.
     *
     * @param seasonId 대상 시즌
     * @return 오름차순 일차 번호. 편성이 없으면 빈 목록
     */
    public List<Short> findSeasonDays(long seasonId) {
        return jdbc.query("SELECT season_day FROM season_days WHERE season_id=? ORDER BY season_day",
                (rs, n) -> rs.getShort(1), seasonId);
    }

    /**
     * 게임 월마다 그 달의 첫 가격 틱. 월 → Tick 매핑은 거래일 달력에서 파생되므로 서버만 안다.
     *
     * @param seasonId 대상 시즌
     * @return 월 오름차순. 적재 전에는 빈 목록
     */
    public List<MonthTick> findMonthFirstTicks(long seasonId) {
        return jdbc.query("""
                SELECT DISTINCT ON (to_char(game_date, 'YYYY-MM')) to_char(game_date, 'YYYY-MM'),
                       tick_seq, season_day, cum_offset_sec
                FROM game_ticks WHERE season_id=?
                ORDER BY to_char(game_date, 'YYYY-MM'), tick_seq
                """,
                (rs, n) -> new MonthTick(rs.getString(1), new TickPoint(rs.getLong(2), rs.getShort(3), rs.getInt(4))),
                seasonId);
    }

    /**
     * 일차마다 그 일차의 마지막 가격 틱. 일차별로 끝나는 시각이 달라 상수로 둘 수 없다.
     *
     * @param seasonId 대상 시즌
     * @return 일차 오름차순. 적재 전에는 빈 목록
     */
    public List<TickPoint> findDayLastTicks(long seasonId) {
        return jdbc.query("""
                SELECT DISTINCT ON (season_day) tick_seq, season_day, cum_offset_sec
                FROM game_ticks WHERE season_id=? ORDER BY season_day, tick_seq DESC
                """,
                (rs, n) -> new TickPoint(rs.getLong(1), rs.getShort(2), rs.getInt(3)), seasonId);
    }

    /**
     * 공모별 공시·마감·상장 틱. 셋을 각각 한 지점으로 낸다.
     *
     * @param seasonId 대상 시즌
     * @return 틱 순번 오름차순. 편성이 없으면 빈 목록
     */
    public List<IpoPoint> findIpoPoints(long seasonId) {
        return jdbc.query("""
                SELECT o.id, i.name, e.kind, t.tick_seq, t.season_day, t.cum_offset_sec
                FROM ipo_offerings o
                JOIN instruments i ON i.id = o.instrument_id
                CROSS JOIN LATERAL (VALUES ('DISCLOSURE', o.disclosure_tick_seq),
                                          ('CLOSE', o.close_tick_seq),
                                          ('LISTING', o.listing_tick_seq)) AS e(kind, seq)
                JOIN game_ticks t ON t.season_id = o.season_id AND t.tick_seq = e.seq
                WHERE o.season_id=?
                ORDER BY t.tick_seq, e.kind
                """,
                (rs, n) -> new IpoPoint(rs.getLong(1), rs.getString(2), rs.getString(3),
                        new TickPoint(rs.getLong(4), rs.getShort(5), rs.getInt(6))),
                seasonId);
    }

    /**
     * 공모 편성이 없는 상장의 틱. 코인 · 직상장 · SPAC · 이전상장이며 시즌 2 에 15 종 있다.
     *
     * <p><b>공모 편성이 있는 것은 뺀다.</b> 그 20 종은 {@link #findIpoPoints} 가 이미
     * {@code LISTING} 지점으로 내고 있어, 빼지 않으면 같은 틱에 지점이 둘 생긴다.
     *
     * <p>상장일이 {@code instruments} 에 날짜로만 남아 있어 {@code game_ticks} 를 거래일로
     * 잇는다 — 공모의 세 시각이 틱 순번인 것과 다르다.
     *
     * @param seasonId 대상 시즌
     * @return 틱 순번 오름차순. 그런 종목이 없으면 빈 목록
     */
    public List<ListingPoint> findListingPoints(long seasonId) {
        return jdbc.query("""
                SELECT i.id, i.name, t.tick_seq, t.season_day, t.cum_offset_sec
                FROM instruments i
                JOIN game_ticks t ON t.season_id = i.season_id AND t.game_date = i.listed_game_date
                WHERE i.season_id=? AND i.listed_game_date IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM ipo_offerings o WHERE o.instrument_id = i.id)
                ORDER BY t.tick_seq
                """,
                (rs, n) -> new ListingPoint(rs.getLong(1), rs.getString(2),
                        new TickPoint(rs.getLong(3), rs.getShort(4), rs.getInt(5))),
                seasonId);
    }

    /**
     * @param seasonId 대상 시즌
     * @param tickSeq  찾을 틱 순번
     * @return 그 틱의 좌표. 편성에 없으면 {@code null}
     */
    public TickPoint findTickPoint(long seasonId, long tickSeq) {
        List<TickPoint> ticks = jdbc.query("""
                SELECT tick_seq, season_day, cum_offset_sec FROM game_ticks WHERE season_id=? AND tick_seq=?
                """, (rs, n) -> new TickPoint(rs.getLong(1), rs.getShort(2), rs.getInt(3)), seasonId, tickSeq);
        return ticks.isEmpty() ? null : ticks.getFirst();
    }

    /**
     * 그 좌표보다 앞선 Tick 들에서 {@code back} 번째 것. 시간 경계는 Tick 이 아니므로
     * "경계 이전 Tick 들에서 N 개 뒤로"가 경계 지점의 {@code ticksBefore} 정의다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 기준 일차
     * @param offsetSec 기준 가상 초 위치. 이 위치는 포함하지 않는다
     * @param back      몇 번째 앞선 틱인지. 1 이 바로 앞 틱이다
     * @return 그 틱의 좌표. 그만큼 앞선 틱이 없으면 {@code null}
     */
    public TickPoint findTickBefore(long seasonId, short seasonDay, int offsetSec, int back) {
        List<TickPoint> ticks = jdbc.query("""
                SELECT tick_seq, season_day, cum_offset_sec FROM game_ticks
                WHERE season_id=? AND (season_day, cum_offset_sec) < (?, ?)
                ORDER BY tick_seq DESC OFFSET ? LIMIT 1
                """, (rs, n) -> new TickPoint(rs.getLong(1), rs.getShort(2), rs.getInt(3)),
                seasonId, seasonDay, offsetSec, back - 1);
        return ticks.isEmpty() ? null : ticks.getFirst();
    }

    /**
     * 불변 재생 일정 위의 가격 틱 좌표.
     *
     * @param seq       시즌 안에서 이어지는 순번
     * @param seasonDay 그 틱이 속한 일차
     * @param offsetSec 그 일차 거래 시작으로부터의 가상 초 오프셋
     */
    public record TickPoint(long seq, short seasonDay, int offsetSec) {
    }

    /**
     * 어떤 게임 월의 첫 틱.
     *
     * @param month {@code YYYY-MM}
     * @param tick  그 달의 첫 틱 좌표
     */
    public record MonthTick(String month, TickPoint tick) {
    }

    /**
     * 공모 하나의 국면 한 지점.
     *
     * @param offeringId 공모 식별자
     * @param name       공모 대상 종목명
     * @param kind       {@code DISCLOSURE} · {@code CLOSE} · {@code LISTING}
     * @param tick       그 국면이 발동하는 틱의 좌표
     */
    public record IpoPoint(long offeringId, String name, String kind, TickPoint tick) {
    }

    /**
     * 공모 편성이 없는 상장 하나.
     *
     * @param instrumentId 상장하는 종목 식별자
     * @param name         종목명
     * @param tick         그 종목의 첫 거래일 틱 좌표
     */
    public record ListingPoint(long instrumentId, String name, TickPoint tick) {
    }

    /**
     * 불변 재생 일정 위의 가격 틱 하나.
     *
     * @param seq    시즌 안에서 이어지는 순번
     * @param date   게임 거래일
     * @param offset 그 일차 거래 시작으로부터의 가상 초 오프셋
     * @param fx     그 거래일의 원/달러 환율
     */
    public record Tick(long seq, LocalDate date, int offset, BigDecimal fx) {
    }
}
