package com.ssafy.a605.backend.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.ranking.dto.PointStanding;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.ranking.service.PointStandingsCalculator;
import com.ssafy.a605.backend.domain.report.repository.DailyReportTextRepository;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 일차로 묻는 조회가 <b>다른 시즌의 같은 일차</b>를 집지 않는지 본다 ({@code S15P21A605-286}).
 *
 * <p><b>일차는 시즌마다 1~3 으로 다시 시작한다.</b> {@code daily_settlements} 와
 * {@code daily_report_texts} 의 키는 {@code (season_day, account_id)} 뿐이라 일차만으로는
 * 시즌이 갈리지 않는다. 시즌 2 를 같은 DB 에 적재한 뒤 이 조회들이 시즌 1 행을 함께 집었고,
 * 그중 {@code existsBySeasonDay} 는 시즌 2 첫 폐장을 "이미 확정됨" 으로 읽어 <b>정산을 통째로
 * 건너뛰게</b> 했다 — 순위·시상·리포트가 전부 나오지 않는 경로다.
 *
 * <p><b>조회를 직접 부른다.</b> 조건이 JPQL 안에 있어 서비스를 거치면 무엇이 걸렀는지가
 * 흐려진다. 여기서 보는 것은 "시즌 인자가 실제로 행을 가르는가" 하나다. 정산이 건너뛰어지지
 * 않는다는 사실 자체는 {@code DailySettlementServiceTest} 가 경계 이벤트로 본다.
 *
 * <p>진행 중인 시즌은 1 이고, 시즌 2 는 <b>오염원</b>으로만 둔다 — 반대 방향(진행 중인 시즌이
 * 2)도 같은 질의라 한쪽만 본다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class SeasonScopedSettlementQueryTest {

    private static final long CURRENT_SEASON = ReplayFixture.SEASON_ID;
    private static final long OTHER_SEASON = 2L;

    /** {@code PointStandingsCalculatorTest} 대역(940~)과 겹치지 않는 자리. */
    private static final long ACCOUNT_CURRENT = 970L;
    private static final long ACCOUNT_OTHER = 971L;

    private static final short DAY = 1;

    @Autowired
    private DailySettlementRepository settlements;

    @Autowired
    private DailyReportTextRepository texts;

    @Autowired
    private PointStandingsCalculator points;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReplayFixture fixture;

    @BeforeEach
    void setUp() {
        clean();
        givenOtherSeason();
        givenAccount(ACCOUNT_CURRENT, CURRENT_SEASON);
        givenAccount(ACCOUNT_OTHER, OTHER_SEASON);
        points.clear();
    }

    @AfterEach
    void tearDown() {
        clean();
        points.clear();
    }

    @Test
    @DisplayName("확정 여부_다른 시즌의 같은 일차 행만 있으면_이 시즌은 미확정이다")
    void exists_ignoresOtherSeason() {
        givenSettled(ACCOUNT_OTHER, 1, 1, 2, "10");

        assertThat(settlements.existsBySeasonDay(CURRENT_SEASON, DAY)).isFalse();
        assertThat(settlements.existsBySeasonDay(OTHER_SEASON, DAY)).isTrue();

        givenSettled(ACCOUNT_CURRENT, 1, 1, 2, "10");

        assertThat(settlements.existsBySeasonDay(CURRENT_SEASON, DAY)).isTrue();
    }

    @Test
    @DisplayName("일차 확정 행_이 시즌 계좌만 온다")
    void findBySeasonDay_ignoresOtherSeason() {
        givenSettled(ACCOUNT_CURRENT, 1, 2, 2, "10");
        givenSettled(ACCOUNT_OTHER, 1, 1, 2, "20");

        assertThat(settlements.findBySeasonDay(CURRENT_SEASON, DAY))
                .extracting(DailySettlement::getAccountId)
                .containsExactly(ACCOUNT_CURRENT);
    }

    @Test
    @DisplayName("참가자 수_다른 시즌 인원을 더하지 않는다")
    void countBySeasonDay_ignoresOtherSeason() {
        givenSettled(ACCOUNT_CURRENT, 1, 1, 2, "10");
        givenSettled(ACCOUNT_OTHER, 1, 1, 2, "10");

        assertThat(settlements.countBySeasonDay(CURRENT_SEASON, DAY)).isEqualTo(1);
    }

    /** 시상 조회와 시상 알림이 같은 메서드를 읽는다 — 여기가 갈리면 알림이 남의 시즌으로 간다. */
    @Test
    @DisplayName("일간 시상 대상_다른 시즌의 상위 계좌가 끼지 않는다")
    void dailyAwardRows_ignoreOtherSeason() {
        givenSettled(ACCOUNT_CURRENT, 1, 3, 2, "10");
        givenSettled(ACCOUNT_OTHER, 1, 1, 2, "99");

        assertThat(settlements.findBySeasonDayAndDailyRankLessThanEqualOrderByDailyRankAscAccountIdAsc(
                CURRENT_SEASON, DAY, 3))
                .extracting(DailySettlement::getAccountId, DailySettlement::getDailyRank)
                .containsExactly(tuple(ACCOUNT_CURRENT, 3));
    }

    /**
     * 목록은 {@code RankingService#enrolled} 가 어차피 거르지만 <b>순위 번호</b>는 집계 목록
     * 전체를 세워 매긴다. 다른 시즌 계좌가 섞이면 이 시즌 1 등이 그 수만큼 밀린 번호를 받는다.
     */
    @Test
    @DisplayName("포인트 순위_다른 시즌 계좌가 순위 번호를 밀지 않는다")
    void pointStandings_ignoreOtherSeason() {
        givenSettled(ACCOUNT_CURRENT, 1, 5, 2, "10");
        // 점수가 더 높은 다른 시즌 계좌. 함께 세우면 이 시즌 1 등이 2 위가 된다
        givenSettled(ACCOUNT_OTHER, 1, 1, 2, "99");

        assertThat(points.standings()).extracting(PointStanding::accountId, PointStanding::rank)
                .containsExactly(tuple(ACCOUNT_CURRENT, 1));
    }

    /** 이 가드가 다른 시즌 문구를 세면 새 시즌에서는 AI 문구가 한 번도 생성되지 않는다. */
    @Test
    @DisplayName("AI 문구 생성 가드_다른 시즌 문구를 세지 않는다")
    void reportTextCount_ignoresOtherSeason() {
        givenReportText(ACCOUNT_OTHER);

        assertThat(texts.countBySeasonDay(CURRENT_SEASON, DAY)).isZero();

        givenReportText(ACCOUNT_CURRENT);

        assertThat(texts.countBySeasonDay(CURRENT_SEASON, DAY)).isEqualTo(1);
    }

    /** 편성값은 시즌 1 과 같다 — 이 테스트가 보는 것은 계좌가 매달릴 시즌 행의 존재뿐이다. */
    private void givenOtherSeason() {
        jdbc.update("""
                INSERT INTO seasons (id, name, start_date, end_date, seed_krw,
                                     trade_fee_rate, exchange_fee_rate, ipo_winner_ratio, created_at)
                VALUES (?, '시즌 경계 검증용', '2026-09-24', '2026-09-26', 100000000, 0.15, 0.05, 30, now())
                ON CONFLICT (id) DO NOTHING
                """, OTHER_SEASON);
    }

    private void givenAccount(long accountId, long seasonId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, fixture.user("시즌경계참가자" + accountId).getId(), seasonId);
    }

    private void givenSettled(long accountId, int seasonDay, int dailyRank, int executionCount, String dailyReturn) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, ?, ?, 0, CAST(? AS NUMERIC), 100000000, ?, 0, 0, '[]'::jsonb, now())
                """, seasonDay, accountId, dailyRank, dailyRank, dailyReturn, executionCount);
    }

    private void givenReportText(long accountId) {
        jdbc.update("""
                INSERT INTO daily_report_texts (season_day, account_id, headline, scene_aliases,
                                                block_ids, generated_at)
                VALUES (?, ?, '머리말', '[]'::jsonb, '[]'::jsonb, now())
                """, DAY, accountId);
    }

    private void clean() {
        jdbc.update("DELETE FROM daily_report_texts WHERE account_id IN (?, ?)", ACCOUNT_CURRENT, ACCOUNT_OTHER);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)", ACCOUNT_CURRENT, ACCOUNT_OTHER);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)", ACCOUNT_CURRENT, ACCOUNT_OTHER);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_CURRENT, ACCOUNT_OTHER);
        // 시즌 행까지 치운다. R__ 픽스처는 시즌이 하나뿐인 DB 를 전제로 예외를 던지므로
        // ("Replay fixture requires an isolated test database") 검증용 시즌을 남기지 않는다.
        jdbc.update("DELETE FROM seasons WHERE id = ?", OTHER_SEASON);
    }
}
