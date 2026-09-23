package com.ssafy.a605.backend.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.service.RankingRecalculator;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 폐장 경계 이벤트 한 번에 마지막 틱의 순위가 {@code daily_settlements} 로 얼려지고, 다음 날
 * 브리핑에 그 값이 일간 기준값으로 넘어가는지 본다.
 *
 * <p>순위는 {@code RankingRecalculatorTest} 와 같은 방식으로 만든다 — 틱 이벤트를 직접 발행해
 * 실제 스윕과 계산기가 낸 값을 얼린다. 경계 이벤트도 직접 발행한다. 폴러가 경계를 커밋한 뒤
 * 같은 객체를 발행하므로 여기서는 그 뒤의 일만 검증한다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                // 픽스처를 여기서 켠다. 선언하지 않으면 .env 의 REPLAY_FIXTURE_ENABLED 가
                // 결과를 정하고, 그 파일은 버전 관리되지 않아 사람마다 다른 DB 를 본다.
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class,
        DailySettlementServiceTest.FinalizedEvents.class})
class DailySettlementServiceTest {

    /** 픽스처 1일차 마지막 틱. 게임 거래일 2022-03-31, 환율 1200. 검증 종목 종가 9,600. */
    private static final long LAST_TICK_DAY_1 = 4L;
    private static final LocalDate LAST_DATE_DAY_1 = LocalDate.of(2022, 3, 31);

    /** 픽스처 2일차 첫 틱. 게임 거래일 2022-04-01, 환율 1210. 검증 종목 종가 10,500. */
    private static final long FIRST_TICK_DAY_2 = 5L;
    private static final LocalDate FIRST_DATE_DAY_2 = LocalDate.of(2022, 4, 1);

    /** 1일차의 앞 세 틱. 종가는 tick_seq 로 정해진다 (픽스처: 10,000 +- tick x 100). */
    private static final long TICK_1 = 1L;
    private static final long TICK_2 = 2L;
    private static final long TICK_3 = 3L;
    private static final LocalDate DATE_1 = LocalDate.of(2020, 1, 2);
    private static final LocalDate DATE_2 = LocalDate.of(2020, 1, 3);
    private static final LocalDate DATE_3 = LocalDate.of(2020, 1, 6);

    /** 픽스처가 검증 종목 B 의 종가를 비워 둔 거래일. 태그 검증에 쓰고 되돌린다. */
    private static final LocalDate GAP_DATE_DAY_1 = DATE_2;

    private static final long ACCOUNT_A = 930L;
    private static final long ACCOUNT_B = 931L;
    /** 정산 뒤에 합류하는 계좌 (S15P21A605-248) */
    private static final long ACCOUNT_C = 932L;
    /** 지난 시즌의 같은 일차에 이미 확정된 계좌 (S15P21A605-286) */
    private static final long ACCOUNT_OTHER_SEASON = 933L;
    private static final long OTHER_SEASON = 2L;

    @Autowired
    private RankingRecalculator recalculator;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinalizedEvents finalizedEvents;

    @Autowired
    private com.ssafy.a605.backend.domain.ranking.service.DailySettlementService settlements;

