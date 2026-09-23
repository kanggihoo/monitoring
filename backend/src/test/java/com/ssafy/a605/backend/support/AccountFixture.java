package com.ssafy.a605.backend.support;

import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 시즌 계좌를 원시 SQL 로 만들고 읽고 치운다. 계좌 생성 경로를 <b>지나지 않고</b> 계좌가 있는
 * 상태를 만드는 것이 목적이다 — 생성 경로 자신을 검증하는 테스트는 여기를 쓰지 않고 관측만 한다.
 *
 * <p>기존 계좌 생성 테스트 27곳은 이관하지 않는다. 이관하면 diff 가 계좌 생성 변경을 삼켜
 * 리뷰어가 봐야 할 것을 못 본다 ({@code market-replay-account-enrollment.md} §픽스처).
 */
@TestComponent
@RequiredArgsConstructor
public class AccountFixture {

    /** 기획서 3.2.2 의 시드. 편성값({@code seasons.seed_krw})과 같은 값이다. */
    public static final String SEED_KRW = "100000000";

    /**
     * 계좌에 매달린 자식 테이블. 뒷정리 순서가 외래키 순서다 — 확정 정산이 그날의 최선·최악
     * 체결을 가리키고, 체결이 예약을 가리킨다.
     */
    private static final List<String> CHILD_TABLES = List.of(
            "daily_report_texts", "season_report_texts", "season_reports", "daily_settlements",
            "executions", "orders", "positions", "exchanges", "ipo_subscriptions", "notifications");

    private final JdbcTemplate jdbc;

    /**
     * 1일차에 시드로 합류한 계좌. 확인한 틱은 {@code 0} 이다.
     *
     * @param userId 계좌 주인
     * @return 만들어진 계좌 식별자
     */
    public long create(long userId) {
        return create(userId, (short) 1, 0L, SEED_KRW);
    }

    /**
     * 합류 일차와 확인한 틱, 원화 현금을 지정한 계좌.
     *
     * @param userId           계좌 주인
     * @param joinedSeasonDay  합류 일차
     * @param lastAckedTickSeq 부재 요약을 확인 처리한 틱
     * @param krwCash          원화 현금. 시작 잔고는 시드로 고정이라 수익률이 이 값에서 갈린다
     * @return 만들어진 계좌 식별자
     */
    public long create(long userId, short joinedSeasonDay, long lastAckedTickSeq, String krwCash) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, peak_valuation_krw, max_drawdown, created_at)
                VALUES (?, ?, CAST(? AS NUMERIC), 0, 0, 0,
                        CAST(? AS NUMERIC), false, ?, CAST(? AS NUMERIC), ?, ?,
                        CAST(? AS NUMERIC), 0, now())
                RETURNING id
                """, Long.class, userId, ReplayFixture.SEASON_ID, krwCash,
                SEED_KRW, joinedSeasonDay, SEED_KRW, joinedSeasonDay, lastAckedTickSeq, SEED_KRW);
    }

    /**
     * @param userId 계좌 주인
     * @return 그 시즌 계좌의 행. 없으면 {@code null}
     */
    public Map<String, Object> rowOf(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM accounts WHERE user_id = ? AND season_id = ?", userId, ReplayFixture.SEASON_ID);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * @param userId 계좌 주인
     * @return 그 사용자의 그 시즌 계좌 수. 멱등성은 이 값이 1 인 것으로 관측된다
     */
    public int countOf(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM accounts WHERE user_id = ? AND season_id = ?",
                Integer.class, userId, ReplayFixture.SEASON_ID);
    }

    /** 계좌와 그 자식 전부. 테스트 사이에 컨테이너를 공유하므로 만든 것은 치운다. */
    public void deleteAll(long userId) {
        CHILD_TABLES.forEach(table -> jdbc.update(
                "DELETE FROM " + table + " WHERE account_id IN (SELECT id FROM accounts WHERE user_id = ?)", userId));
        jdbc.update("DELETE FROM accounts WHERE user_id = ?", userId);
    }
}
