package com.ssafy.a605.backend.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 매 틱 계좌 평가가 최고점과 최대 낙폭을 쌓는가 ({@code rpt.md} 최대 낙폭 - 구현에서 확정).
 *
 * <p><b>이 값은 지금 쌓지 않으면 만들 수 없다.</b> 평가액 시계열을 저장하는 곳이 없고,
 * 저장된 것 중 가장 촘촘한 {@code daily_settlements} 도 일차당 한 점이라 시즌 전체가 세
 * 점이다 — 시즌 리포트의 낙폭은 여기서 쌓인 값을 옮긴 것이다.
 *
 * <p>픽스처의 검증 종목 A 는 종가가 {@code 10,000 +- tick x 100} 이라, 10 주를 들고
 * 틱을 넘기면 평가액이 101,000 -> 98,000 -> 103,000 으로 움직인다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class AccountDrawdownApiTest {

    private static final long ACCOUNT_ID = 952L;

    /** 1 일차 첫 틱(2020-01-02, 종가 10,100). */
    private static final long FIRST_TICK = 1L;
    private static final LocalDate FIRST_DATE = LocalDate.of(2020, 1, 2);

    /** 1 일차 둘째 틱(2020-01-03, 종가 9,800). 낙폭을 만드는 하락이다. */
    private static final long SECOND_TICK = 2L;
    private static final LocalDate SECOND_DATE = LocalDate.of(2020, 1, 3);

    /** 1 일차 셋째 틱(2020-01-06, 종가 10,300). 최고점을 새로 찍는다. */
    private static final long THIRD_TICK = 3L;
    private static final LocalDate THIRD_DATE = LocalDate.of(2020, 1, 6);

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        // 현금 없이 10 주만 들고 있다. 평가액이 종가를 그대로 따라간다.
        givenAccount();
        givenPosition(10);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Test
    @DisplayName("스윕_평가액이 최고점을 넘으면_최고점이 올라가고 낙폭은 그대로다")
    void sweep_newPeak_raisesPeak() {
        // 1 틱 종가 10,100 이면 평가액이 101,000 원이다.
                
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));

        assertThat(peak()).isEqualByComparingTo("101000");
        assertThat(drawdown()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("스윕_최고점에서 내려오면_그 하락률이 낙폭으로 남는다")
    void sweep_afterPeak_recordsDrawdown() {
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
        // 2 틱 종가는 9,800 이라 98,000 원이다. 101,000 대비 -2.97% 다.
        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));

        // 시계열을 저장하지 않으므로 이 값은 지금 쌓지 않으면 시즌이 끝난 뒤 만들 수 없다.
        assertThat(peak()).isEqualByComparingTo("101000");
        assertThat(drawdown()).isEqualByComparingTo("-2.97");
    }

    @Test
    @DisplayName("스윕_더 얕은 하락은_이미 기록된 낙폭을 덮지 않는다")
    void sweep_shallowerDip_keepsTheDeepest() {
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
        events.publishEvent(new TickPublishedEvent(SECOND_TICK, SECOND_DATE));
        // 3 틱 종가 10,300 은 최고점(10,100 x 10)보다 높다 - 최고점이 올라가고 낙폭은 남는다.
        events.publishEvent(new TickPublishedEvent(THIRD_TICK, THIRD_DATE));

        assertThat(peak()).isEqualByComparingTo("103000");
        assertThat(drawdown()).isEqualByComparingTo("-2.97");
    }

    private void givenAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, peak_valuation_krw, max_drawdown, created_at)
                VALUES (?, ?, ?, 0, 0, 0, 0, 100000, false, 1, 100000, 1, 0, 100000, 0, now())
                """, ACCOUNT_ID, fixture.user("낙폭주인").getId(), ReplayFixture.SEASON_ID);
    }

    /** 취득 총액은 수량 x 10,000 이다. 수량이 있는데 원가가 0 이면 제약이 막는다. */
    private void givenPosition(int quantity) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                SELECT ?, i.id, ?, CAST(? AS NUMERIC)
                FROM instruments i WHERE i.symbol = 'REPLAY_TEST_A'
                """, ACCOUNT_ID, quantity, quantity * 10_000);
    }

    private BigDecimal peak() {
        return jdbc.queryForObject("SELECT peak_valuation_krw FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_ID);
    }

    private BigDecimal drawdown() {
        return jdbc.queryForObject("SELECT max_drawdown FROM accounts WHERE id = ?",
                BigDecimal.class, ACCOUNT_ID);
    }
}
