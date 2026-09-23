package com.ssafy.a605.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ssafy.a605.backend.domain.report.service.SeasonReportService;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 시즌 리포트의 확정과 조회 ({@code rpt.md} §GET /api/v1/reports/season, 기획서 13.3).
 *
 * <p>보는 것은 셋이다. <b>확정</b>이 3일차 폐장 스냅샷에서 계좌 수만큼 행을 만드는가,
 * <b>최대 낙폭</b>이 매 틱 스윕에서 실제로 쌓이는가(이 값만은 시즌이 지나면 만들 수 없다),
 * 그리고 <b>조회</b>가 발표 전후로 닫히고 열리는가.
 *
 * <p>비교 기준선의 종목은 편성값이라 픽스처 종목으로 바꿔 세운다 — 기본값이 가리키는 유니버스
 * 종목(NQX · KTOP)은 검증용 DB 에 없다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50",
                // 검증 종목 A 를 나스닥 기준선 자리에 세운다.
                "app.report.benchmark.us-symbol=REPLAY_TEST_A",
                // 없는 심볼이다. 찾지 못한 기준선이 빠지는지 함께 본다.
                "app.report.benchmark.kr-symbol=REPLAY_TEST_NONE"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class SeasonReportApiTest {

    private static final String SEASON_URI = "/api/v1/reports/season";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final short FINAL_DAY = 3;
    private static final long ACCOUNT_ID = 950L;
    private static final long OTHER_ACCOUNT_ID = 951L;


    /** 3 일차 마지막 틱. 비교 기준선의 끝점이다 (2026-08-31, 종가 8,800). */
    private static final long LAST_TICK = 12L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeasonReportService seasonReports;


    private User owner;
    private String session;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        owner = fixture.user("시즌리포트");
        session = sessions.createSessionFor(owner);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM season_report_texts WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM season_reports WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Nested
    @DisplayName("확정")
    class Finalize {

        /**
         * 일차 경계마다 자산이 시드로 돌아가므로 최종 성적은 <b>마지막 일차 행 하나로 만들
         * 수 없다</b>. 세 일차를 모두 깔고, 네 지표가 각자 다른 방식으로 합쳐지는 것을 본다.
         */
        @Test
        @DisplayName("확정_3일차 폐장이면_계좌 수만큼 행이 생기고 일차를 합친 지표가 채워진다")
        void finalizeSeason_writesOneRowPerAccount() {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenAccount(OTHER_ACCOUNT_ID, "100000000", "100000000");
            // 계좌의 낙폭은 초기화가 매일 0 으로 되돌리므로 최종 성적의 근거가 아니다.
            givenDrawdown(ACCOUNT_ID, "-5.00");
            //          일차  일간순위  일간수익률   마감평가액     그 일차 낙폭
            givenDay(ACCOUNT_ID, 1, 1, "30.0000", "130000000", "-10.0000");
            givenDay(ACCOUNT_ID, 2, 2, "-20.0000", "80000000", "-31.4000");
            givenDay(ACCOUNT_ID, 3, 1, "50.0000", "150000000", "-5.0000");
            givenDay(OTHER_ACCOUNT_ID, 3, 2, "-10.0000", "90000000", "-2.0000");
            // 매도 둘 중 하나만 이익이라 승률 50%. 거래대금 5,000 만 + 5,000 만 = 1 억이다.
            givenExecution(ACCOUNT_ID, "SELL", "50000000", "100000", "1000000");
            givenExecution(ACCOUNT_ID, "SELL", "50000000", "100000", "-1000000");

            seasonReports.finalizeSeason(FINAL_DAY);

            Map<String, Object> row = report(ACCOUNT_ID);
            // 참가한 일차의 일간 수익률 합. 30 - 20 + 50 = 60 이다 (복리 합성이 아니다).
            assertThat((BigDecimal) row.get("final_return")).isEqualByComparingTo("60.0000");
            // 포인트 합산 1 위. 100 + 80 + 100 = 280 점으로, 3 일차만 80 점인 상대를 앞선다.
            assertThat(((Number) row.get("final_rank")).intValue()).isEqualTo(1);
            // 일차별 낙폭 중 가장 깊은 값. 계좌에 남은 -5.00 이 아니다.
            assertThat((BigDecimal) row.get("max_drawdown")).isEqualByComparingTo("-31.40");
            assertThat((BigDecimal) row.get("win_rate")).isEqualByComparingTo("50.00");
            // 분모는 일차별 평균 자산의 합이다 — (1+1.3)/2 + (1+0.8)/2 + (1+1.5)/2 = 3.3 억.
            // 1 억 / 3.3 억 = 0.3030... -> 0.30
            assertThat((BigDecimal) row.get("turnover")).isEqualByComparingTo("0.30");
            assertThat((BigDecimal) row.get("total_fee_krw")).isEqualByComparingTo("200000");
            assertThat(reportCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("확정_거래가 없는 계좌도_승률과 회전율 0 으로 행을 받는다")
        void finalizeSeason_withoutTrades_stillWritesRow() {
            // 걸러내면 그 사람만 시즌 화면이 404 가 된다.
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettlement(ACCOUNT_ID, 1, "0.0000", "100000000");

            seasonReports.finalizeSeason(FINAL_DAY);

            Map<String, Object> row = report(ACCOUNT_ID);
            assertThat((BigDecimal) row.get("win_rate")).isEqualByComparingTo("0");
            assertThat((BigDecimal) row.get("turnover")).isEqualByComparingTo("0");
            assertThat((BigDecimal) row.get("total_fee_krw")).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("확정_같은 시즌을 다시 확정해도_행이 늘지 않는다")
        void finalizeSeason_runTwice_isIdempotent() {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettlement(ACCOUNT_ID, 1, "10.0000", "110000000");

            seasonReports.finalizeSeason(FINAL_DAY);
            seasonReports.finalizeSeason(FINAL_DAY);

            assertThat(reportCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("확정_마지막 일차가 아니면_아무것도 만들지 않는다")
        void finalizeSeason_beforeFinalDay_doesNothing() {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettlement(ACCOUNT_ID, 1, "10.0000", "110000000");

            seasonReports.finalizeSeason((short) 1);

            assertThat(reportCount()).isZero();
        }
    }

    @Nested
    @DisplayName("조회")
    class Query {

        @Test
        @DisplayName("조회_3일차 발표 전이면_409 다")
        void getSeason_beforeAnnouncement_returnsConflict() throws Exception {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettling();

            mockMvc.perform(season())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("시즌 정산 발표 후에 열람할 수 있습니다."));
        }

        @Test
        @DisplayName("조회_발표 뒤에는_얼린 지표가 그대로 나온다")
        void getSeason_afterAnnouncement_returnsStoredMetrics() throws Exception {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettlement(ACCOUNT_ID, 1, "84.2000", "184200000");
            givenSeasonReport(ACCOUNT_ID, "84.2000", "-31.4000", "58.3000", "4.1200", "1284000", 1);
            givenAnnounced();

            mockMvc.perform(season())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.metrics.finalReturn").value(84.2))
                    .andExpect(jsonPath("$.data.metrics.maxDrawdown").value(-31.4))
                    .andExpect(jsonPath("$.data.metrics.winRate").value(58.3))
                    .andExpect(jsonPath("$.data.metrics.turnover").value(4.12))
                    // 금액은 문자열이다 (_common.md §6).
                    .andExpect(jsonPath("$.data.metrics.totalFeeKrw").value("1284000"))
                    .andExpect(jsonPath("$.data.finalRank").value(1))
                    .andExpect(jsonPath("$.data.totalParticipants").value(1))
                    .andExpect(jsonPath("$.data.finalizedAt").exists())
                    // 선택 기능이라 자리만 있다 (기획서 13.4 · 13.5).
                    // 문구가 없으면 자리를 비운 채 연다.
                    .andExpect(jsonPath("$.data.investorType").value(nullValue()))
                    .andExpect(jsonPath("$.data.shareCard").value(nullValue()));
        }

        @Test
        @DisplayName("조회_AI 유형이 있으면_이름·설명·총평이 실려 나온다")
        void getSeason_withInvestorType_carriesNameAndVerdict() throws Exception {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettlement(ACCOUNT_ID, 1, "84.2000", "184200000");
            givenSeasonReport(ACCOUNT_ID, "84.2000", "0.0000", "0.0000", "0.0000", "0", 1);
            givenInvestorType(ACCOUNT_ID);
            givenAnnounced();

            mockMvc.perform(season())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.investorType.name").value("추격 매수형"))
                    .andExpect(jsonPath("$.data.investorType.description")
                            .value("오르는 것을 따라가는 편입니다."))
                    // 총평이 시즌 리포트에서 가장 읽히는 한 줄이다.
                    .andExpect(jsonPath("$.data.investorType.verdict")
                            .value("고점에서 자주 손이 나갔습니다."))
                    // 유형 코드는 AI 가 블록을 고르는 축이라 계약에 올리지 않는다.
                    .andExpect(jsonPath("$.data.investorType.investorType").doesNotExist());
        }

        @Test
        @DisplayName("조회_비교 기준선의 마지막 줄은_내 최종 수익률이다")
        void getSeason_benchmarks_endWithMe() throws Exception {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenSettlement(ACCOUNT_ID, 1, "84.2000", "184200000");
            givenSeasonReport(ACCOUNT_ID, "84.2000", "0.0000", "0.0000", "0.0000", "0", 1);
            givenAnnounced();

            mockMvc.perform(season())
                    .andExpect(status().isOk())
                    // 없는 심볼의 기준선은 빠지고 찾은 것과 "나" 만 남는다.
                    .andExpect(jsonPath("$.data.benchmarks", hasSize(2)))
                    .andExpect(jsonPath("$.data.benchmarks[0].label").value("검증 종목 A 단순 보유"))
                    // 2020-01-02 종가 10,100 -> 2026-08-31 종가 8,800 = -12.87%
                    .andExpect(jsonPath("$.data.benchmarks[0].returnRate").value(-12.87))
                    .andExpect(jsonPath("$.data.benchmarks[1].label").value("나"))
                    .andExpect(jsonPath("$.data.benchmarks[1].returnRate").value(84.2));
        }

        @Test
        @DisplayName("조회_발표됐지만 내 행이 없으면_404 다")
        void getSeason_withoutRow_returnsNotFound() throws Exception {
            givenAccount(ACCOUNT_ID, "100000000", "100000000");
            givenAnnounced();

            mockMvc.perform(season())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 리포트가 없습니다."));
        }

        @Test
        @DisplayName("조회_세션이 없으면_401 이다")
        void getSeason_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isUnauthorized());
        }
    }

    private MockHttpServletRequestBuilder season() {
        return get(SEASON_URI).header(AUTH_TOKEN_HEADER, session);
    }

    /** 3일차 정산 발표가 끝난 상태. 열람 전용 구간에 시계를 세운다. */
    private void givenAnnounced() {
        fixture.store(FINAL_DAY, BigDecimal.valueOf(ReplayFixture.ANNOUNCE), ReplayFixture.START,
                BigDecimal.ZERO, LAST_TICK, (int) FINAL_DAY, ReplayFixture.ANNOUNCE);
    }

    /** 폐장은 지났지만 발표 전인 상태. 리포트는 만들어졌고 문은 아직 닫혀 있다. */
    private void givenSettling() {
        fixture.store(FINAL_DAY, BigDecimal.valueOf(ReplayFixture.CLOSE), ReplayFixture.START,
                BigDecimal.ZERO, LAST_TICK, (int) FINAL_DAY, ReplayFixture.CLOSE);
    }

    private void givenAccount(long accountId, String krwCash, String startingBalance) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, peak_valuation_krw, max_drawdown, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), 0, 0, 0, CAST(? AS NUMERIC), false, 1,
                        CAST(? AS NUMERIC), 1, 0, CAST(? AS NUMERIC), 0, now())
                """, accountId, accountId == ACCOUNT_ID ? owner.getId()
                        : fixture.user("시즌리포트" + accountId).getId(),
                ReplayFixture.SEASON_ID, krwCash, startingBalance, startingBalance,
                startingBalance);
    }

    /** 매 틱 스윕이 쌓아 둔 낙폭. 쌓는 과정은 위 {@code 최대 낙폭} 이 본다. */
    private void givenDrawdown(long accountId, String maxDrawdown) {
        jdbc.update("UPDATE accounts SET max_drawdown = CAST(? AS NUMERIC) WHERE id = ?",
                maxDrawdown, accountId);
    }


    /**
     * 한 일차의 확정 행. 최종 성적이 일차를 어떻게 합치는지 보려면 일차마다 값이 달라야 한다.
     *
     * <p>체결 건수를 1 로 둔다 — 0 이면 그날 포인트가 0 점이라 순위를 가를 수 없다.
     */
    private void givenDay(long accountId, int seasonDay, int dailyRank, String dailyReturn,
                          String totalValuationKrw, String maxDrawdown) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               max_drawdown, best_execution_id, worst_execution_id,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, ?, ?, 0, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 1, 0, 0,
                        CAST(? AS NUMERIC), NULL, NULL, '[]'::JSONB, now())
                """, seasonDay, accountId, dailyRank, dailyRank, dailyReturn, totalValuationKrw,
                maxDrawdown);
    }

    private void givenSettlement(long accountId, int seasonRank, String seasonReturn,
                                 String totalValuationKrw) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, ?, 1, CAST(? AS NUMERIC), 0, CAST(? AS NUMERIC), 0, 0, 0,
                        NULL, NULL, '[]'::JSONB, now())
                """, FINAL_DAY, accountId, seasonRank, seasonReturn, totalValuationKrw);
    }

    /** AI 가 만들어 저장한 시즌 문구. 생성 경로는 {@code AiSeasonReportApiTest} 가 본다. */
    private void givenInvestorType(long accountId) {
        jdbc.update("""
                INSERT INTO season_report_texts (account_id, investor_type, type_name,
                                                 type_description, verdict, block_ids,
                                                 generated_at)
                VALUES (?, 'MOMENTUM_CHASER', '추격 매수형', '오르는 것을 따라가는 편입니다.',
                        '고점에서 자주 손이 나갔습니다.', '["TYPE_MOMENTUM_1"]'::JSONB, now())
                """, accountId);
    }

    private void givenSeasonReport(long accountId, String finalReturn, String maxDrawdown,
                                   String winRate, String turnover, String totalFeeKrw,
                                   int finalRank) {
        jdbc.update("""
                INSERT INTO season_reports (account_id, final_return, max_drawdown, win_rate,
                                            turnover, total_fee_krw, final_rank, finalized_at)
                VALUES (?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, now())
                """, accountId, finalReturn, maxDrawdown, winRate, turnover, totalFeeKrw,
                finalRank);
    }

    /** 체결 한 건. 수량 1 이라 대금이 단가와 같고, 원화 종목이라 환산이 1 이다. */
    private void givenExecution(long accountId, String side, String price, String fee,
                                String realizedPnl) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount,
                                        applied_fee_rate, fee, realized_pnl, tick_seq, game_date,
                                        executed_at)
                SELECT ?, i.id, NULL, 'MARKET', ?, 1, CAST(? AS NUMERIC), i.currency,
                       CAST(? AS NUMERIC), 0, CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                       t.tick_seq, t.game_date, now()
                FROM instruments i, game_ticks t
                WHERE i.symbol = 'REPLAY_TEST_A' AND t.tick_seq = ?
                """, accountId, side, price, price, fee, realizedPnl, LAST_TICK);
    }

    private Map<String, Object> report(long accountId) {
        return jdbc.queryForMap("SELECT * FROM season_reports WHERE account_id = ?", accountId);
    }

    private int reportCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM season_reports WHERE account_id IN (?, ?)", Integer.class,
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }


}