    private User ownerA;
    private User ownerB;
    private User ownerC;
    private long krwInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        finalizedEvents.received.clear();
        ownerA = fixture.user("정산참가자A");
        ownerB = fixture.user("정산참가자B");
        ownerC = fixture.user("정산참가자C");
        krwInstrument = instrumentId("REPLAY_TEST_A");
        // A: 현금 1억 (0.00%). B: 현금 9천만 + 원화 종목 10주 x 9,600 = 90,096,000 (-9.90%)
        givenAccount(ACCOUNT_A, "100000000");
        givenAccount(ACCOUNT_B, "90000000");
        givenPosition(ACCOUNT_B, krwInstrument, 10);
        events.publishEvent(new TickPublishedEvent(LAST_TICK_DAY_1, LAST_DATE_DAY_1));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?, ?, ?)",
                ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_OTHER_SEASON);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?, ?, ?)",
                ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_OTHER_SEASON);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)", ACCOUNT_A, ACCOUNT_B);
        jdbc.update("DELETE FROM orders WHERE account_id IN (?, ?, ?)",
                ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        // 픽스처가 일부러 비워 둔 행이다. 넣었으면 되돌린다 — 다른 테스트가 휴장으로 본다.
        jdbc.update("DELETE FROM daily_closes WHERE instrument_id = ? AND game_date = ?",
                instrumentId("REPLAY_TEST_B"), java.sql.Date.valueOf(GAP_DATE_DAY_1));
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?, ?)",
                ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)",
                ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_OTHER_SEASON);
        // R__ 픽스처가 시즌이 하나뿐인 DB 를 전제로 예외를 던진다 — 검증용 시즌을 남기지 않는다.
        jdbc.update("DELETE FROM seasons WHERE id = ?", OTHER_SEASON);
    }

    @Test
    @DisplayName("폐장 경계_마지막 틱의 순위가 순위에 든 계좌 수만큼 daily_settlements 에 얼려진다")
    void onClose_freezesLastTickRankingForEveryRankedAccount() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        assertThat(settlementCount(1)).isEqualTo(2);
        Map<String, Object> a = settlement(1, ACCOUNT_A);
        assertThat(((Number) a.get("season_rank")).intValue()).isEqualTo(1);
        assertThat(((Number) a.get("daily_rank")).intValue()).isEqualTo(1);
        assertThat((BigDecimal) a.get("total_valuation_krw")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) a.get("season_return")).isEqualByComparingTo("0");
        Map<String, Object> b = settlement(1, ACCOUNT_B);
        assertThat(((Number) b.get("season_rank")).intValue()).isEqualTo(2);
        assertThat((BigDecimal) b.get("total_valuation_krw")).isEqualByComparingTo("90096000");
        assertThat((BigDecimal) b.get("season_return")).isEqualByComparingTo("-9.90");
        assertThat((BigDecimal) b.get("daily_return")).isEqualByComparingTo("-9.90");
        // 거래가 없는 계좌라 복기 집계가 기본값이다.
        assertThat(((Number) b.get("execution_count")).intValue()).isZero();
        assertThat((BigDecimal) b.get("total_fee_krw")).isEqualByComparingTo("0");
        assertThat(b.get("best_execution_id")).isNull();
        assertThat(b.get("pattern_tags").toString()).isEqualTo("[]");
        assertThat(b.get("finalized_at")).isNotNull();

        assertThat(finalizedEvents.received).hasSize(1);
        assertThat(finalizedEvents.received.get(0).seasonDay()).isEqualTo((short) 1);
        assertThat(finalizedEvents.received.get(0).accountCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("폐장 경계_같은 일차로 다시 실행돼도_행 수가 늘지 않고 확정 이벤트도 다시 나가지 않는다")
    void onClose_repeatedForSameDay_isIdempotent() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        assertThat(settlementCount(1)).isEqualTo(2);
        assertThat(finalizedEvents.received).hasSize(1);
    }

    /**
     * 확정 여부를 일차로만 물으면 지난 시즌의 1 일차 행에 걸려 <b>정산이 통째로 건너뛰어진다</b>
     * — 순위·시상·일간 리포트·시즌 리포트가 전부 나오지 않는다 (S15P21A605-286).
     */
    @Test
    @DisplayName("폐장 경계_다른 시즌의 같은 일차가 이미 확정돼 있어도_이 시즌 정산은 실행된다")
    void onClose_withOtherSeasonSettledForSameDay_stillFinalizes() {
        givenOtherSeasonSettled();

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        assertThat(settlementCount(1)).isEqualTo(2);
        assertThat(finalizedEvents.received).hasSize(1);
    }

    @Test
    @DisplayName("확정 후_계좌가 거래해 평가액이 바뀌어도_daily_settlements 값은 그대로다")
    void afterClose_accountChanges_doNotAlterSettlement() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        // 폐장 뒤의 거래를 흉내 낸다 — 현금이 바뀌고 다음 틱이 순위를 다시 만든다.
        jdbc.update("UPDATE accounts SET krw_cash = 50000000 WHERE id = ?", ACCOUNT_A);
        events.publishEvent(new TickPublishedEvent(FIRST_TICK_DAY_2, FIRST_DATE_DAY_2));

        Map<String, Object> a = settlement(1, ACCOUNT_A);
        assertThat((BigDecimal) a.get("total_valuation_krw")).isEqualByComparingTo("100000000");
        assertThat(((Number) a.get("season_rank")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 표시_폐장에 켜지고_다음 날 브리핑에 꺼진다")
    void finalizedFlag_setAtClose_clearedAtNextBriefing() {
        assertThat(recalculator.current().finalized()).isFalse();

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        RankingSnapshot frozen = recalculator.current();
        assertThat(frozen.finalized()).isTrue();
        assertThat(frozen.tickSeq()).isEqualTo(LAST_TICK_DAY_1);

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));
        assertThat(recalculator.current().finalized()).isFalse();
        assertThat(recalculator.current().tickSeq()).isEqualTo(LAST_TICK_DAY_1);
    }

    @Test
    @DisplayName("폐장 경계_마지막 틱 뒤에 탈퇴한 계좌가 있어도_남은 계좌는 확정된다")
    void onClose_accountDeletedAfterLastTick_freezesRemainingAccounts() {
        // A 는 스냅샷에 있지만 폐장 전에 사라졌다. FK 위반 한 건이 전체를 롤백시키면 안 된다.
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_A);

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        assertThat(settlementCount(1)).isEqualTo(1);
        assertThat(settlement(1, ACCOUNT_B)).isNotNull();
        assertThat(finalizedEvents.received).hasSize(1);
        assertThat(finalizedEvents.received.get(0).accountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("폐장 경계_그 일차의 순위가 없으면_아무것도 저장하지 않는다")
    void onClose_withoutSnapshotForThatDay_savesNothing() {
        // 순위는 1일차 마지막 틱 것인데 폐장은 2일차다 — 다른 일차의 값을 얼리지 않는다.
        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.CLOSE));

        assertThat(settlementCount(2)).isZero();
        assertThat(finalizedEvents.received).isEmpty();
    }

    @Test
    @DisplayName("폐장 경계_그 일차의 보유와 마감 현금이_daily_settlements 와 같은 트랜잭션에 남는다")
    void onClose_savesClosingHoldingsSnapshot() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        // B 는 원화 종목 10 주(마지막 틱 종가 9,600)를 들고 폐장했다.
        Map<String, Object> holding = jdbc.queryForMap(
                "SELECT * FROM daily_holdings WHERE season_day = 1 AND account_id = ?", ACCOUNT_B);
        assertThat(((Number) holding.get("instrument_id")).longValue()).isEqualTo(krwInstrument);
        assertThat(((Number) holding.get("quantity")).intValue()).isEqualTo(10);
        assertThat((BigDecimal) holding.get("valuation_krw")).isEqualByComparingTo("96000");

        // A 는 현금만 들고 있었다 — 0 행이 정상이고, 그래서 "행이 있는가"로 판정할 수 없다.
        assertThat(holdingCount(1, ACCOUNT_A)).isZero();
        Map<String, Object> a = settlement(1, ACCOUNT_A);
        assertThat((BigDecimal) a.get("closing_krw_cash")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) a.get("closing_usd_cash")).isEqualByComparingTo("0");
        assertThat(((Number) a.get("closing_tick_seq")).longValue()).isEqualTo(LAST_TICK_DAY_1);
        assertThat(a.get("holdings_snapshotted_at")).isNotNull();

        // 확정 평가액 = 마감 현금 + 마감 보유 평가액.
        Map<String, Object> b = settlement(1, ACCOUNT_B);
        assertThat((BigDecimal) b.get("closing_krw_cash")).isEqualByComparingTo("90000000");
        assertThat((BigDecimal) b.get("total_valuation_krw")).isEqualByComparingTo("90096000");
    }

    /**
     * <b>마지막 틱은 폐장보다 31 분 앞서 끝난다</b> (시즌 2 편성: 마지막 틱 19,440 초 ·
     * 폐장 21,300 초). 그 사이의 정상 매도·환전은 확정 평가액(마지막 틱 순위 스냅샷)과
     * 폐장 시점 잔고를 갈라놓는다. 그 차이로 스냅샷 표식을 빼면 <b>늦은 거래 한 건이
     * 다음 날 전 계좌의 초기화를 막는다.</b>
     */
    @Test
    @DisplayName("마지막 틱 이후 매도_확정 평가액과 마감 잔고가 달라도_스냅샷과 다음 날 초기화가 정상이다")
    void afterLastTickTrade_stillSnapshotsAndResets() {
        // 마지막 틱이 공개된 뒤 폐장 전에 10 주를 96,000 + 4,000 에 판 상태.
        jdbc.update("UPDATE accounts SET krw_cash = 90100000 WHERE id = ?", ACCOUNT_B);
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_B);

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        Map<String, Object> b = settlement(1, ACCOUNT_B);
        // 확정 평가액은 마지막 틱 것 그대로다 — 폐장에 재계산하지 않는 정책은 그대로 둔다.
        assertThat((BigDecimal) b.get("total_valuation_krw")).isEqualByComparingTo("90096000");
        // 마감 현금은 실제 폐장 잔고이며, 둘이 달라도 표식은 찍힌다.
        assertThat((BigDecimal) b.get("closing_krw_cash")).isEqualByComparingTo("90100000");
        assertThat(b.get("holdings_snapshotted_at")).isNotNull();
        assertThat(holdingCount(1, ACCOUNT_B)).isZero();

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_B)).isEqualByComparingTo("100000000");
        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_A)).isEqualByComparingTo("100000000");
    }

    @Test
    @DisplayName("다음 날 브리핑_전 계좌가 시드로 돌아가고_보유가 사라진다")
    void onNextBriefing_resetsEveryAccountToSeed() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        Map<String, Object> b = jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", ACCOUNT_B);
        assertThat((BigDecimal) b.get("krw_cash")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) b.get("usd_cash")).isEqualByComparingTo("0");
        assertThat((BigDecimal) b.get("locked_krw")).isEqualByComparingTo("0");
        assertThat((BigDecimal) b.get("starting_balance_krw")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) b.get("daily_base_valuation_krw")).isEqualByComparingTo("100000000");
        assertThat(((Number) b.get("daily_base_season_day")).intValue()).isEqualTo(2);
        assertThat((BigDecimal) b.get("peak_valuation_krw")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) b.get("max_drawdown")).isEqualByComparingTo("0");
        assertThat(positionCount(ACCOUNT_B)).isZero();

        // 1일차 기록은 그대로다 — 초기화는 확정 행을 건드리지 않는다.
        assertThat((BigDecimal) settlement(1, ACCOUNT_B).get("total_valuation_krw"))
                .isEqualByComparingTo("90096000");
        assertThat(holdingCount(1, ACCOUNT_B)).isEqualTo(1);
    }

    /**
     * {@code Account.resetToSeed()} 를 재사용하면 참가 일차와 확인한 틱까지 되돌아가
     * <b>부재 요약이 조용히 깨진다</b> — 모두가 시즌 첫 틱부터 부재로 잡힌다.
     */
    @Test
    @DisplayName("다음 날 브리핑_초기화가_참가 일차와 확인한 틱은 보존한다")
    void onNextBriefing_reset_preservesJoinDayAndAck() {
        jdbc.update("UPDATE accounts SET last_acked_tick_seq = ? WHERE id = ?",
                LAST_TICK_DAY_1, ACCOUNT_B);
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        Map<String, Object> b = jdbc.queryForMap(
                "SELECT joined_season_day, last_acked_tick_seq FROM accounts WHERE id = ?", ACCOUNT_B);
        assertThat(((Number) b.get("joined_season_day")).intValue()).isEqualTo(1);
        assertThat(((Number) b.get("last_acked_tick_seq")).longValue()).isEqualTo(LAST_TICK_DAY_1);
    }

    /**
     * 정산은 마지막 틱 시점의 순위 스냅샷에 든 계좌만 담는다. 그 뒤에 생긴 계좌가 초기화에서
     * 빠지면 그 계좌만 어제 잔고로 거래한다 (S15P21A605-248 과 같은 자리).
     */
    @Test
    @DisplayName("다음 날 브리핑_정산 뒤에 생긴 계좌도_초기화 대상이다")
    void onNextBriefing_accountCreatedAfterSettlement_isAlsoReset() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        // 확정이 끝난 뒤 합류한다 — 이 계좌는 daily_settlements 에 행이 없다.
        givenAccount(ACCOUNT_C, "70000000");

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        Map<String, Object> c = jdbc.queryForMap(
                "SELECT krw_cash, daily_base_season_day FROM accounts WHERE id = ?", ACCOUNT_C);
        assertThat((BigDecimal) c.get("krw_cash")).isEqualByComparingTo("100000000");
        assertThat(((Number) c.get("daily_base_season_day")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("초기화 재실행_이미 초기화된 뒤 거래한 계좌는_다시 지우지 않는다")
    void resetDaily_rerunAfterTrading_keepsTheResult() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));
        // 2일차에 매수한 상태를 흉내 낸다.
        jdbc.update("UPDATE accounts SET krw_cash = 40000000 WHERE id = ?", ACCOUNT_B);
        givenPosition(ACCOUNT_B, krwInstrument, 5);

        settlements.resetDaily((short) 2);

        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_B)).isEqualByComparingTo("40000000");
        assertThat(positionCount(ACCOUNT_B)).isEqualTo(1);
    }

    /**
     * 폐장 만료를 놓친 예약이 남으면, 잠긴 현금의 근거가 사라진 채 다음 날 체결되어
     * <b>초기화된 잔고에서 어제 주문이 돈을 쓴다.</b> 정상 경로라면 대상이 없다.
     */
    @Test
    @DisplayName("다음 날 브리핑_폐장에 닫히지 않은 예약이 남아 있으면_초기화 전에 닫힌다")
    void onNextBriefing_expiresLeftoverOpenOrders() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        // 폐장 만료가 돌지 않은 상태를 흉내 낸다 — 예약이 OPEN 이고 현금이 잠겨 있다.
        givenOpenBuyOrder(ACCOUNT_B, krwInstrument, "1000000");

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM orders WHERE account_id = ?", String.class, ACCOUNT_B))
                .isEqualTo("EXPIRED");
        Map<String, Object> b = jdbc.queryForMap(
                "SELECT krw_cash, locked_krw, daily_base_season_day FROM accounts WHERE id = ?", ACCOUNT_B);
        assertThat((BigDecimal) b.get("krw_cash")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) b.get("locked_krw")).isEqualByComparingTo("0");
        assertThat(((Number) b.get("daily_base_season_day")).intValue()).isEqualTo(2);
    }

    /**
     * 검증 없이 열면 3일차 정산 뒤의 {@code /4} 가 전일(3일차) 조건을 통과해 <b>시즌 최종
     * 자산을 전부 시드로 지운다.</b>
     */
    @Test
    @DisplayName("수동 재실행_초기화가 없는 일차면_400 이고 자산을 건드리지 않는다")
    void rerunDailyReset_forDayOutsideRange_isRejected() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        assertThatThrownBy(() -> settlements.rerunDailyReset((short) 4))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2~3");
        assertThatThrownBy(() -> settlements.rerunDailyReset((short) 1))
                .isInstanceOf(BusinessException.class);

        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_B)).isEqualByComparingTo("90000000");
    }

    @Test
    @DisplayName("수동 재실행_아직 그 일차에 도달하지 않았으면_409 이고 자산을 건드리지 않는다")
    void rerunDailyReset_beforeThatDay_isRejected() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        // 시계는 아직 1일차다 — 폐장은 지났지만 2일차 브리핑에 들어가지 않았다.

        assertThatThrownBy(() -> settlements.rerunDailyReset((short) 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("도달하지");

        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_B)).isEqualByComparingTo("90000000");
        assertThat(positionCount(ACCOUNT_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 날 브리핑_전날 마감 보유 스냅샷이 없으면_아무 계좌도 초기화하지 않는다")
    void onNextBriefing_withoutClosingSnapshot_resetsNothing() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        // 스냅샷 표식만 지운다 — V42 이전에 확정된 행이 이 상태다.
        jdbc.update("UPDATE daily_settlements SET holdings_snapshotted_at = NULL WHERE season_day = 1");

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_B)).isEqualByComparingTo("90000000");
        assertThat(positionCount(ACCOUNT_B)).isEqualTo(1);
    }

    /**
     * 확정 행이 없는 계좌의 보유는 {@code daily_holdings} 에도 없다 — 스냅샷이 확정 행에
     * 매달리기 때문이다. 지우면 어디에도 남지 않으므로 초기화 전체를 되돌린다.
     */
    @Test
    @DisplayName("다음 날 브리핑_전날 정산이 없는데 보유가 남은 계좌가 있으면_초기화 전체가 되돌아간다")
    void onNextBriefing_withUnrecordedHoldings_rollsBackEverything() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
        givenAccount(ACCOUNT_C, "70000000");
        givenPosition(ACCOUNT_C, krwInstrument, 3);

        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        // C 뿐 아니라 A·B 도 그대로여야 한다 — 부분 초기화는 없다.
        assertThat(jdbc.queryForObject("SELECT krw_cash FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_B)).isEqualByComparingTo("90000000");
        assertThat(positionCount(ACCOUNT_B)).isEqualTo(1);
        assertThat(positionCount(ACCOUNT_C)).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 날 브리핑_전날 확정 행이 없으면_계좌를 건드리지 않는다")
    void onNextBriefing_withoutPreviousSettlement_resetsNothing() {
        events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

        Map<String, Object> b = jdbc.queryForMap(
                "SELECT krw_cash, daily_base_season_day FROM accounts WHERE id = ?", ACCOUNT_B);
        assertThat((BigDecimal) b.get("krw_cash")).isEqualByComparingTo("90000000");
        assertThat(((Number) b.get("daily_base_season_day")).intValue()).isEqualTo(1);
        assertThat(positionCount(ACCOUNT_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("폐장 경계_그 일차에 거래한 계좌는_복기 집계가 실제 값으로 채워진다")
    void onClose_fillsReviewAggregateFromExecutions() {
        long b = instrumentId("REPLAY_TEST_B");
        long c = instrumentId("REPLAY_TEST_C");
        // 픽스처 1일차 폐장 종가는 9,600 이다(마지막 틱 4). 그보다 10% 이상 높은 값에 산
        // 체결을 만들려면 그 거래일의 종가 자체가 높아야 하므로, 비어 있는 자리를 채운다.
        givenGapClose(b, "11000");
        // 11,000 에 매수 -> 폐장 종가 9,600 = -12.73% -> 고점 매수
        long bought = givenExecution(ACCOUNT_B, b, "BUY", "11000", "KRW", "16", null, TICK_2, DATE_2);
        // 10,300 에 매도 -> -6.80% 라 저점 매도 기준(+10%)에 못 미친다
        long sold = givenExecution(ACCOUNT_B, b, "SELL", "10300", "KRW", "15", "-1600", TICK_3, DATE_3);
        // 달러 종목. 수수료 0.15 x 그 틱 환율 1,190 = 178.5 원
        givenExecution(ACCOUNT_B, c, "BUY", "10300.00", "USD", "0.15", null, TICK_3, DATE_3);

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        Map<String, Object> row = settlement(1, ACCOUNT_B);
        assertThat(((Number) row.get("execution_count")).intValue()).isEqualTo(3);
        // 16 + 15 + 178.5 = 209.5 -> 210. 체결마다 원 단위로 끊으면 209 가 된다.
        assertThat((BigDecimal) row.get("total_fee_krw")).isEqualByComparingTo("210");
        assertThat((BigDecimal) row.get("realized_pnl_krw")).isEqualByComparingTo("-1600");
        // 매도가 한 건이라 상위와 하위가 같다.
        assertThat(((Number) row.get("best_execution_id")).longValue()).isEqualTo(sold);
        assertThat(((Number) row.get("worst_execution_id")).longValue()).isEqualTo(sold);
        // JSON 문자열을 대조하지 않는다 - jsonb 가 공백을 넣어 정규화하므로 값을 본다.
        assertThat(tagCount(1, ACCOUNT_B)).isEqualTo(1);
        assertThat(tag(1, ACCOUNT_B, 0, "kind")).isEqualTo("PANIC_BUY_HIGH");
        assertThat(tag(1, ACCOUNT_B, 0, "executionId")).isEqualTo(String.valueOf(bought));
        assertThat(tag(1, ACCOUNT_B, 0, "instrumentId")).isEqualTo(String.valueOf(b));
        assertThat(new BigDecimal(tag(1, ACCOUNT_B, 0, "changeRate"))).isEqualByComparingTo("-12.73");

        // 거래하지 않은 계좌는 기본값 그대로다.
        Map<String, Object> idle = settlement(1, ACCOUNT_A);
        assertThat(((Number) idle.get("execution_count")).intValue()).isZero();
        assertThat(idle.get("pattern_tags").toString()).isEqualTo("[]");
    }

    @Test
    @DisplayName("폐장 경계_다른 일차의 체결은_그 일차 집계에 섞이지 않는다")
    void onClose_ignoresExecutionsFromOtherDays() {
        long b = instrumentId("REPLAY_TEST_B");
        givenExecution(ACCOUNT_B, b, "BUY", "10100", "KRW", "15", null, TICK_1, DATE_1);
        // 2일차 첫 틱의 체결이다. 1일차 폐장 집계가 이것을 세면 안 된다.
        givenExecution(ACCOUNT_B, b, "BUY", "10500", "KRW", "16", null, FIRST_TICK_DAY_2,
                FIRST_DATE_DAY_2);

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        Map<String, Object> row = settlement(1, ACCOUNT_B);
        assertThat(((Number) row.get("execution_count")).intValue()).isEqualTo(1);
        assertThat((BigDecimal) row.get("total_fee_krw")).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("폐장 경계_폐장 종가에 체결한 건은_변화율이 0 이라 태그가 붙지 않는다")
    void onClose_tradedAtDayEndClose_isNotTagged() {
        long b = instrumentId("REPLAY_TEST_B");
        // 1일차 마지막 틱(2022-03-31, 종가 9,600)이 곧 판정 기준이다. 체결가와 같아 0% 다.
        givenExecution(ACCOUNT_B, b, "BUY", "9600", "KRW", "14", null, LAST_TICK_DAY_1,
                LAST_DATE_DAY_1);

        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

        Map<String, Object> row = settlement(1, ACCOUNT_B);
        assertThat(((Number) row.get("execution_count")).intValue()).isEqualTo(1);
        assertThat(row.get("pattern_tags").toString()).isEqualTo("[]");
    }

    /** 픽스처가 비워 둔 검증 종목 B 의 1일차 둘째 거래일 종가. tearDown 이 되돌린다. */
    private void givenGapClose(long instrumentId, String close) {
        jdbc.update("""
                INSERT INTO daily_closes (instrument_id, game_date, close_price, raw_close)
                VALUES (?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC))
                """, instrumentId, java.sql.Date.valueOf(GAP_DATE_DAY_1), close, close);
    }

    /**
     * 체결 한 건. 주문 도메인을 거치지 않고 행을 직접 쓴다 — 여기서 보는 것은 이미 쌓인 체결을
     * 폐장이 어떻게 집계하는가이고, 체결이 만들어지는 경로는 주문 테스트가 본다.
     */
    private long givenExecution(long accountId, long instrumentId, String side, String price,
                                String currency, String fee, String realizedPnl, long tickSeq,
                                LocalDate gameDate) {
        return jdbc.queryForObject("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount,
                                        applied_fee_rate, fee, realized_pnl, tick_seq, game_date,
                                        executed_at)
                VALUES (?, ?, NULL, 'MARKET', ?, 1, CAST(? AS NUMERIC), ?, CAST(? AS NUMERIC),
                        0.15, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, now())
                RETURNING id
                """, Long.class, accountId, instrumentId, side, price, currency, price, fee,
                realizedPnl, tickSeq, java.sql.Date.valueOf(gameDate));
    }

    /** 그 행의 패턴 태그 개수. */
    private int tagCount(int seasonDay, long accountId) {
        return jdbc.queryForObject("""
                SELECT jsonb_array_length(pattern_tags) FROM daily_settlements
                WHERE season_day = ? AND account_id = ?
                """, Integer.class, seasonDay, accountId);
    }

    /** 패턴 태그 배열의 index 번째 항목에서 필드 하나를 문자열로 읽는다. */
    private String tag(int seasonDay, long accountId, int index, String field) {
        return jdbc.queryForObject("""
                SELECT pattern_tags -> ? ->> ? FROM daily_settlements
                WHERE season_day = ? AND account_id = ?
                """, String.class, index, field, seasonDay, accountId);
    }

    private int holdingCount(int seasonDay, long accountId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM daily_holdings WHERE season_day = ? AND account_id = ?",
                Integer.class, seasonDay, accountId);
    }

    private int positionCount(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM positions WHERE account_id = ?",
                Integer.class, accountId);
    }

    private int settlementCount(int seasonDay) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM daily_settlements WHERE season_day = ? AND account_id IN (?, ?)",
                Integer.class, seasonDay, ACCOUNT_A, ACCOUNT_B);
    }

    private Map<String, Object> settlement(int seasonDay, long accountId) {
        return jdbc.queryForMap("SELECT * FROM daily_settlements WHERE season_day = ? AND account_id = ?",
                seasonDay, accountId);
    }

    /** 계좌마다 주인이 다르다 — accounts 에 (user_id, season_id) UNIQUE 가 있다. */
    private User ownerOf(long accountId) {
        return accountId == ACCOUNT_A ? ownerA : accountId == ACCOUNT_B ? ownerB : ownerC;
    }

    private void givenAccount(long accountId, String krwCash) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), 0, 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, ownerOf(accountId).getId(), ReplayFixture.SEASON_ID,
                krwCash);
    }

    /** 지난 시즌의 1 일차 확정 행. 이 시즌의 확정 여부를 흐리는 오염원이다 (S15P21A605-286). */
    private void givenOtherSeasonSettled() {
        jdbc.update("""
                INSERT INTO seasons (id, name, start_date, end_date, seed_krw,
                                     trade_fee_rate, exchange_fee_rate, ipo_winner_ratio, created_at)
                VALUES (?, '시즌 경계 검증용', '2026-09-24', '2026-09-26', 100000000, 0.15, 0.05, 30, now())
                ON CONFLICT (id) DO NOTHING
                """, OTHER_SEASON);
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_OTHER_SEASON, fixture.user("지난시즌참가자").getId(), OTHER_SEASON);
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               pattern_tags, finalized_at)
                VALUES (1, ?, 1, 1, 0, 0, 100000000, 0, 0, 0, '[]'::jsonb, now())
                """, ACCOUNT_OTHER_SEASON);
    }

    /** 폐장에 닫히지 않은 매수 예약. 현금을 잠근 채 OPEN 으로 남아 있다. */
    private void givenOpenBuyOrder(long accountId, long instrumentId, String lockedAmount) {
        jdbc.update("UPDATE accounts SET locked_krw = CAST(? AS NUMERIC) WHERE id = ?",
                lockedAmount, accountId);
        jdbc.update("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    locked_amount, status, expires_at, created_tick_seq, created_at)
                VALUES (?, ?, 'BUY', 100, 1, 'KRW', CAST(? AS NUMERIC), 'OPEN', now(), ?, now())
                """, accountId, instrumentId, lockedAmount, TICK_1);
    }

    /** 취득 총액은 평가에 쓰이지 않지만 수량이 있으면 0 일 수 없다 (V14 CHECK). */
    private void givenPosition(long accountId, long instrumentId, int quantity) {
        jdbc.update("INSERT INTO positions (account_id, instrument_id, quantity, total_cost) VALUES (?, ?, ?, 1)",
                accountId, instrumentId, quantity);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }

    /** 확정 완료 도메인 이벤트가 커밋 뒤 한 번만 나가는지 보기 위한 수신함. */
    @TestComponent
    static class FinalizedEvents {
        final List<DailySettlementFinalizedEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DailySettlementFinalizedEvent event) {
            received.add(event);
        }
    }
}
